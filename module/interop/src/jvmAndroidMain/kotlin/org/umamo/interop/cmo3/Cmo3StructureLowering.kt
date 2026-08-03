package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3GraphEditor
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.GEditableMesh2
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CEditableMeshExtension
import org.umamo.format.cmo3.model.gen.CParameterGroup
import org.umamo.format.cmo3.model.gen.CParameterGroupSet
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.Type
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.ExportNotice
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable

/*
 * The structural half of the CMO3 export reconcile: set membership.  Creations synthesize an
 * IDENTITY SHELL only (fresh guid/id, the texture/extension chain a duplicate shares with its
 * source) and are appended to their source set; the caller then routes the new entity through the
 * ordinary Changed lowering with every field flagged, so field writes flow through the one tested
 * path rather than a parallel synthesis copy.  Deletions remove the source AND strip its guid from
 * every parts-panel child list (a removed guid the org rebuild would otherwise preserve as an
 * unknown entry); the dangling cross-references (masks, axes, glues) fall out of the model-driven
 * Changed diffs, and the shared pool is pruned once at the end.
 *
 * Topology rewrites and glue vertex re-binding also live here: they regenerate the GEditableMesh2
 * identity surface (point mirror, fresh vertex uids, the triangle edge set) that the rest of the
 * document hangs off.
 */
internal class Cmo3StructureLowering(
	private val modelSource: CModelSource,
	private val index: Cmo3GraphIndex,
	private val editor: Cmo3GraphEditor,
	private val edited: PuppetModel,
	private val notices: MutableList<ExportNotice>,
) {
	/** True once any deletion ran - the caller prunes the shared pool exactly once at the end. */
	var deletedAnything: Boolean = false
		private set

	private fun unsupported(category: String, subject: String, detail: String) {
		notices.add(ExportNotice.UnsupportedChange(category, subject, detail))
	}

	private fun freshGuidLike(template: Guid?, fallbackKind: String): Guid =
		Guid(template?.kind ?: fallbackKind).apply {
			uuid = java.util.UUID.randomUUID().toString()
			note = template?.note ?: "(no debug info)"
		}

	private fun freshIdLike(template: Id?, fallbackKind: String, idStr: String): Id =
		Id(template?.kind ?: fallbackKind).apply { idstr = idStr }

	private fun appendToCollection(owner: Any, ownerTag: String, property: String, current: Any?, assign: (MutableList<Any?>) -> Unit, element: Any) {
		val mutable = current as? MutableList<Any?>
		if (mutable != null) {
			mutable.add(element)
			return
		}
		val fresh: MutableList<Any?> = CArrayList()
		fresh.add(element)
		assign(fresh)
		editor.ensureChildSlot(owner, ownerTag, property)
	}

	private fun removeFromCollection(current: Any?, matches: (Any?) -> Boolean): Boolean {
		val mutable = current as? MutableList<Any?> ?: return false
		return mutable.removeAll(matches)
	}

	/** Strips [uuid] from every parts-panel child list (all user parts plus the synthetic root). */
	private fun stripFromChildLists(uuid: String?) {
		if (uuid == null) {
			return
		}
		val parts = index.userPartSources + listOfNotNull(index.rootPartSource)
		for (part in parts) {
			(part._childGuids as? MutableList<Any?>)?.removeAll { entry -> Cmo3Import.uuidOf(entry) == uuid }
		}
	}

	/**
	 * Synthesizes the identity shell for a created parameter and appends it in document order (the
	 * PARAMETER_ORDER lowering re-sorts afterwards).
	 *
	 * @param Parameter editedParameter The created parameter.
	 * @return Boolean True on success.
	 */
	fun synthesizeParameter(editedParameter: Parameter): Boolean {
		val sourceSet = modelSource.parameterSourceSet as? CParameterSourceSet
		if (sourceSet == null) {
			unsupported("parameter", editedParameter.id.raw, "model has no parameter source set to create into")
			return false
		}
		val template = index.parameterSources.firstOrNull()
		val fresh =
			CParameterSource().apply {
				// CMO3: CParameterSource - identity plus the editor conventions cloned from a sibling
				// (decimal places, snap epsilon, the explicit paramType).
				guid = freshGuidLike(template?.guid as? Guid, "CParameterGuid")
				id = freshIdLike(template?.id as? Id, "CParameterId", editedParameter.id.raw)
				decimalPlaces = template?.decimalPlaces ?: 0
				snapEpsilon = template?.snapEpsilon ?: 0f
				minValue = editedParameter.min
				maxValue = editedParameter.max
				defaultValue = editedParameter.default
				isRepeat = false
				paramType = if (editedParameter.kind == ParameterKind.BLEND_SHAPE) Type.MORPH_TARGET else template?.paramType
				name = editedParameter.name
				combined = false
				parentGroupGuid = index.rootParameterGroup?.guid
			}
		appendToCollection(sourceSet, "CParameterSourceSet", "_sources", sourceSet._sources, { sourceSet._sources = it }, fresh)
		return true
	}

	/** Removes a deleted parameter's source. */
	fun deleteParameter(parameterId: ParameterId) {
		val sourceSet = modelSource.parameterSourceSet as? CParameterSourceSet ?: return
		if (removeFromCollection(sourceSet._sources) { entry ->
				entry is CParameterSource && Cmo3Import.idStrOf(entry.id) == parameterId.raw
			}
		) {
			deletedAnything = true
		}
	}

	/**
	 * Synthesizes the identity shell for a created parameter group.
	 *
	 * @param ParameterGroupId groupId The created group's id.
	 * @return Boolean True on success.
	 */
	fun synthesizeParameterGroup(groupId: ParameterGroupId): Boolean {
		val groupSet = modelSource.parameterGroupSet as? CParameterGroupSet
		if (groupSet == null) {
			unsupported("parameter group", groupId.raw, "model has no parameter group set to create into")
			return false
		}
		val editedGroup = findEditedGroup(groupId)
		if (editedGroup == null) {
			unsupported("parameter group", groupId.raw, "created group is not in the edited tree")
			return false
		}
		val template = index.groupSources.firstOrNull() ?: index.rootParameterGroup
		val fresh =
			CParameterGroup().apply {
				// CMO3: CParameterGroup - identity shell; name/open state/children flow through the
				// ordinary group lowering.
				guid = freshGuidLike(template?.guid as? Guid, "CParameterGroupGuid")
				id = freshIdLike(template?.id as? Id, "CParameterGroupId", groupId.raw)
				name = editedGroup.name
				folderIsOpened = editedGroup.initiallyOpen
				_childGuids = CArrayList<Any?>()
				parentGroupGuid = index.rootParameterGroup?.guid
			}
		appendToCollection(groupSet, "CParameterGroupSet", "_groups", groupSet._groups, { groupSet._groups = it }, fresh)
		return true
	}

	/** Removes a deleted parameter group's source. */
	fun deleteParameterGroup(groupId: ParameterGroupId) {
		val groupSet = modelSource.parameterGroupSet as? CParameterGroupSet ?: return
		if (removeFromCollection(groupSet._groups) { entry ->
				entry is CParameterGroup && Cmo3Import.idStrOf(entry.id) == groupId.raw
			}
		) {
			deletedAnything = true
		}
	}

	/**
	 * Synthesizes the identity shell for a session-created drawable (an Object-mode duplicate):
	 * fresh guid/id and editable mesh, the texture binding SHARED with its texture source (the
	 * atlas slot both render from; no new CAFF entry).  Geometry, keyforms, and every flat field
	 * flow through the ordinary Changed lowering afterwards.
	 *
	 * @param Drawable editedDrawable The created drawable.
	 * @return Boolean True on success.
	 */
	fun synthesizeDrawable(editedDrawable: Drawable): Boolean {
		val subject = editedDrawable.id.raw
		val sourceSet = modelSource.drawableSourceSet as? org.umamo.format.cmo3.model.gen.CDrawableSourceSet
		if (sourceSet == null) {
			unsupported("drawable", subject, "model has no drawable source set to create into")
			return false
		}
		val textureSource = editedDrawable.textureSourceId?.let { index.drawableByIdStr[it.raw] }
		if (textureSource == null) {
			unsupported("drawable", subject, "created drawable has no texture source to clone; not synthesized")
			return false
		}
		val templateMesh = Cmo3Import.editableMeshOf(textureSource)
		val ownerPartId = edited.partByDrawable()[editedDrawable.id]
		val ownerGuid = ownerPartId?.let { index.partByIdStr[it.raw]?.guid } ?: index.rootPartSource?.guid
		val fresh =
			CArtMeshSource().apply {
				// CMO3: CArtMeshSource identity shell.  The texture and texture-input extension are
				// the SOURCE drawable's own objects (the writer hoists the shared references), so the
				// duplicate samples the same atlas slot without new image-chain entries.
				guid = freshGuidLike(textureSource.guid as? Guid, "CDrawableGuid")
				id = freshIdLike(textureSource.id as? Id, "CDrawableId", subject)
				parentGuid = ownerGuid
				texture = textureSource.texture
				textureState = textureSource.textureState
				_extensions =
					CArrayList<Any?>().apply {
						Cmo3Import.elementsOf(textureSource._extensions)
							.filterIsInstance<CTextureInputExtension>()
							.firstOrNull()
							?.let(::add)
						add(
							CEditableMeshExtension().apply {
								guid = freshGuidLike(null, "CExtensionGuid")
								editableMesh =
									GEditableMesh2().apply {
										meshGuid = freshGuidLike(templateMesh?.meshGuid as? Guid, "CMeshGuid")
										coordType = templateMesh?.coordType
										useDelaunayTriangulation = false
									}
							},
						)
					}
			}
		appendToCollection(sourceSet, "CDrawableSourceSet", "_sources", sourceSet._sources, { sourceSet._sources = it }, fresh)
		return true
	}

	/** Removes a deleted drawable's source and strips its guid from every panel child list. */
	fun deleteDrawable(drawableId: DrawableId) {
		val sourceSet = modelSource.drawableSourceSet as? org.umamo.format.cmo3.model.gen.CDrawableSourceSet ?: return
		val source = index.drawableByIdStr[drawableId.raw]
		if (removeFromCollection(sourceSet._sources) { entry -> entry === source }) {
			stripFromChildLists(Cmo3Import.uuidOf(source?.guid))
			deletedAnything = true
		}
	}

	/** Removes a deleted part's source and strips its guid from every panel child list. */
	fun deletePart(partIdRaw: String) {
		val sourceSet = modelSource.partSourceSet as? org.umamo.format.cmo3.model.gen.CPartSourceSet ?: return
		val source = index.partByIdStr[partIdRaw]
		if (removeFromCollection(sourceSet._sources) { entry -> entry === source }) {
			stripFromChildLists(Cmo3Import.uuidOf(source?.guid))
			deletedAnything = true
		}
	}

	/** Removes a deleted deformer's source and strips its guid from every panel child list. */
	fun deleteDeformer(deformerIdRaw: String) {
		val sourceSet = modelSource.deformerSourceSet as? org.umamo.format.cmo3.model.gen.CDeformerSourceSet ?: return
		val source = index.deformerByIdStr[deformerIdRaw]
		if (removeFromCollection(sourceSet._sources) { entry -> entry === source }) {
			stripFromChildLists(Cmo3Import.uuidOf(source?.guid))
			deletedAnything = true
		}
	}

	/** Removes a deleted glue's source, resolved by mesh pair plus ordinal. */
	fun deleteGlue(meshA: DrawableId, meshB: DrawableId, ordinal: Int) {
		val sourceSet = modelSource.affecterSourceSet as? org.umamo.format.cmo3.model.gen.CAffecterSourceSet ?: return
		val matching =
			index.glueSources.filter { glueSource ->
				index.drawableIdStrByUuid[Cmo3Import.uuidOf(glueSource.targetArtMeshA_guid)] == meshA.raw &&
					index.drawableIdStrByUuid[Cmo3Import.uuidOf(glueSource.targetArtMeshB_guid)] == meshB.raw
			}
		val victim = matching.getOrNull(ordinal) ?: return
		if (removeFromCollection(sourceSet._sources) { entry -> entry === victim }) {
			deletedAnything = true
		}
	}

	/**
	 * Rewrites a drawable's topology surface: indices, base positions, UVs, and the full
	 * GEditableMesh2 rebuild (point mirror, freshly minted vertex uids, the unique triangle-edge
	 * set), then re-binds every glue touching the drawable to the new uid table.
	 *
	 * @param CArtMeshSource source         The drawable's graph source.
	 * @param Drawable       editedDrawable The edited drawable.
	 * @return Boolean True on success.
	 */
	fun lowerMeshTopology(source: CArtMeshSource, editedDrawable: Drawable): Boolean {
		val subject = editedDrawable.id.raw
		val mesh = editedDrawable.mesh
		if (mesh == null) {
			unsupported("drawable", subject, "a drawable without a mesh cannot be written to CMO3")
			return false
		}
		// CMO3: CArtMeshSource fields indices / positions / uvs.
		source.indices = mesh.indices.copyOf()
		editor.ensureChildSlot(source, "CArtMeshSource", "indices", "keyforms")
		source.positions = mesh.positions.copyOf()
		editor.ensureChildSlot(source, "CArtMeshSource", "positions", "uvs")
		source.uvs = storedUvsFor(source, mesh.uvs)
		editor.ensureChildSlot(source, "CArtMeshSource", "uvs", "texture")

		val editableMesh = Cmo3Import.editableMeshOf(source)
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
				unsupported("drawable", subject, "vertex count exceeds the editable mesh's short-indexed edge table")
			}
			// Priority arrays resize to the new counts, keeping whichever array type was stored.
			editableMesh.pointPriority = resizedPriorityArray(editableMesh.pointPriority, vertexCount)
			(editableMesh.edge as? ShortArray)?.let { edges ->
				editableMesh.edgePriority = resizedPriorityArray(editableMesh.edgePriority, edges.size / 2)
			}
		}
		for (glue in edited.glues) {
			if (glue.meshA == editedDrawable.id || glue.meshB == editedDrawable.id) {
				lowerGluePairsFor(glue)
			}
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
				index.drawableIdStrByUuid[Cmo3Import.uuidOf(candidate.targetArtMeshA_guid)] == editedGlue.meshA.raw &&
					index.drawableIdStrByUuid[Cmo3Import.uuidOf(candidate.targetArtMeshB_guid)] == editedGlue.meshB.raw
			}.getOrNull(ordinal)
		if (glueSource == null) {
			unsupported("glue", subject, "no matching CMO3 source to re-bind")
			return false
		}
		val uidTableA = uidTableOf(editedGlue.meshA)
		val uidTableB = uidTableOf(editedGlue.meshB)
		if (uidTableA == null || uidTableB == null) {
			unsupported("glue", subject, "a glued mesh has no editable-mesh uid table")
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
				unsupported("glue", subject, "a glue pair indexes past its mesh's uid table")
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

	private fun uidTableOf(drawableId: DrawableId): IntArray? =
		index.drawableByIdStr[drawableId.raw]?.let { source -> Cmo3Import.editableMeshOf(source)?.pointUid as? IntArray }

	/** The unique undirected triangle edges as the editable mesh's short-indexed pair list. */
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

	/** A zero-filled priority array of [count] entries matching [existing]'s stored type. */
	private fun resizedPriorityArray(existing: Any?, count: Int): Any? =
		when (existing) {
			is IntArray -> IntArray(count)
			is ShortArray -> ShortArray(count)
			is FloatArray -> FloatArray(count)
			else -> existing
		}

	/**
	 * The UVs as CMO3 stores them: verbatim for a packed (atlas-region) drawable, through the
	 * forward model-image affine for an unpacked one.
	 *
	 * @param CArtMeshSource source The drawable's graph source.
	 * @param FloatArray     uvs    The model-frame UVs.
	 * @return FloatArray The stored-frame UVs.
	 */
	private fun storedUvsFor(source: CArtMeshSource, uvs: FloatArray): FloatArray {
		if (Cmo3Import.hasAtlasRegion(source)) {
			return uvs.copyOf()
		}
		// CMO3: GTexture2D field transformImageResource01toLogical01 (the import applied its inverse).
		val affine = (source.texture as? GTexture2D)?.transformImageResource01toLogical01 as? CAffine ?: return uvs.copyOf()
		val isIdentity =
			affine.m00 == 1f &&
				affine.m01 == 0f &&
				affine.m02 == 0f &&
				affine.m10 == 0f &&
				affine.m11 == 1f &&
				affine.m12 == 0f
		if (isIdentity) {
			return uvs.copyOf()
		}
		val result = FloatArray(uvs.size)
		var component = 0
		while (component + 1 < uvs.size) {
			val u = uvs[component]
			val v = uvs[component + 1]
			result[component] = affine.m00 * u + affine.m01 * v + affine.m02
			result[component + 1] = affine.m10 * u + affine.m11 * v + affine.m12
			component += 2
		}
		return result
	}

	private fun findEditedGroup(groupId: ParameterGroupId): ParameterNode.Group? {
		fun walk(nodes: List<ParameterNode>): ParameterNode.Group? {
			for (node in nodes) {
				if (node is ParameterNode.Group) {
					if (node.id == groupId) {
						return node
					}
					walk(node.children)?.let { return it }
				}
			}
			return null
		}
		return walk(edited.parameterTree)
	}
}
