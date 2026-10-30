package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshStyle
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.latticeLayout
import org.umamo.ui.viewport.gizmo.NO_OVERLAY_FLAGS
import org.umamo.ui.viewport.gizmo.editCageFlagsOf

/*
 * A lattice's Edit cage as geometry: its points, grid lines, and cells, the two triangles each cell fills as, and the
 * face-dot anchors - one vertex appended per cell past the lattice's own points, at the cell's center - that put a
 * quad cell's one dot where its center is.
 */

/**
 * One lattice's cage topology.
 *
 * @property Int rows The lattice's cell count along v.
 * @property Int columns The lattice's cell count along u.
 * @property ElementTopology elements Its points, grid lines, and cells.
 * @property IntArray triangleIndices Two triangles per cell, cell by cell.
 * @property IntArray faceDotCorners Three anchors per triangle, each the cell's center vertex.
 */
internal class CageTopology(
	val rows: Int,
	val columns: Int,
	val elements: ElementTopology,
	val triangleIndices: IntArray,
	val faceDotCorners: IntArray,
) {
	/** The lattice's own points, which draw vertex dots. */
	val pointCount: Int get() = elements.vertexCount

	/** The points and the appended cell centers. */
	val vertexCount: Int get() = elements.vertexCount + elements.faceCount
}

/**
 * [warp]'s cage topology at its grid size.
 *
 * @param Deformer.Warp warp The warp.
 * @return CageTopology The topology.
 */
internal fun cageTopologyOf(warp: Deformer.Warp): CageTopology {
	val layout = warp.latticeLayout
	val faceDotCorners = IntArray(layout.cellCount * 6)

	for (cellIndex in 0 until layout.cellCount) {
		faceDotCorners.fill(layout.pointCount + cellIndex, cellIndex * 6, cellIndex * 6 + 6)
	}

	return CageTopology(warp.rows, warp.columns, ElementTopology.ofLattice(layout), layout.cellTriangles, faceDotCorners)
}

/**
 * A lattice's world points with each cell's center appended ([ElementTopology.faceCenterInto]).
 *
 * @param FloatArray world The world points, interleaved x, z.
 * @param CageTopology topology The lattice's topology.
 * @return FloatArray The cage positions.
 */
internal fun cagePositionsOf(world: FloatArray, topology: CageTopology): FloatArray {
	val cage = world.copyOf(topology.vertexCount * 2)

	for (cellIndex in 0 until topology.elements.faceCount) {
		topology.elements.faceCenterInto(world, cellIndex, cage, (topology.pointCount + cellIndex) * 2)
	}

	return cage
}

/**
 * One lattice's cage entry: its points' and grid lines' flags as the mesh derive lights them, the appended centers
 * left idle and drawing no vertex dot, and each cell's flag on both of its triangles, the active cell's dot read
 * through its first.
 *
 * @param ElementOwner owner The lattice's owner.
 * @param CageTopology topology Its topology.
 * @param Set<MeshElement> elements Its selected elements.
 * @param MeshElement? active Its active element, or null.
 * @param MeshSelectMode selectMode The select mode.
 * @param MeshStyle style Its style: marked pending while its warp holds an unkeyed Pose-mode shape.
 * @return MeshOverlayMesh The entry.
 */
internal fun cageEntryOf(
	owner: ElementOwner,
	topology: CageTopology,
	elements: Set<MeshElement>,
	active: MeshElement?,
	selectMode: MeshSelectMode,
	style: MeshStyle,
): MeshOverlayMesh {
	val edgeEndpoints = topology.elements.edgeEndpoints
	val flags =
		editCageFlagsOf(topology.elements, elements, active, selectMode)
			?: return MeshOverlayMesh(
				owner,
				topology.vertexCount,
				edgeEndpoints,
				NO_OVERLAY_FLAGS,
				NO_OVERLAY_FLAGS,
				NO_OVERLAY_FLAGS,
				null,
				null,
				null,
				style = style,
				faceDotCorners = topology.faceDotCorners,
				vertexDotCount = topology.pointCount,
			)
	val triangleFlags = ByteArray(flags.faceFlags.size * 2) { triangleIndex -> flags.faceFlags[triangleIndex / 2] }

	return MeshOverlayMesh(
		owner,
		topology.vertexCount,
		edgeEndpoints,
		flags.vertexFlags.copyOf(topology.vertexCount),
		flags.edgeFlags,
		triangleFlags,
		flags.activeVertex,
		flags.activeEdge,
		flags.activeFace?.let { cellIndex -> cellIndex * 2 },
		style = style,
		faceDotCorners = topology.faceDotCorners,
		vertexDotCount = topology.pointCount,
	)
}