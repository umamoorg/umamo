package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectMode
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshStyle
import org.umamo.render.puppet.OVERLAY_FLAG_BACK
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.LatticeBackFacing
import org.umamo.runtime.model.WarpLatticeLayout
import org.umamo.runtime.model.latticeLayout
import org.umamo.ui.viewport.gizmo.EditCageFlags
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
 * @property WarpLatticeLayout layout The lattice's layout, which its back-facing parts are measured in.
 */
internal class CageTopology(
	val rows: Int,
	val columns: Int,
	val elements: ElementTopology,
	val triangleIndices: IntArray,
	val faceDotCorners: IntArray,
	val layout: WarpLatticeLayout = WarpLatticeLayout(rows, columns),
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

	return CageTopology(warp.rows, warp.columns, ElementTopology.ofLattice(layout), layout.cellTriangles, faceDotCorners, layout)
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
 * through its first.  Each back-facing point, grid line, and cell, and each back-facing cell's center, carries the
 * back-facing bit beside its role.
 *
 * @param ElementOwner owner The lattice's owner.
 * @param CageTopology topology Its topology.
 * @param Set<MeshElement> elements Its selected elements.
 * @param MeshElement? active Its active element, or null.
 * @param MeshSelectMode selectMode The select mode.
 * @param MeshStyle style Its style: marked pending while its warp holds an unkeyed Pose-mode shape.
 * @param LatticeBackFacing? backFacing The lattice's back-facing parts, or null for none.
 * @return MeshOverlayMesh The entry.
 */
internal fun cageEntryOf(
	owner: ElementOwner,
	topology: CageTopology,
	elements: Set<MeshElement>,
	active: MeshElement?,
	selectMode: MeshSelectMode,
	style: MeshStyle,
	backFacing: LatticeBackFacing? = null,
): MeshOverlayMesh {
	val edgeEndpoints = topology.elements.edgeEndpoints
	val selectionFlags = editCageFlagsOf(topology.elements, elements, active, selectMode)
	val flags =
		when {
			backFacing != null -> {
				val idle = EditCageFlags(ByteArray(topology.pointCount), ByteArray(edgeEndpoints.size / 2), ByteArray(topology.elements.faceCount), null, null, null)
				withBackFacing(selectionFlags ?: idle, topology, backFacing)
			}

			selectionFlags != null -> {
				selectionFlags
			}

			else -> {
				return MeshOverlayMesh(
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
			}
		}
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

/**
 * [flags] with the back-facing bit ORed onto every back-facing part: the points, each cell's center past them, the
 * grid lines, and the cells.
 *
 * @param EditCageFlags flags The selection's flags, one per point, edge, and cell.
 * @param CageTopology topology The lattice's topology.
 * @param LatticeBackFacing backFacing Its back-facing parts.
 * @return EditCageFlags The marked flags, the points' grown to the cage's vertices.
 */
private fun withBackFacing(flags: EditCageFlags, topology: CageTopology, backFacing: LatticeBackFacing): EditCageFlags {
	val back = OVERLAY_FLAG_BACK.toInt()
	val vertexFlags = flags.vertexFlags.copyOf(topology.vertexCount)

	for (pointIndex in 0 until topology.pointCount) {
		if (backFacing.points[pointIndex]) {
			vertexFlags[pointIndex] = (vertexFlags[pointIndex].toInt() or back).toByte()
		}
	}

	val faceFlags = flags.faceFlags.copyOf()

	for (cellIndex in faceFlags.indices) {
		if (backFacing.cells[cellIndex]) {
			faceFlags[cellIndex] = (faceFlags[cellIndex].toInt() or back).toByte()
			vertexFlags[topology.pointCount + cellIndex] = (vertexFlags[topology.pointCount + cellIndex].toInt() or back).toByte()
		}
	}

	val backEdges = backFacing.edgesIn(topology.layout, topology.elements)
	val edgeFlags = flags.edgeFlags.copyOf()

	for (edgeIndex in edgeFlags.indices) {
		if (backEdges[edgeIndex]) {
			edgeFlags[edgeIndex] = (edgeFlags[edgeIndex].toInt() or back).toByte()
		}
	}

	return EditCageFlags(vertexFlags, edgeFlags, faceFlags, flags.activeVertex, flags.activeEdge, flags.activeFace)
}

/**
 * Which of [elements]' edges face away, in its edge order: the topology orders a lattice's grid lines its own way, so
 * each is found in [layout] by its endpoints.
 *
 * @param WarpLatticeLayout layout The lattice's layout, which these back-facing parts are in.
 * @param ElementTopology elements The lattice's element topology.
 * @return BooleanArray One per topology edge.
 */
internal fun LatticeBackFacing.edgesIn(layout: WarpLatticeLayout, elements: ElementTopology): BooleanArray {
	val edgeEndpoints = elements.edgeEndpoints

	return BooleanArray(edgeEndpoints.size / 2) { edgeIndex ->
		layout.edgeOrdinal(edgeEndpoints[edgeIndex * 2], edgeEndpoints[edgeIndex * 2 + 1])?.let { ordinal -> edges[ordinal] } ?: false
	}
}