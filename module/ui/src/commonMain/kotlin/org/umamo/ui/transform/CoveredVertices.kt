package org.umamo.ui.transform

import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.runtime.model.DrawableId

/*
 * The vertices an Edit-mode selection covers and where their median sits in world space: what Shift+S snaps
 * from and what the sidebar's Vertex / Median panel shows and moves.  Written over positions and indices rather
 * than meshes, so other element kinds with a world mapping can share it.
 */

/**
 * One mesh's covered vertices: the vertices its selected elements span, with the world geometry they were
 * captured in.
 *
 * @property DrawableWorldGeometry geometry The mesh's captured geometry.
 * @property IntArray triangleIndices The mesh's triangle vertex indices (connectivity for a capture built over it).
 * @property Set<Int> indices The covered vertices, every one inside [geometry].
 */
internal class CoveredVertices(
	val geometry: DrawableWorldGeometry,
	val triangleIndices: IntArray,
	val indices: Set<Int>,
)

/**
 * The mean world position of a set of covered vertices.
 *
 * @property Float worldX The median's world x.
 * @property Float worldZ The median's world z (up).
 * @property Int vertexCount How many vertices it is the mean of.
 */
internal class WorldMedian(val worldX: Float, val worldZ: Float, val vertexCount: Int)

/**
 * The covered vertices of every mesh in [geometries], dropping a mesh that has none.
 *
 * An index at or past the captured vertex count is dropped too: the selection and the model can reach a reader
 * through separate collectors, so for a moment after a duplicate or a rip the selection may name vertices the
 * captured geometry does not have.  A set needing no such drop is kept as the topology returned it, so a sum over
 * it runs in the topology's order, the one a modal transform's capture sums the same vertices in.
 *
 * @param MeshSelection selection The mesh selection.
 * @param List<DrawableWorldGeometry> geometries The meshes' captured geometry.
 * @param Map<DrawableId, IntArray> triangleIndicesById Each mesh's triangle indices, as captured with its geometry.
 * @return List<CoveredVertices> The meshes with covered vertices, in [geometries] order.
 */
internal fun coveredVerticesOf(
	selection: MeshSelection,
	geometries: List<DrawableWorldGeometry>,
	triangleIndicesById: Map<DrawableId, IntArray>,
): List<CoveredVertices> =
	geometries.mapNotNull { geometry ->
		val elements = selection.elementsOf(geometry.drawableId)
		val triangleIndices = triangleIndicesById[geometry.drawableId]

		if (elements.isEmpty() || triangleIndices == null) {
			return@mapNotNull null
		}

		val vertexCount = geometry.world.size / 2
		val covered = MeshTopology.coveredVertexIndices(elements, triangleIndices)
		val inside =
			if (covered.all { vertexIndex -> vertexIndex < vertexCount }) {
				covered
			} else {
				covered.filterTo(HashSet()) { vertexIndex -> vertexIndex < vertexCount }
			}

		if (inside.isEmpty()) null else CoveredVertices(geometry, triangleIndices, inside)
	}

/**
 * The mean of every covered vertex's captured world position, across all meshes: the median Grab pivots about
 * and Shift+S snaps from.
 *
 * @param List<CoveredVertices> covered The covered vertices.
 * @return WorldMedian? The median, or null when nothing is covered.
 */
internal fun coveredMedian(covered: List<CoveredVertices>): WorldMedian? = coveredMedianIn(covered, emptyMap())

/**
 * The median as a gesture's preview has the covered vertices: each mesh's previewed base, carried onto the
 * captured displayed shape and through the captured deformer mapping.  Valid while the deformer chain holds
 * still, which Edit mode's pinned pose guarantees, and cheap, because only the covered vertices are mapped.  A
 * mesh the preview does not move, or one whose vertex count it changed, counts at its captured world position.
 *
 * @param List<CoveredVertices> covered The covered vertices, as captured from the committed model.
 * @param Map<DrawableId, MeshRestPositions> restByDrawable The preview's new rest shape per moved mesh.
 * @return WorldMedian? The median, or null when nothing is covered.
 */
internal fun coveredMedianIn(covered: List<CoveredVertices>, restByDrawable: Map<DrawableId, MeshRestPositions>): WorldMedian? {
	var sumX = 0f
	var sumZ = 0f
	var count = 0

	for (mesh in covered) {
		val geometry = mesh.geometry
		val previewBase = restByDrawable[geometry.drawableId]?.localPositions?.takeIf { base -> base.size == geometry.local.size }

		if (previewBase == null) {
			for (vertexIndex in mesh.indices) {
				sumX += geometry.world[vertexIndex * 2]
				sumZ += geometry.world[vertexIndex * 2 + 1]
			}
		} else {
			val packed = previewedWorld(geometry, previewBase, mesh.indices)
			var slotIndex = 0

			while (slotIndex + 1 < packed.size) {
				sumX += packed[slotIndex]
				sumZ += packed[slotIndex + 1]
				slotIndex += 2
			}
		}

		count += mesh.indices.size
	}

	if (count == 0) {
		return null
	}

	return WorldMedian(sumX / count, sumZ / count, count)
}

/**
 * The world positions of [indices] with the base moved to [previewBase], packed in [indices] order.  A grid-less
 * mesh displays its base as it is; a keyformed one displays the base plus the neutral blend, which a positions-only
 * edit leaves unchanged, so the displayed shape moves by exactly what the base moved.
 *
 * @param DrawableWorldGeometry geometry The mesh's captured geometry.
 * @param FloatArray previewBase The previewed base, the same size as the captured one.
 * @param Set<Int> indices The vertices to map.
 * @return FloatArray Their world positions, two floats per index in iteration order.
 */
private fun previewedWorld(geometry: DrawableWorldGeometry, previewBase: FloatArray, indices: Set<Int>): FloatArray {
	val displayed = FloatArray(indices.size * 2)
	val gridless = geometry.displayed === geometry.local
	var slotIndex = 0

	for (vertexIndex in indices) {
		val xIndex = vertexIndex * 2

		if (gridless) {
			displayed[slotIndex] = previewBase[xIndex]
			displayed[slotIndex + 1] = previewBase[xIndex + 1]
		} else {
			displayed[slotIndex] = geometry.displayed[xIndex] + (previewBase[xIndex] - geometry.local[xIndex])
			displayed[slotIndex + 1] = geometry.displayed[xIndex + 1] + (previewBase[xIndex + 1] - geometry.local[xIndex + 1])
		}

		slotIndex += 2
	}

	return geometry.mapping.localToWorld(displayed)
}