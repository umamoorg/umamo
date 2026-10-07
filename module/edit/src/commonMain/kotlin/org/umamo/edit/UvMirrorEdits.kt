package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel

/*
 * Texture-coordinate mirroring over the Edit-mode selection: the UV editor's Mirror U / V commands, and the
 * coverage rule they share with the UV operator latch - which session meshes carry an editable UV array the
 * selection covers.  A mirror is a pivot scale by -1 on one axis, so it borrows the transform pivots and
 * MeshTransforms rather than carrying geometry of its own.
 */

/**
 * One session mesh an Edit-mode UV operation can act on: the mesh and the vertex indices the selection
 * covers on it.
 *
 * @property DrawableMesh mesh The mesh, carrying an editable UV array.
 * @property Set<Int> vertexIndices The covered vertex indices; never empty.
 */
internal class EditableUvCoverage(val mesh: DrawableMesh, val vertexIndices: Set<Int>)

/**
 * The session meshes an Edit-mode UV operation covers, in selection order: each selected mesh that carries
 * an editable UV array (imports may leave uvs empty; a malformed length is excluded by the same guard as
 * withMeshUvs) on which the selection covers at least one vertex.  The one rule both the UV operator latch
 * and the operations ask, so a latched gesture always has something to act on.
 *
 * @param MeshSelection selection The live Edit-mode selection.
 * @param PuppetModel model The live model.
 * @param Set<DrawableId>? shownDrawableIds The meshes the authoring surface shows, or null for every selected
 *   mesh.
 * @return Map<DrawableId, EditableUvCoverage> The covered meshes keyed by drawable, in selection order.
 */
internal fun editableUvCoverage(selection: MeshSelection, model: PuppetModel, shownDrawableIds: Set<DrawableId>?): Map<DrawableId, EditableUvCoverage> {
	val coverage = LinkedHashMap<DrawableId, EditableUvCoverage>()
	for (drawableId in selection.drawableIds) {
		if (shownDrawableIds != null && drawableId !in shownDrawableIds) {
			continue
		}
		val mesh = model.drawables.firstOrNull { drawable -> drawable.id == drawableId }?.mesh ?: continue
		if (mesh.uvs.isEmpty() || mesh.uvs.size != mesh.positions.size) {
			continue
		}
		val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(drawableId), mesh.indices)
		if (covered.isEmpty()) {
			continue
		}
		coverage[drawableId] = EditableUvCoverage(mesh, covered)
	}
	return coverage
}

/**
 * Mirrors the selected vertices' texture coordinates about the transform pivot as ONE undo step -
 * the UV editor's Mirror U / V commands, serving the duplicated-and-flipped texture regions
 * workflow (both eyes sampling one eye texture).  The pivot follows [SessionToolSettings.pivotMode], resolved in UV
 * space: Median Point mirrors about the covered vertices' combined median across every edited mesh,
 * Individual Origins mirrors each connectivity island about its own median, Active Element anchors
 * on the active element's median, and Cursor anchors on the UV cursor (each falling back to the
 * combined median when unresolvable - a mirror should never silently do nothing because a pivot was
 * never placed).  Mirroring is axis-aligned, so operating directly in normalized UV space matches
 * the on-screen result regardless of the shown surface's size.  A no-op outside Edit mode or with an
 * empty selection; a notice explains when no covered mesh carries an editable UV array.
 *
 * [frame] names the space the user is mirroring in, which for this operation is the whole question:
 * an axis in the atlas page's frame is a different axis in a rotated or mirrored source layer's.
 * With no frame the stored coordinates ARE the authoring space (the page view), and the whole
 * conversion drops out.  Untouched vertices keep their exact stored values either way, so a mirror
 * never marks a vertex changed that it did not move.
 *
 * [shownDrawableIds] narrows the mirror to the meshes the authoring surface shows.  An edit can span
 * pages and layers, and a mesh on another one is measured in another space: its coordinates would
 * move the shared pivot and be reflected through a frame they are not in.
 *
 * @param Boolean mirrorU True to mirror horizontally (u about the pivot), false vertically (v).
 * @param UvFrame? frame The authoring frame, or null when the stored coordinates are the frame.
 * @param Set<DrawableId>? shownDrawableIds The meshes the authoring surface shows, or null for every
 *   selected mesh.
 */
fun EditorSession.mirrorSelectedUvs(mirrorU: Boolean, frame: UvFrame? = null, shownDrawableIds: Set<DrawableId>? = null) {
	if (mode.value != EditorMode.Edit) {
		return
	}
	val selection = meshSelection.value
	if (selection.isEmpty) {
		// Speaks up, unlike the mode guard above: reaching Mirror with an empty selection is an ordinary
		// mistake a rigger makes, where a request arriving outside Edit mode is stale bus traffic the
		// palette already gates.
		emitNotice("notice.uv.noSelection", NoticePlacement.NearCursor)
		return
	}
	val model = model.value
	// Each session mesh's covered vertices, by the rule the UV operator latch asked (editableUvCoverage),
	// so a latched gesture always finds something to mirror.
	val coverage = editableUvCoverage(selection, model, shownDrawableIds)
	if (coverage.isEmpty()) {
		emitNotice("notice.uv.noUvs", NoticePlacement.NearCursor)
		return
	}
	val coveredByDrawable = coverage.mapValues { (_, covered) -> covered.vertexIndices }
	val meshByDrawable = coverage.mapValues { (_, covered) -> covered.mesh }
	// The whole operation runs in the authoring frame: pivots, island medians, and the reflection
	// itself.  With no frame these arrays are the stored ones and the conversions are absent.
	val frameUvsByDrawable =
		meshByDrawable.mapValues { (_, mesh) -> frame?.toFrame(mesh.uvs) ?: mesh.uvs }
	val sharedPivot =
		if (pivotMode.value == TransformPivotMode.IndividualOrigins) {
			null
		} else {
			resolveUvMirrorPivot(coveredByDrawable, meshByDrawable, frameUvsByDrawable, selection, frame)
		}
	val newUvsByDrawable = LinkedHashMap<DrawableId, FloatArray>()
	for ((drawableId, covered) in coveredByDrawable) {
		val mesh = meshByDrawable.getValue(drawableId)
		val frameUvs = frameUvsByDrawable.getValue(drawableId)
		val groups =
			if (sharedPivot == null) {
				TransformPivots.islandGroups(frameUvs, covered, mesh.indices)
			} else {
				TransformPivots.sharedGroup(covered, sharedPivot.first, sharedPivot.second)
			}
		var mirroredUvs = frameUvs
		for (group in groups) {
			mirroredUvs =
				MeshTransforms.scaleVerticesAxis(
					mirroredUvs,
					group.vertexIndices,
					if (mirrorU) -1f else 1f,
					if (mirrorU) 1f else -1f,
					group.pivotX,
					group.pivotY,
				)
		}
		// Back to the stored form, then overwrite ONLY the covered vertices onto the current stored
		// array: a frame round trip is exact in the reals but not in floats, so rebuilding from the
		// stored values is what keeps an untouched vertex bit-identical (and out of the export's
		// changed-uv set).  Without a frame the mirrored array is already stored-form and this is a
		// straight copy of the moved components.
		val storedMirrored = frame?.fromFrame(mirroredUvs) ?: mirroredUvs
		val newUvs = mesh.uvs.copyOf()
		for (vertexIndex in covered) {
			newUvs[vertexIndex * 2] = storedMirrored[vertexIndex * 2]
			newUvs[vertexIndex * 2 + 1] = storedMirrored[vertexIndex * 2 + 1]
		}
		newUvsByDrawable[drawableId] = newUvs
	}
	commitMeshUvs(MeshChange.MirrorUvs(newUvsByDrawable.keys.toList(), mirrorU), newUvsByDrawable)
}

/**
 * Resolves the shared UV mirror pivot for the single-anchor pivot modes: Cursor anchors on the UV
 * cursor and Active Element on the active element's covered median, each falling back to the
 * combined covered median across every edited mesh - which is also the Median Point result.
 *
 * Resolved in the AUTHORING frame throughout, so every anchor means the same thing the reflection
 * does - including the UV cursor, which is stored in atlas coordinates and converts in like the
 * meshes do.
 *
 * @param Map<DrawableId, Set<Int>> coveredByDrawable Each edited mesh's covered vertex indices.
 * @param Map<DrawableId, DrawableMesh> meshByDrawable Each edited mesh, keyed like the covered map.
 * @param Map<DrawableId, FloatArray> frameUvsByDrawable Each edited mesh's uvs in the authoring frame.
 * @param MeshSelection selection The live selection (for the active element).
 * @param UvFrame? frame The authoring frame, or null when the stored coordinates are the frame.
 * @return Pair<Float, Float> The pivot's (u, v), in the authoring frame.
 */
private fun EditorSession.resolveUvMirrorPivot(
	coveredByDrawable: Map<DrawableId, Set<Int>>,
	meshByDrawable: Map<DrawableId, DrawableMesh>,
	frameUvsByDrawable: Map<DrawableId, FloatArray>,
	selection: MeshSelection,
	frame: UvFrame?,
): Pair<Float, Float> {
	when (pivotMode.value) {
		TransformPivotMode.Cursor -> {
			val cursor = uvCursor.value
			if (cursor != null) {
				return frame?.pointToFrame(cursor.u, cursor.v) ?: (cursor.u to cursor.v)
			}
		}

		TransformPivotMode.ActiveElement -> {
			val active = selection.activeElement
			val activeMesh = active?.let { activeElement -> meshByDrawable[activeElement.drawableId] }
			val activeFrameUvs = active?.let { activeElement -> frameUvsByDrawable[activeElement.drawableId] }
			if (active != null && activeMesh != null && activeFrameUvs != null) {
				val activeCovered = MeshTopology.coveredVertexIndices(setOf(active.element), activeMesh.indices)
				if (activeCovered.isNotEmpty()) {
					return MeshTransforms.medianPivot(activeFrameUvs, activeCovered)
				}
			}
		}

		TransformPivotMode.MedianPoint, TransformPivotMode.IndividualOrigins -> {}
	}
	var sumU = 0f
	var sumV = 0f
	var coveredCount = 0
	for ((drawableId, covered) in coveredByDrawable) {
		val uvs = frameUvsByDrawable.getValue(drawableId)
		for (vertexIndex in covered) {
			sumU += uvs[vertexIndex * 2]
			sumV += uvs[vertexIndex * 2 + 1]
			coveredCount += 1
		}
	}
	if (coveredCount == 0) {
		// Unreachable today (callers pre-filter empty covered sets); the authoring frame's center is
		// a safe anchor.
		return 0.5f to 0.5f
	}
	return (sumU / coveredCount) to (sumV / coveredCount)
}