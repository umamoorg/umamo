package org.umamo.ui.tracks

import kotlin.math.abs

/**
 * Maps a fraction across the lane to a pixel x, inset by [markRadius] at both ends.
 *
 * Without the inset a mark at either end of the domain draws half outside the lane and reads as clipped -
 * which is exactly what a key at either end of an owner's range is, and every axis has two of those.
 *
 * @param Float fraction The 0..1 position across the domain.
 * @param Float laneWidth The lane's pixel width.
 * @param Float markRadius The half-extent of a mark, in pixels.
 * @return Float The pixel x to draw at.
 */
internal fun laneX(fraction: Float, laneWidth: Float, markRadius: Float): Float {
	val usable = laneWidth - markRadius * 2f
	return if (usable <= 0f) laneWidth * 0.5f else markRadius + fraction * usable
}

/**
 * The inverse of [laneX]: the domain value under a pixel x.
 *
 * @param Float x The pixel x within the lane.
 * @param TrackAxis axis The domain.
 * @param Int laneWidth The lane's pixel width.
 * @param Float markRadius The half-extent of a mark, in pixels.
 * @return Float The domain value there.
 */
internal fun domainAt(x: Float, axis: TrackAxis, laneWidth: Int, markRadius: Float): Float {
	val usable = laneWidth - markRadius * 2f
	return if (usable <= 0f) axis.valueAt(0.5f) else axis.valueAt((x - markRadius) / usable)
}

/**
 * The pixel x a mark at [domainValue] is drawn at, within a lane [laneWidth] wide.
 *
 * Exposed because anything resolving a region of the sheet back to keys (a marquee) has to use the SAME
 * mapping the marks were drawn with, including the end inset.  Re-deriving it drifts by exactly the inset,
 * which is the width of a mark - so this is the one definition, and [laneX] behind it is the one the marks
 * themselves go through.
 *
 * @param TrackAxis axis The VISIBLE domain (a zoomed sheet passes its window's axis, not the full range).
 * @param Float domainValue The mark's position.
 * @param Float laneWidth The lane's pixel width.
 * @param Float markRadiusPx The sheet's mark radius, which is also its end inset.
 * @return Float The pixel offset within the lane.
 */
fun laneMarkOffsetX(axis: TrackAxis, domainValue: Float, laneWidth: Float, markRadiusPx: Float): Float =
	laneX(axis.fractionOf(domainValue), laneWidth, markRadiusPx)

/**
 * The pick radius for a mark, in DOMAIN units, so it stays a constant number of pixels however wide the
 * panel is or however large the domain is.
 *
 * @param TrackAxis axis The domain.
 * @param Int laneWidth The lane's pixel width.
 * @param Float markRadius The half-extent of a mark, in pixels.
 * @return Float The tolerance, in domain units.
 */
internal fun pickTolerance(axis: TrackAxis, laneWidth: Int, markRadius: Float): Float {
	val usable = laneWidth - markRadius * 2f
	return if (usable <= 0f) 0f else abs(axis.span) * (markRadius * 1.5f / usable)
}