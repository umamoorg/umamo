package org.umamo.render.puppet

import org.umamo.runtime.model.DrawableId

/**
 * One overlay mesh's place in the overlay position store.
 *
 * @property MeshOverlayMesh mesh The mesh.
 * @property Int baseOffset Its first vertex's index in the store.
 */
internal class OverlayPlacement(
	val mesh: MeshOverlayMesh,
	val baseOffset: Int,
)

/**
 * The addressing plan for an overlay's position store: which of its meshes are placed, where each one's
 * vertices sit, and the store capacity they need.
 *
 * @property List<OverlayPlacement> placed The placed meshes, in overlay order.
 * @property Int totalVertexCount The store's total vertex count.
 */
internal class OverlayLayout(
	val placed: List<OverlayPlacement>,
	val totalVertexCount: Int,
)

/**
 * Plans [overlay]'s store layout against the residents: the meshes are walked in overlay order, each
 * placed one gets the next region, so the order DEFINES the addressing, as the glue layout's walk does.
 *
 * This is where the pairing rule lives: a mesh is placed only when a resident exists for it, the
 * resident's vertex count is the count the data was built against, and, when faces draw, the face flags
 * are empty or one per resident triangle.  A disagreeing mesh is left out of this frame rather than
 * guessed at, and comes back on the next publish.  Pure, so the rule is testable without a device.
 *
 * @param MeshOverlay overlay The overlay to place.
 * @param Map<DrawableId, Int> residentVertexCountById Each resident drawable's uploaded vertex count.
 * @param Map<DrawableId, Int> residentTriangleCountById Each resident drawable's uploaded triangle count.
 * @return OverlayLayout The plan; empty when nothing pairs.
 */
internal fun planOverlayLayout(
	overlay: MeshOverlay,
	residentVertexCountById: Map<DrawableId, Int>,
	residentTriangleCountById: Map<DrawableId, Int>,
): OverlayLayout {
	val placed = ArrayList<OverlayPlacement>(overlay.meshes.size)
	var totalVertexCount = 0
	for (mesh in overlay.meshes) {
		val residentVertexCount = residentVertexCountById[mesh.drawableId] ?: continue
		if (residentVertexCount != mesh.vertexCount) {
			continue
		}
		if (overlay.kind == MeshOverlayKind.Edit && mesh.faceFlags.isNotEmpty()) {
			val residentTriangleCount = residentTriangleCountById[mesh.drawableId] ?: continue
			if (residentTriangleCount != mesh.faceFlags.size) {
				continue
			}
		}
		placed.add(OverlayPlacement(mesh, totalVertexCount))
		totalVertexCount += mesh.vertexCount
	}
	return OverlayLayout(placed, totalVertexCount)
}