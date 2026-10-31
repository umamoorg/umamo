package org.umamo.edit.structure

import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterUnit
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.mesh.LATTICE_DIVISION_MAX
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel

/*
 * The commit side of Create Warp Deformer, and its face on the operation settings strip.  Building the warp - the
 * selection's bounds mapped into its parent's space, the wrapped items kept in place under it - needs the evaluator,
 * which :edit cannot see, so the caller computes the created model and this records it.
 */

/** The cells along each axis a warp is born with. */
const val WARP_BIRTH_DIVISIONS = 5

/** The margin, in percent of the selection's larger side, a warp is born with on every edge. */
const val WARP_BIRTH_MARGIN_PERCENT = 10f

/** The parameter keys Create Warp Deformer's rows carry. */
object WarpCreationParameterKeys {
	/** The Rows row: the new warp's cells along v. */
	const val ROWS = "createWarp.rows"

	/** The Columns row: its cells along u. */
	const val COLUMNS = "createWarp.columns"

	/** The Margin row: the space around the selection, in percent of its larger side. */
	const val MARGIN = "createWarp.margin"

	/** The Bilinear row: whether the new warp maps bilinearly (quad) rather than by triangle split. */
	const val QUAD = "createWarp.quad"
}

/**
 * The strip's rows for a warp created at [rows] x [columns] cells with [marginPercent] around the selection: the two
 * counts ratio-paired, the margin, and, when the runtime target allows a choice, the bilinear mapping.
 *
 * @param Int rows The cells along v.
 * @param Int columns The cells along u.
 * @param Float marginPercent The margin in percent of the selection's larger side.
 * @param Boolean? quad Whether it maps bilinearly, or null when the target offers no choice (the row is left out).
 * @return List The rows.
 */
fun warpCreationParameters(rows: Int, columns: Int, marginPercent: Float, quad: Boolean?): List<OperatorParameter> =
	listOfNotNull(
		OperatorParameter.IntParameter(WarpCreationParameterKeys.ROWS, WarpCreationParameterKeys.ROWS, rows, 1, LATTICE_DIVISION_MAX, ratioPartnerKey = WarpCreationParameterKeys.COLUMNS),
		OperatorParameter.IntParameter(WarpCreationParameterKeys.COLUMNS, WarpCreationParameterKeys.COLUMNS, columns, 1, LATTICE_DIVISION_MAX),
		OperatorParameter.FloatParameter(WarpCreationParameterKeys.MARGIN, WarpCreationParameterKeys.MARGIN, marginPercent, 0f, 100f, 1f, ParameterUnit.Percent),
		quad?.let { value -> OperatorParameter.BooleanParameter(WarpCreationParameterKeys.QUAD, WarpCreationParameterKeys.QUAD, value) },
	)

/**
 * Commits a created warp as ONE undo step with the warp alone selected and active, as a new object becomes the
 * selection.  A model that changed nothing records nothing.
 *
 * @param DeformerChange.Create change The edit descriptor.
 * @param PuppetModel created The model with the warp created and the wrapped items kept in place under it.
 * @param DeformerId warpId The new warp.
 * @return Boolean True when a step was recorded.
 */
fun EditorSession.commitWarpCreation(change: DeformerChange.Create, created: PuppetModel, warpId: DeformerId): Boolean {
	if (created === model.value) {
		return false
	}

	val warp = SelectionTarget.Deformer(warpId)
	commitStep(change, model = created, selection = Selection(setOf(warp), warp))

	return true
}