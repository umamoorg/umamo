package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.custom.GEditableMesh2
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CGlueSource
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.cmo3.Cmo3TextureFrames
import org.umamo.interop.cmo3.editableMeshOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.storedToArtAffineForTile

/*
 * The mesh-topology half of the CMO3 export reconcile.  A drawable whose triangulation changed gets a
 * new GEditableMesh2 identity surface (point mirror, fresh vertex uids, the triangle edge set), which
 * the rest of the document hangs off, and its glues are re-bound to the new vertex ids.
 *
 * The drawables pass runs on one instance from start to end: it names the drawables it is about to
 * rewrite, rewrites them, then settles, so a glue between two rewritten drawables is bound once both
 * carry their new tables.
 */
internal class Cmo3MeshTopologyLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
) {
	private val editor = context.editor
	private val edited = context.edited
	private val textureFrames = context.textureFrames

	/** Drawables whose topology pass this export has still to run; see [expectTopologyRewrites]. */
	private val pendingTopology = HashSet<DrawableId>()

	/** Glues a topology pass left to a partner whose own pass was still pending. */
	private val deferredGlues = LinkedHashSet<Glue>()

	/**
	 * Names the drawables whose topology this export is about to rewrite, so a glue between two of them
	 * re-binds once both carry their new vertex-id tables rather than against the later one's old table.
	 *
	 * @param Collection<DrawableId> drawableIds The drawables whose topology passes follow.
	 */
	fun expectTopologyRewrites(drawableIds: Collection<DrawableId>) {
		pendingTopology.addAll(drawableIds)
	}

	/**
	 * Re-binds every glue still left to a partner whose topology pass never ran, against the tables the
	 * graph holds now - the binding it would have had without the wait, notice included.
	 */
	fun settleTopologyRewrites() {
		pendingTopology.clear()
		val waiting = deferredGlues.toList()
		deferredGlues.clear()

		for (glue in waiting) {
			lowerGluePairsFor(glue)
		}
	}

	/**
	 * Rewrites a drawable's topology surface: indices, canvas positions, UVs, and the full
	 * GEditableMesh2 rebuild (point mirror, freshly minted vertex uids, the unique triangle-edge
	 * set), then re-binds every glue touching the drawable to the new uid table; a glue whose partner's
	 * own topology pass is still to come is left to that pass, which re-binds it against both new tables.
	 *
	 * @param CArtMeshSource source         The drawable's graph source.
	 * @param Drawable       editedDrawable The edited drawable.
	 * @return Boolean True on success.
	 */
	fun lowerMeshTopology(source: CArtMeshSource, editedDrawable: Drawable): Boolean {
		val subject = editedDrawable.id.raw
		pendingTopology.remove(editedDrawable.id)
		val mesh = editedDrawable.mesh

		if (mesh == null) {
			notices.unsupported(ExportEntityCategory.Drawable, subject, ExportNoticeReason.DrawableHasNoMesh)

			return false
		}

		// CMO3: CArtMeshSource fields indices / positions / uvs.  The positions are CANVAS-frame
		// in every official file, and mesh.positions is the canvas editable mesh by the runtime's
		// contract (a MOC3 import derives it through :render's restMeshesToCanvasSpace at load); the
		// editor's atlas and mesh-edit views read this field as canvas geometry.  The forms are the
		// keyform lowering's, rebuilt from mesh.localPositions.
		source.indices = mesh.indices.copyOf()
		editor.ensureChildSlot(source, "CArtMeshSource", "indices", "keyforms")
		source.positions = mesh.positions.copyOf()
		editor.ensureChildSlot(source, "CArtMeshSource", "positions", "uvs")
		source.uvs = storedUvsFor(source, mesh.uvs, editedDrawable)
		editor.ensureChildSlot(source, "CArtMeshSource", "uvs", "texture")

		val editableMesh = editableMeshOf(source)

		if (editableMesh != null) {
			val vertexCount = mesh.vertexCount
			// CMO3: GEditableMesh2 - point mirrors the positions as its own array; pointUid gets a
			// freshly minted 0..n-1 table (glues re-bind below, so re-minting is safe); edge is the
			// unique undirected triangle-edge set; the Delaunay flag drops (the triangulation is
			// authored by the indices now).
			editableMesh.point = mesh.positions.copyOf()
			editor.ensureChildSlot(editableMesh, "GEditableMesh2", "point", "pointPriority")
			editableMesh.pointUid = IntArray(vertexCount) { vertexOrdinal -> vertexOrdinal }
			editor.ensureChildSlot(editableMesh, "GEditableMesh2", "pointUid", "meshGuid")
			editableMesh.nextPointUid = vertexCount
			editor.ensurePresentAttr(editableMesh, "GEditableMesh2", "nextPointUid")
			editableMesh.useDelaunayTriangulation = false
			editor.ensurePresentAttr(editableMesh, "GEditableMesh2", "useDelaunayTriangulation")

			if (vertexCount <= Short.MAX_VALUE.toInt()) {
				editableMesh.edge = triangleEdges(mesh.indices)
				editor.ensureChildSlot(editableMesh, "GEditableMesh2", "edge", "edgePriority")
			} else {
				notices.unsupported(
					ExportEntityCategory.Drawable,
					subject,
					ExportNoticeReason.VertexCountExceedsEdgeTable(vertexCount, Short.MAX_VALUE.toInt()),
				)
			}

			// Priority arrays resize to the new counts, keeping whichever array type was stored.
			editableMesh.pointPriority = resizedPriorityArray(editableMesh.pointPriority, vertexCount, freshDefault = 10)
			editor.ensureChildSlot(editableMesh, "GEditableMesh2", "pointPriority", "edge")
			(editableMesh.edge as? ShortArray)?.let { edges ->
				editableMesh.edgePriority = resizedPriorityArray(editableMesh.edgePriority, edges.size / 2, freshDefault = 30)
				editor.ensureChildSlot(editableMesh, "GEditableMesh2", "edgePriority", "pointUid")
			}
		}

		for (glue in edited.glues) {
			if (glue.meshA != editedDrawable.id && glue.meshB != editedDrawable.id) {
				continue
			}

			// A partner whose uid table is still missing is a fresh shell whose own topology pass is
			// later in this same export - ITS re-bind loop covers this glue with both tables minted,
			// so a premature attempt here would only raise a spurious notice.
			if (uidTableOf(glue.meshA) == null || uidTableOf(glue.meshB) == null) {
				continue
			}

			// A partner whose topology pass is still to come holds its OLD uid table until then, and
			// its own pass re-binds this glue against both new tables.
			if (glue.meshA in pendingTopology || glue.meshB in pendingTopology) {
				deferredGlues.add(glue)

				continue
			}

			deferredGlues.remove(glue)
			lowerGluePairsFor(glue)
		}

		return true
	}

	/**
	 * Rewrites a glue's vertex binding (weights + bindVertexUids) from the edited model's
	 * index-based pairs against both meshes' CURRENT uid tables.
	 *
	 * @param Glue editedGlue The edited glue.
	 * @return Boolean True on success.
	 */
	fun lowerGluePairsFor(editedGlue: Glue): Boolean {
		val subject = "${editedGlue.meshA.raw}+${editedGlue.meshB.raw}"
		val editedByPair = edited.glues.filter { it.meshA == editedGlue.meshA && it.meshB == editedGlue.meshB }
		val ordinal = editedByPair.indexOfFirst { it === editedGlue }.coerceAtLeast(0)
		val glueSource =
			index.glueSources.filter { candidate ->
				index.drawableIdStrByUuid[uuidOf(candidate.targetArtMeshA_guid)] == editedGlue.meshA.raw &&
					index.drawableIdStrByUuid[uuidOf(candidate.targetArtMeshB_guid)] == editedGlue.meshB.raw
			}.getOrNull(ordinal)

		if (glueSource == null) {
			notices.unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.NoMatchingSourceToReconcile)

			return false
		}

		val uidTableA = uidTableOf(editedGlue.meshA)
		val uidTableB = uidTableOf(editedGlue.meshB)

		if (uidTableA == null || uidTableB == null) {
			notices.unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.GluedMeshHasNoUidTable)

			return false
		}

		val pairCount = editedGlue.pairs.size
		val weights = FloatArray(pairCount * 2)
		val uids = LongArray(pairCount * 2)

		for (pairIndex in 0 until pairCount) {
			val pair = editedGlue.pairs[pairIndex]
			val uidA = uidTableA.getOrNull(pair.indexA)
			val uidB = uidTableB.getOrNull(pair.indexB)

			if (uidA == null || uidB == null) {
				notices.unsupported(ExportEntityCategory.Glue, subject, ExportNoticeReason.GluePairIndexesPastUidTable)

				return false
			}

			// CMO3: CGlueSource fields weights / bindVertexUids - [A, B]-interleaved per pair.
			weights[pairIndex * 2] = pair.weightA
			weights[pairIndex * 2 + 1] = pair.weightB
			uids[pairIndex * 2] = uidA.toLong()
			uids[pairIndex * 2 + 1] = uidB.toLong()
		}

		glueSource.weights = weights
		editor.ensureChildSlot(glueSource, "CGlueSource", "weights", "keyforms")
		glueSource.bindVertexUids = uids
		editor.ensureChildSlot(glueSource, "CGlueSource", "bindVertexUids", "tabPosOnCanvas")

		return true
	}

	/**
	 * The point uid table of a drawable's editable mesh.
	 *
	 * @param DrawableId drawableId The drawable.
	 * @return IntArray? The table, or null when the drawable has no source or no table.
	 */
	private fun uidTableOf(drawableId: DrawableId): IntArray? =
		index.drawableByIdStr[drawableId.raw]?.let { source -> editableMeshOf(source)?.pointUid as? IntArray }

	/**
	 * The unique undirected triangle edges as the editable mesh's short-indexed pair list.
	 *
	 * @param IntArray indices The triangle list, three vertex indices per triangle.
	 * @return ShortArray The edges, two vertex indices per edge.
	 */
	private fun triangleEdges(indices: IntArray): ShortArray {
		val seen = LinkedHashSet<Int>()
		var triangleStart = 0

		while (triangleStart + 2 < indices.size) {
			for (cornerIndex in 0 until 3) {
				val endpointA = indices[triangleStart + cornerIndex]
				val endpointB = indices[triangleStart + (cornerIndex + 1) % 3]
				val low = minOf(endpointA, endpointB)
				val high = maxOf(endpointA, endpointB)
				seen.add(low shl 16 or high)
			}

			triangleStart += 3
		}

		val edges = ShortArray(seen.size * 2)
		var writeIndex = 0

		for (packed in seen) {
			edges[writeIndex++] = (packed shr 16).toShort()
			edges[writeIndex++] = (packed and 0xFFFF).toShort()
		}

		return edges
	}

	/**
	 * A priority array of [count] entries matching [existing]'s stored type, or a fresh byte
	 * array filled with [freshDefault] when none was stored.
	 *
	 * CMO3: GEditableMesh2 fields pointPriority / edgePriority - byte arrays on every corpus
	 * mesh, never null (the editor NPEs on a null); points carry 10, triangle edges 30.
	 *
	 * @param Any? existing The stored priority array, or null.
	 * @param Int count The entry count the new array needs.
	 * @param Byte freshDefault The priority a fresh byte array is filled with.
	 * @return Any? The new array.
	 */
	private fun resizedPriorityArray(existing: Any?, count: Int, freshDefault: Byte): Any? =
		when (existing) {
			is IntArray -> IntArray(count)
			is ShortArray -> ShortArray(count)
			is FloatArray -> FloatArray(count)
			is ByteArray -> ByteArray(count) { freshDefault }
			else -> ByteArray(count) { freshDefault }
		}

	/**
	 * The UVs as CMO3 stores them, in the frame the drawable stores ([Cmo3TextureFrames.storedUvsOf]), off
	 * its tile's page through the edited placement when the tile has one.
	 *
	 * @param CArtMeshSource source         The drawable's graph source.
	 * @param FloatArray     uvs            The model-frame UVs.
	 * @param Drawable       editedDrawable The edited drawable, whose tile the UVs address.
	 * @return FloatArray The stored-frame UVs.
	 */
	private fun storedUvsFor(source: CArtMeshSource, uvs: FloatArray, editedDrawable: Drawable): FloatArray =
		textureFrames.storedUvsOf(source, uvs, editedDrawable.atlasTileId?.let { tileId -> edited.atlas.storedToArtAffineForTile(tileId) })
}