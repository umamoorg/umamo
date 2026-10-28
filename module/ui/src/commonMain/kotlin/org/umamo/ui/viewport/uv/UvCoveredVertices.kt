package org.umamo.ui.viewport.uv

import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry

/*
 * The texture coordinates an Edit-mode selection covers on a UV surface and where their median sits: what the UV
 * editor's Shift+S snaps from and what the sidebar's UV Vertex / Median panel shows and moves, so the two cannot
 * disagree.  Display space throughout: texels of the shown surface, y up.
 */

/**
 * One shown mesh's covered vertices with the display-space geometry they were read from.
 *
 * @property GizmoMeshGeometry geometry The mesh's display-space geometry.
 * @property Set<Int> indices The covered vertices, every one inside [geometry].
 */
internal class UvCoveredVertices(val geometry: GizmoMeshGeometry, val indices: Set<Int>)

/**
 * The mean display position of a set of covered vertices.
 *
 * @property Float displayX The median's display x in texels.
 * @property Float displayY The median's display y in texels, y up.
 * @property Int vertexCount How many vertices it is the mean of.
 */
internal class UvDisplayMedian(val displayX: Float, val displayY: Float, val vertexCount: Int)

/**
 * The covered vertices of every shown mesh, dropping a mesh that has none, and any index past a mesh's vertices
 * (see MeshTopology.coveredVertexIndicesWithin).
 *
 * @param MeshSelection selection The mesh selection.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' display-space geometry.
 * @return List<UvCoveredVertices> The meshes with covered vertices, in [geometries] order.
 */
internal fun uvCoveredVertices(selection: MeshSelection, geometries: List<GizmoMeshGeometry>): List<UvCoveredVertices> =
	geometries.mapNotNull { geometry ->
		val elements = selection.elementsOf(geometry.drawableId)

		if (elements.isEmpty()) {
			return@mapNotNull null
		}

		val covered = MeshTopology.coveredVertexIndicesWithin(elements, geometry.indices, geometry.positions.size / 2)

		if (covered.isEmpty()) null else UvCoveredVertices(geometry, covered)
	}

/**
 * The mean display position of every covered vertex across the shown meshes.
 *
 * @param List<UvCoveredVertices> covered The covered vertices.
 * @return UvDisplayMedian? The median, or null when nothing is covered.
 */
internal fun uvCoveredMedian(covered: List<UvCoveredVertices>): UvDisplayMedian? {
	var sumX = 0f
	var sumY = 0f
	var count = 0

	for (mesh in covered) {
		val positions = mesh.geometry.positions

		for (vertexIndex in mesh.indices) {
			sumX += positions[vertexIndex * 2]
			sumY += positions[vertexIndex * 2 + 1]
			count++
		}
	}

	if (count == 0) {
		return null
	}

	return UvDisplayMedian(sumX / count, sumY / count, count)
}