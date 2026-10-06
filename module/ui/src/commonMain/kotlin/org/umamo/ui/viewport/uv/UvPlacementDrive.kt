package org.umamo.ui.viewport.uv

import org.umamo.edit.MeshOperatorKind
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.applyUvAffine

/*
 * The placement drive's compute: the frozen gesture evaluated into a placement per mover and a display
 * affine per moving island, and each island's frozen display positions through its tile's affine.  Pure over
 * its request (the gesture's frozen fields are never written after the capture builds), so the drive worker
 * runs it off the UI thread.
 */

/**
 * One placement drive's request, resolved on the UI thread: resolving the parameters
 * advances the rotation tracker, which must see every pointer event.
 *
 * @property MeshOperatorKind operator The operator.
 * @property PlacementGestureParameters parameters The display-space parameters the pointer frame yielded.
 * @property PlacementGesture gesture The frozen capture.
 */
internal class PlacementDriveRequest(
	val operator: MeshOperatorKind,
	val parameters: PlacementGestureParameters,
	val gesture: PlacementGesture,
)

/**
 * One placement drive's result.
 *
 * @property PlacementDragResult evaluation The placements, affines, readout, and collisions.
 * @property Map<DrawableId, FloatArray> preview Each moving island's display positions.
 */
internal class PlacementDriveResult(
	val evaluation: PlacementDragResult,
	val preview: Map<DrawableId, FloatArray>,
)

/**
 * The drive: the gesture evaluated under the request's parameters, and each moving island carried by its
 * tile's display affine.
 *
 * @param PlacementDriveRequest request The request.
 * @return PlacementDriveResult The result.
 */
internal fun computePlacementDrive(request: PlacementDriveRequest): PlacementDriveResult {
	val gestureData = request.gesture
	val evaluation =
		evaluatePlacementDrag(
			operatorKind = request.operator,
			parameters = request.parameters,
			movers = gestureData.movers,
			bystanders = gestureData.bystanders,
			occupancy = gestureData.occupancy,
			pageWidth = gestureData.pageWidth,
			pageHeight = gestureData.pageHeight,
			extrude = gestureData.extrude,
		)

	val preview = LinkedHashMap<DrawableId, FloatArray>()

	for ((drawableId, frozen) in gestureData.frozenPositionsByDrawable) {
		val tileId = gestureData.tileByDrawable[drawableId] ?: continue
		val affine = evaluation.displayAffineByTile[tileId] ?: continue
		preview[drawableId] = applyUvAffine(frozen, affine)
	}

	return PlacementDriveResult(evaluation, preview)
}