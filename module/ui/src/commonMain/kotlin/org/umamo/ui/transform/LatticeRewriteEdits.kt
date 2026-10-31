package org.umamo.ui.transform

import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionTarget
import org.umamo.edit.intValue
import org.umamo.edit.mesh.LatticeResizeParameterKeys
import org.umamo.edit.mesh.commitLatticeRewrite
import org.umamo.edit.mesh.latticeSizeParameters
import org.umamo.edit.mesh.latticeSplitParameters
import org.umamo.edit.mesh.reportLatticeRewrite
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel

/*
 * The session side of rewriting a warp's lattice at rest: a typed resolution, a subdivision, and the Quad toggle.
 * Each lowers through LatticeResize.kt and commits as one step; the two resizes register on the operation strip,
 * where a rerun resizes the step's base again.  None applies in Pose mode, which shapes the lattice at a pose and
 * would lose the shape it holds to a rewrite of every form.
 */

/**
 * Gives warp [warpId] a lattice of [rows] x [columns] cells as one undo step ([latticeResizeOf]), and registers the
 * resize on the operation strip in [areaId] with its Rows and Columns.  Refused in Pose mode; a size the warp already
 * has records nothing.
 *
 * @param DeformerId warpId The warp to resize.
 * @param Int rows The new cell count along v.
 * @param Int columns The new cell count along u.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.setLatticeSizeAdjustable(warpId: DeformerId, rows: Int, columns: Int, areaId: String?) {
	if (mode.value == EditorMode.Pose) {
		return
	}

	val rewrite = latticeResizeOf(model.value, mapOf(warpId to LatticeSize(rows, columns)))

	if (!commitLatticeRewrite(DeformerChange.ResizeLattice(listOf(warpId)), rewrite.model, listOf(warpId), rewrite.unconvertedCount, rewrite.approximate)) {
		return
	}

	registerAdjustableOperation(model.value, areaId, latticeSizeParameters(rows, columns)) { record ->
		val size =
			LatticeSize(
				record.parameters.intValue(LatticeResizeParameterKeys.ROWS, rows),
				record.parameters.intValue(LatticeResizeParameterKeys.COLUMNS, columns),
			)
		val rerun = latticeResizeOf(record.baseSnapshot.model, mapOf(warpId to size))

		if (amendLastCommit(record, rerun.model)) {
			reportLatticeRewrite(rerun.unconvertedCount, rerun.approximate)
		}
	}
}

/**
 * Splits every cell of [warpIds]' lattices by [rowSplits] x [columnSplits] as one undo step - a quad-mode warp keeps
 * its map exactly - and registers the subdivision on the operation strip in [areaId] with its Row and Column Splits.
 * A warp the split would take past the largest lattice is left alone.  Refused in Pose mode.
 *
 * @param List<DeformerId> warpIds The warps to subdivide.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 * @param Int rowSplits The factor every row of cells is split by.
 * @param Int columnSplits The factor every column of cells is split by.
 */
internal fun EditorSession.subdivideLatticesAdjustable(
	warpIds: List<DeformerId>,
	areaId: String?,
	rowSplits: Int = 2,
	columnSplits: Int = 2,
) {
	if (mode.value == EditorMode.Pose || warpIds.isEmpty()) {
		return
	}

	val rewrite = latticeResizeOf(model.value, subdividedSizes(model.value, warpIds, rowSplits, columnSplits))

	if (!commitLatticeRewrite(DeformerChange.SubdivideLattice(warpIds), rewrite.model, warpIds, rewrite.unconvertedCount, rewrite.approximate)) {
		return
	}

	registerAdjustableOperation(model.value, areaId, latticeSplitParameters(rowSplits, columnSplits)) { record ->
		val base = record.baseSnapshot.model
		val splitRows = record.parameters.intValue(LatticeResizeParameterKeys.ROW_SPLITS, rowSplits)
		val splitColumns = record.parameters.intValue(LatticeResizeParameterKeys.COLUMN_SPLITS, columnSplits)
		val rerun = latticeResizeOf(base, subdividedSizes(base, warpIds, splitRows, splitColumns))

		if (amendLastCommit(record, rerun.model)) {
			reportLatticeRewrite(rerun.unconvertedCount, rerun.approximate)
		}
	}
}

/**
 * Switches warp [warpId] to bilinear ([quad]) or triangle-split mapping as one undo step, with its children kept in
 * place ([quadToggledOf]).  Refused in Pose mode; the warp's own setting records nothing.
 *
 * @param DeformerId warpId The warp.
 * @param Boolean quad The new mapping: true for bilinear.
 */
internal fun EditorSession.setWarpQuadTransform(warpId: DeformerId, quad: Boolean) {
	if (mode.value == EditorMode.Pose) {
		return
	}

	val rewrite = quadToggledOf(model.value, warpId, quad)
	commitLatticeRewrite(DeformerChange.SetQuadTransform(warpId, quad), rewrite.model, emptyList(), rewrite.unconvertedCount, rewrite.approximate)
}

/**
 * The warps a Subdivide Lattice acts on: in Edit mode the lattices open for editing, in Object mode the selected
 * warps, and none in Pose mode.
 *
 * @return List<DeformerId> The warps, in selection order.
 */
internal fun EditorSession.subdivisionTargets(): List<DeformerId> {
	val current = model.value

	return when (mode.value) {
		EditorMode.Edit -> {
			meshSelection.value.deformerIds
		}

		EditorMode.Object -> {
			selection.value.targets.filterIsInstance<SelectionTarget.Deformer>().map { target -> target.id }.filter { deformerId -> current.deformerById[deformerId] is Deformer.Warp }
		}

		EditorMode.Pose -> {
			emptyList()
		}
	}
}

/**
 * Each warp's resolution after splitting its cells by [rowSplits] x [columnSplits].
 *
 * @param PuppetModel model The model the warps are in.
 * @param List<DeformerId> warpIds The warps.
 * @param Int rowSplits The factor along v.
 * @param Int columnSplits The factor along u.
 * @return Map<DeformerId, LatticeSize> Each warp's new size.
 */
private fun subdividedSizes(
	model: PuppetModel,
	warpIds: List<DeformerId>,
	rowSplits: Int,
	columnSplits: Int,
): Map<DeformerId, LatticeSize> =
	warpIds
		.mapNotNull { warpId -> (model.deformerById[warpId] as? Deformer.Warp)?.let { warp -> warpId to LatticeSize(warp.rows * rowSplits, warp.columns * columnSplits) } }
		.toMap()