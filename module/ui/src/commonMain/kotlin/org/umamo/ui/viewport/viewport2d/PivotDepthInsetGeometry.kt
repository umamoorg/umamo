package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

// The perspective's strength: the far edge of the floor draws at 1 / (1 + this) of the near edge's size.
private const val FLOOR_RECESSION = 3f

// Where the horizon sits, as a fraction of the inset's height from its top.
private const val HORIZON_FRACTION = 0.12f

// The transverse grid lines a framing holds at most: a deeper pivot doubles the spacing.
private const val TRANSVERSE_LINES = 8

// How far down the floor the pivot may sit, as a fraction of the framing, before the framing doubles.
private const val PIVOT_REACH = 0.8f

// The share of the framing kept in front of the nearer of the art plane and the pivot.
private const val NEAR_MARGIN = 0.1f

// The floor's half-width at its near edge, in framing spans per unit of the inset's aspect ratio, which keeps the
// nearest cells roughly square.
private const val LATERAL_SPAN_PER_ASPECT = 0.3f

/**
 * One straight segment of the depth inset, in the inset's own pixels.
 *
 * @property Offset start The segment's first end.
 * @property Offset end The segment's second end.
 */
internal data class InsetSegment(val start: Offset, val end: Offset)

/**
 * The depth inset's picture: a floor grid in one-point perspective, receding from the viewer toward a horizon, with
 * the art's plane across it and the depth rotation's pivot pushed into it.  Depth runs up the inset (world Y, into the
 * screen), so a pivot pushed away climbs toward the horizon, and one pulled toward the viewer sits in front of the
 * art plane.  The floor's lines fall at multiples of the viewport grid's major spacing, doubled as the pivot goes
 * deeper so a framing never holds more than [TRANSVERSE_LINES] of them.
 *
 * @property List<InsetSegment> gridLines The floor's grid: the lines across it, then the lines receding into it.
 * @property InsetSegment artPlane The art's plane (depth 0), across the floor.
 * @property Offset artCenter Where the depth line leaves the art plane, under the pivot.
 * @property Offset pivot The pivot, at its depth.
 * @property Float horizonY The horizon's height, the vanishing point's y.
 */
internal class PivotDepthInsetGeometry(
	val gridLines: List<InsetSegment>,
	val artPlane: InsetSegment,
	val artCenter: Offset,
	val pivot: Offset,
	val horizonY: Float,
)

/**
 * Lays out the depth inset for [pivotDepth] over an inset [width] x [height] pixels.
 *
 * The framing spans [TRANSVERSE_LINES] grid steps of [gridScale] doubled until the pivot sits within [PIVOT_REACH]
 * of it, so pushing the pivot visibly walks it toward the horizon, and only a push past the framing's reach widens it.
 *
 * @param Float pivotDepth The pivot's depth along world Y, in world px, positive into the screen.
 * @param Float gridScale The viewport grid's major spacing, in world px.
 * @param Float width The inset's width in px.
 * @param Float height The inset's height in px.
 * @return PivotDepthInsetGeometry The picture.
 */
internal fun pivotDepthInsetGeometry(pivotDepth: Float, gridScale: Float, width: Float, height: Float): PivotDepthInsetGeometry {
	val baseStep = if (gridScale > 0f && gridScale.isFinite()) gridScale else 1f
	val reach = abs(pivotDepth)
	var step = baseStep

	while (reach > TRANSVERSE_LINES * step * (PIVOT_REACH - NEAR_MARGIN) && step < Float.MAX_VALUE / 4f) {
		step *= 2f
	}

	val span = TRANSVERSE_LINES * step
	val near = minOf(0f, pivotDepth) - NEAR_MARGIN * span
	val horizonY = height * HORIZON_FRACTION
	val centerX = width / 2f
	val aspect = if (height > 0f) width / height else 1f
	val halfLateral = span * LATERAL_SPAN_PER_ASPECT * aspect
	val projection = FloorProjection(near, span, horizonY, height, centerX, if (halfLateral > 0f) (width / 2f) / halfLateral else 0f)
	val lines = ArrayList<InsetSegment>()

	// Across the floor: every multiple of the step inside the framing.
	val firstRow = ceil(near / step).toInt()
	val lastRow = floor((near + span) / step).toInt()

	for (row in firstRow..lastRow) {
		val depth = row * step
		lines.add(InsetSegment(projection.at(-halfLateral, depth), projection.at(halfLateral, depth)))
	}

	// Into the floor: every multiple of the step across its near edge, converging on the vanishing point.
	val columns = floor(halfLateral / step).toInt()

	for (column in -columns..columns) {
		val lateral = column * step
		lines.add(InsetSegment(projection.at(lateral, near), projection.at(lateral, near + span)))
	}

	return PivotDepthInsetGeometry(
		gridLines = lines,
		artPlane = InsetSegment(projection.at(-halfLateral, 0f), projection.at(halfLateral, 0f)),
		artCenter = projection.at(0f, 0f),
		pivot = projection.at(0f, pivotDepth),
		horizonY = horizonY,
	)
}

/**
 * The floor's one-point perspective: a floor point at a lateral offset and a depth to the inset's pixels.  A depth's
 * share of the framing shrinks toward the horizon by 1 / (1 + [FLOOR_RECESSION] x share), and so does its width.
 *
 * @property Float near The framing's nearest depth, world px.
 * @property Float span The framing's depth span, world px.
 * @property Float horizonY The horizon's height in px.
 * @property Float bottomY The near edge's height in px.
 * @property Float centerX The vanishing point's x in px.
 * @property Float pixelsPerLateral Pixels per world px of lateral offset at the near edge.
 */
private class FloorProjection(
	val near: Float,
	val span: Float,
	val horizonY: Float,
	val bottomY: Float,
	val centerX: Float,
	val pixelsPerLateral: Float,
) {
	/**
	 * The inset pixel of a floor point.
	 *
	 * @param Float lateral The point's offset across the floor, world px.
	 * @param Float depth The point's depth, world px.
	 * @return Offset The pixel.
	 */
	fun at(lateral: Float, depth: Float): Offset {
		val share = if (span > 0f) (depth - near) / span else 0f
		val shrink = 1f / (1f + FLOOR_RECESSION * share)

		return Offset(centerX + lateral * pixelsPerLateral * shrink, horizonY + (bottomY - horizonY) * shrink)
	}
}