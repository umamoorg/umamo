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
 * resident's vertex count is the count the data was built against, and, in an Edit overlay, the face flags
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

/**
 * One direct overlay mesh's place in a UV scene's position store, with the arrays it uploads and draws.
 *
 * @property MeshOverlayMesh mesh The mesh.
 * @property FloatArray positions Its display positions.
 * @property IntArray triangleIndices Its triangle indices.
 * @property Int baseOffset Its first vertex's index in the store.
 */
internal class DirectOverlayPlacement(
	val mesh: MeshOverlayMesh,
	val positions: FloatArray,
	val triangleIndices: IntArray,
	val baseOffset: Int,
)

/**
 * The addressing plan for a direct overlay's position store.
 *
 * @property List<DirectOverlayPlacement> placed The placed meshes, in overlay order.
 * @property Int totalVertexCount The store's total vertex count.
 */
internal class DirectOverlayLayout(
	val placed: List<DirectOverlayPlacement>,
	val totalVertexCount: Int,
)

/**
 * Plans a direct overlay's store layout: the meshes are walked in overlay order and each placed one gets the
 * next region, as [planOverlayLayout] does, but each mesh pairs against its OWN positions and triangle
 * indices rather than a resident drawable.  A mesh is placed only when it carries positions of exactly its
 * vertex count, triangle indices in whole triangles that all name one of its vertices, and, in an Edit
 * overlay, face flags that are empty or one per triangle; a disagreeing mesh is left out rather than guessed
 * at.  Pure, so the rule is testable without a device.
 *
 * @param DirectMeshOverlay direct The overlay to place.
 * @return DirectOverlayLayout The plan; empty when nothing pairs.
 */
internal fun planDirectOverlayLayout(direct: DirectMeshOverlay): DirectOverlayLayout {
	val placed = ArrayList<DirectOverlayPlacement>(direct.overlay.meshes.size)
	var totalVertexCount = 0
	for (mesh in direct.overlay.meshes) {
		val positions = direct.positionsById[mesh.drawableId] ?: continue
		val triangleIndices = direct.triangleIndicesById[mesh.drawableId] ?: continue
		if (positions.size != mesh.vertexCount * 2 || triangleIndices.size % 3 != 0) {
			continue
		}
		if (triangleIndices.any { index -> index !in 0 until mesh.vertexCount }) {
			continue
		}
		if (direct.overlay.kind == MeshOverlayKind.Edit && mesh.faceFlags.isNotEmpty() && mesh.faceFlags.size != triangleIndices.size / 3) {
			continue
		}
		placed.add(DirectOverlayPlacement(mesh, positions, triangleIndices, totalVertexCount))
		totalVertexCount += mesh.vertexCount
	}
	return DirectOverlayLayout(placed, totalVertexCount)
}