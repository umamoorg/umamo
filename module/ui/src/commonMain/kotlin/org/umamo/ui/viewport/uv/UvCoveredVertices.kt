package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.transform.IndividualOriginScope
import org.umamo.edit.transform.ModalCaptureSource
import org.umamo.edit.transform.UvDisplayMedian
import org.umamo.edit.transform.UvEditFrame
import org.umamo.edit.transform.UvMedianMove
import org.umamo.edit.transform.buildModalTransformCapture
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry

/*
 * The texture coordinates an Edit-mode selection covers on a UV surface and where their median sits: what the UV
 * editor's Shift+S snaps from and what the sidebar's UV Vertex / Median panel shows and moves, so the two cannot
 * disagree, and the meshes a UV transform's capture is offered.  Display space throughout: texels of the shown
 * surface, y up.
 */

/**
 * One shown mesh's covered vertices with the display-space geometry they were read from.
 *
 * @property GizmoMeshGeometry geometry The mesh's display-space geometry.
 * @property Set<Int> indices The covered vertices, every one inside [geometry].
 */
internal class UvCoveredVertices(val geometry: GizmoMeshGeometry, val indices: Set<Int>)

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
		val elements = selection.elementsOf(geometry.owner)

		if (elements.isEmpty()) {
			return@mapNotNull null
		}

		val covered = MeshTopology.coveredVertexIndicesWithin(elements, geometry.topology, geometry.positions.size / 2)

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

/**
 * Every shown mesh in the edit as a UV transform's capture source, its display coordinates copied: one with the
 * vertices [coveredIndicesOf] names as covered, or with none as a bystander, which only a straight-line proportional
 * halo can move.  The UV editor's own G / S / R and a typed median move both offer their meshes through here, so a
 * strip adjustment of either reaches the same meshes.
 *
 * @param MeshSelection selection The mesh selection, whose owners are the edit.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' display-space geometry.
 * @param Function coveredIndicesOf The covered vertices of one shown mesh, empty for none.
 * @return List<ModalCaptureSource> The sources, in [geometries] order.
 */
internal fun uvCaptureSources(
	selection: MeshSelection,
	geometries: List<GizmoMeshGeometry>,
	coveredIndicesOf: (GizmoMeshGeometry) -> Set<Int>,
): List<ModalCaptureSource> =
	geometries.mapNotNull { geometry ->
		val coveredIndices = coveredIndicesOf(geometry)

		if (coveredIndices.isEmpty() && geometry.owner !in selection.owners) null else ModalCaptureSource(geometry.owner, geometry.positions.copyOf(), geometry.topology, coveredIndices)
	}

/**
 * Captures the coordinates [selection] covers across the shown meshes for a move of their median.  The pivot is the
 * median point with no anchors, since a Grab ignores the pivot, and every shown mesh is offered the way the UV
 * editor's own Grab offers them ([uvCaptureSources]), so proportional editing turned on from the strip afterward
 * reaches the same meshes that Grab's would.
 *
 * @param MeshSelection selection The mesh selection.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' display geometry, read from the COMMITTED coordinates.
 * @param UvEditFrame frame The surface's authoring frame.
 * @param MutableState<Float?> radiusState The surface's proportional radius, which the move reads when it registers on
 *   the strip and an adjusted strip row writes back.
 * @return UvMedianMove? The move, or null when nothing is covered.
 */
internal fun uvMedianMove(selection: MeshSelection, geometries: List<GizmoMeshGeometry>, frame: UvEditFrame, radiusState: MutableState<Float?>): UvMedianMove? {
	val covered = uvCoveredVertices(selection, geometries)
	val median = uvCoveredMedian(covered) ?: return null
	val coveredByOwner = covered.associate { mesh -> mesh.geometry.owner to mesh.indices }
	val sources = uvCaptureSources(selection, geometries) { geometry -> coveredByOwner[geometry.owner].orEmpty() }
	val transform =
		buildModalTransformCapture(
			sources = sources,
			pivotMode = TransformPivotMode.MedianPoint,
			individualOriginScope = IndividualOriginScope.ConnectivityIsland,
			operatorKind = MeshOperatorKind.Grab,
			activeAnchor = null,
			cursorAnchor = null,
		) ?: return null

	return UvMedianMove(transform, frame, median, radiusDisplay = { resolvedUvProportionalRadius(radiusState, frame) }) { radius -> radiusState.value = radius }
}