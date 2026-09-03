package org.umamo.ui.viewport

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterUnit
import org.umamo.edit.floatValue
import org.umamo.edit.intValue
import org.umamo.edit.withAtlasPlacements
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import kotlin.math.PI

/*
 * The placement gesture's face on the operation settings strip: the numbers a Grab / Rotate / Scale
 * committed as the strip's typed rows, the gesture parameters those rows mean, and the registration
 * whose rerun re-evaluates the SAME frozen gesture (movers, bystanders, pivots, the page's paint)
 * from the base snapshot and lands over the gesture's own history step.
 *
 * There is no axis-constraint row.  A lock only zeroes one Grab component or pins one Scale factor
 * at 1, so the per-component rows already say everything the lock said, and the evaluation reads a
 * non-uniform Scale as the tile-axis scale a locked gesture produces.  The rows are built from the
 * drag's own readout, so the strip shows exactly the numbers the HUD showed at confirm.
 */

/** The parameter keys the placement gesture's rows carry (the strip maps them to labels). */
internal object PlacementParameterKeys {
	const val DELTA_X = "placement.deltaX"
	const val DELTA_Y = "placement.deltaY"
	const val ANGLE = "placement.angle"
	const val SCALE_X = "placement.scaleX"
	const val SCALE_Y = "placement.scaleY"
}

/** The widest angle the Angle row accepts, in degrees either way. */
private const val PLACEMENT_ANGLE_LIMIT = 360f

/** The smallest factor a Scale row accepts - a placement with a zero scale would not invert. */
private const val PLACEMENT_SCALE_MIN = 0.01f

/** The largest factor a Scale row accepts. */
private const val PLACEMENT_SCALE_MAX = 100f

/** The Scale rows' scrub step. */
private const val PLACEMENT_SCALE_STEP = 0.01f

/**
 * The strip's rows for the gesture [status] describes: a Grab's move in whole page pixels (y down,
 * as the HUD reads it), a Rotate's page-space angle, a Scale's two factors.
 *
 * @param PlacementDragStatus status     The drag's readout at confirm.
 * @param Int                 pageWidth  The page width, bounding the horizontal move.
 * @param Int                 pageHeight The page height, bounding the vertical move.
 * @return List The rows, empty for an operator that has none (a slide never reaches a placement).
 */
internal fun placementParameters(status: PlacementDragStatus, pageWidth: Int, pageHeight: Int): List<OperatorParameter> =
	when (status.operatorKind) {
		MeshOperatorKind.Grab ->
			listOf(
				OperatorParameter.IntParameter(PlacementParameterKeys.DELTA_X, PlacementParameterKeys.DELTA_X, status.deltaX, -pageWidth, pageWidth, unit = ParameterUnit.Pixels),
				OperatorParameter.IntParameter(PlacementParameterKeys.DELTA_Y, PlacementParameterKeys.DELTA_Y, status.deltaY, -pageHeight, pageHeight, unit = ParameterUnit.Pixels),
			)

		MeshOperatorKind.Rotate ->
			listOf(
				OperatorParameter.FloatParameter(
					PlacementParameterKeys.ANGLE,
					PlacementParameterKeys.ANGLE,
					status.angleDegrees,
					-PLACEMENT_ANGLE_LIMIT,
					PLACEMENT_ANGLE_LIMIT,
					unit = ParameterUnit.Degrees,
				),
			)

		MeshOperatorKind.Scale ->
			listOf(
				OperatorParameter.FloatParameter(PlacementParameterKeys.SCALE_X, PlacementParameterKeys.SCALE_X, status.factorX, PLACEMENT_SCALE_MIN, PLACEMENT_SCALE_MAX, PLACEMENT_SCALE_STEP),
				OperatorParameter.FloatParameter(PlacementParameterKeys.SCALE_Y, PlacementParameterKeys.SCALE_Y, status.factorY, PLACEMENT_SCALE_MIN, PLACEMENT_SCALE_MAX, PLACEMENT_SCALE_STEP),
			)

		MeshOperatorKind.VertexSlide -> emptyList()
	}

/**
 * The gesture parameters the strip's rows mean - the inverse of [placementParameters], back into
 * the display-space form [evaluatePlacementDrag] takes: the move's y flips (display y runs up), and
 * the angle's sign flips with it (the flip mirrors a rotation's sense).
 *
 * @param MeshOperatorKind kind       The operator the rows belong to.
 * @param List             parameters The rows, possibly edited.
 * @return PlacementGestureParameters The parameters to evaluate.
 */
internal fun placementGestureParametersOf(kind: MeshOperatorKind, parameters: List<OperatorParameter>): PlacementGestureParameters =
	when (kind) {
		MeshOperatorKind.Grab ->
			PlacementGestureParameters(
				deltaDisplayX = parameters.intValue(PlacementParameterKeys.DELTA_X, 0).toFloat(),
				deltaDisplayY = -parameters.intValue(PlacementParameterKeys.DELTA_Y, 0).toFloat(),
				factorX = 1f,
				factorY = 1f,
				rotationRadians = 0f,
			)

		MeshOperatorKind.Rotate ->
			PlacementGestureParameters(
				deltaDisplayX = 0f,
				deltaDisplayY = 0f,
				factorX = 1f,
				factorY = 1f,
				rotationRadians = (-parameters.floatValue(PlacementParameterKeys.ANGLE, 0f) * PI / 180.0).toFloat(),
			)

		MeshOperatorKind.Scale ->
			PlacementGestureParameters(
				deltaDisplayX = 0f,
				deltaDisplayY = 0f,
				factorX = parameters.floatValue(PlacementParameterKeys.SCALE_X, 1f),
				factorY = parameters.floatValue(PlacementParameterKeys.SCALE_Y, 1f),
				rotationRadians = 0f,
			)

		MeshOperatorKind.VertexSlide -> PlacementGestureParameters(0f, 0f, 1f, 1f, 0f)
	}

/**
 * The placements a gesture's evaluation actually changed: every mover whose placement under
 * [result] differs from where it sat at latch - what the commit writes, and what a rerun re-writes.
 *
 * @param List                movers The gesture's movers.
 * @param PlacementDragResult result The evaluation.
 * @return Map Each changed tile's new placement, in mover order.
 */
internal fun changedPlacements(movers: List<PlacementMover>, result: PlacementDragResult): Map<AtlasTileId, AtlasPlacement?> {
	val changed = LinkedHashMap<AtlasTileId, AtlasPlacement?>()
	for (mover in movers) {
		val next = result.placementByTile[mover.tileId] ?: continue
		if (next != mover.placement) {
			changed[mover.tileId] = next
		}
	}
	return changed
}

/**
 * Registers the placement gesture that just committed [result] as the session's adjustable
 * operation.  Call it right after the commit.  An adjustment re-evaluates the retained [gesture]
 * under the edited rows - the same movers, bystanders, pivots, and page paint the drag ran over, so
 * the collision warnings stay honest - lands the placements over the gesture's own step from the
 * record's base, and hands the new evaluation to [onLanded] (the overlay's ghosts and notices).
 * Synchronous: an evaluation is the per-pointer-frame cost.
 *
 * @param EditorSession       session  The session the gesture committed into.
 * @param String              areaId   The UV editor the gesture ran in (where the strip shows).
 * @param PlacementGesture    gesture  The frozen gesture.
 * @param PlacementDragResult result   The evaluation the commit wrote.
 * @param Function            onLanded Receives each adjustment's evaluation once it has landed.
 * @return AdjustableOperation? The record, or null when the session refused the registration (no
 *   commit was pushed) or the operator has no rows.
 */
internal fun registerPlacementAdjustment(
	session: EditorSession,
	areaId: String,
	gesture: PlacementGesture,
	result: PlacementDragResult,
	onLanded: (PlacementDragResult) -> Unit,
): AdjustableOperation? {
	val kind = gesture.transform.operatorKind
	val parameters = placementParameters(result.status, gesture.pageWidth, gesture.pageHeight)
	if (parameters.isEmpty()) {
		return null
	}
	return session.registerAdjustableOperation(session.model.value, areaId, parameters) { record ->
		val adjusted =
			evaluatePlacementDrag(
				operatorKind = kind,
				parameters = placementGestureParametersOf(kind, record.parameters),
				movers = gesture.movers,
				bystanders = gesture.bystanders,
				occupancy = gesture.occupancy,
				pageWidth = gesture.pageWidth,
				pageHeight = gesture.pageHeight,
				extrude = gesture.extrude,
			)
		val landed = record.baseSnapshot.model.withAtlasPlacements(changedPlacements(gesture.movers, adjusted))
		if (session.amendLastCommit(record, landed)) {
			onLanded(adjusted)
		}
	}
}