package org.umamo.ui.kit.field

import kotlin.math.abs

/*
 * Pure, Compose-free pad math: the value a pointer sets along one axis of the pad, the held-Ctrl snap each axis
 * applies by itself, and the pips the pad draws where its two axes' keys cross.  Kept apart so the pad's behavior is
 * unit testable without a Compose harness (see PadMathTest).
 *
 * Each axis maps over the pad's full extent, its minimum at one edge and its maximum at the other.  The vertical axis
 * is inverted, so the top of the pad is its maximum.
 */

/** The shape of a pip where the two axes' keys cross, from the shapes of the key marks that meet there. */
enum class PadKeyPipShape {
	/** Only circle marks meet here, so the pip is the slider's circle. */
	Circle,

	/** Only square marks meet here, so the pip is the slider's rounded square. */
	Square,

	/** Both shapes meet here, so the pip is the square's top half over the circle's bottom half. */
	SquareOverCircle,
}

/**
 * One pip the pad draws, where a key on its horizontal axis crosses a key on its vertical one.
 *
 * @property Float x The horizontal axis's key value.
 * @property Float y The vertical axis's key value.
 * @property PadKeyPipShape shape The pip's shape.
 */
data class PadKeyPip(
	val x: Float,
	val y: Float,
	val shape: PadKeyPipShape,
)

/**
 * The value a pointer at [coordinate] along one axis of the pad sets: its place across [extent], held to the ends.
 *
 * @param Float coordinate The pointer's position along the axis (x or y).
 * @param Float extent The axis's length in pixels (the pad's width or height).
 * @param ClosedFloatingPointRange range The axis's min..max.
 * @param Boolean invert Whether the axis runs the other way, so coordinate 0 (the top of a Canvas) is the maximum.
 * @return Float The value at [coordinate].
 */
fun padValueAt(coordinate: Float, extent: Float, range: ClosedFloatingPointRange<Float>, invert: Boolean): Float {
	val rawFraction = (coordinate / usablePadExtent(extent)).coerceIn(0f, 1f)
	val fraction = if (invert) 1f - rawFraction else rawFraction

	return range.start + fraction * (range.endInclusive - range.start)
}

/**
 * Where [value] draws along one axis of the pad, as the part of its extent from the minimum's edge.  A value outside
 * [range] draws at the end it is past.
 *
 * @param Float value The value to draw.
 * @param ClosedFloatingPointRange range The axis's min..max.
 * @return Float The fraction, in 0..1; 0 for an empty range.
 */
fun padFractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
	val span = range.endInclusive - range.start

	if (span == 0f) {
		return 0f
	}

	return ((value - range.start) / span).coerceIn(0f, 1f)
}

/**
 * The value one axis of the pad takes while Ctrl is held: [value], latched onto the nearest of [snapTargets] within
 * [snapRadius] pixels of it along that axis.  Each axis latches by itself, so the handle lands on a key line near one
 * and on a pip near a crossing.
 *
 * @param Float value The axis's value under the pointer.
 * @param List snapTargets The axis's key values, empty when Ctrl is not held.
 * @param Float snapRadius How near a key the pointer must come to latch, in pixels.
 * @param Float extent The axis's length in pixels.
 * @param ClosedFloatingPointRange range The axis's min..max.
 * @return Float The value, snapped.
 */
fun padAxisSnapped(value: Float, snapTargets: List<Float>, snapRadius: Float, extent: Float, range: ClosedFloatingPointRange<Float>): Float {
	if (snapTargets.isEmpty()) {
		return value
	}

	val tolerance = abs(range.endInclusive - range.start) * snapRadius / usablePadExtent(extent)
	// Bounded by the range because a key can sit outside it on a malformed import.  The minOf / maxOf form tolerates a
	// reversed range, where a plain range would hold nothing.
	val bounds = minOf(range.start, range.endInclusive)..maxOf(range.start, range.endInclusive)

	return snapToNearestWithin(value, snapTargets, tolerance, bounds)
}

/**
 * The values one axis of the pad marks with a key line and latches onto: each mark's value once, in mark order.
 *
 * @param List keyMarks The axis's key marks.
 * @return List<Float> The distinct values.
 */
fun padKeyLineValues(keyMarks: List<SliderKeyMark>): List<Float> = keyMarks.map { mark -> mark.value }.distinct()

/**
 * The pips where the two axes' keys cross: one for every pairing of a horizontal key value with a vertical one.  A
 * pip's shape shows the shapes of the marks that meet at it, so a value marked both ways makes each of its pips the
 * mixed one.
 *
 * @param List xKeyMarks The horizontal axis's key marks.
 * @param List yKeyMarks The vertical axis's key marks.
 * @return List<PadKeyPip> The pips, by horizontal value and then vertical, each in mark order; empty when either axis
 *   has no marks.
 */
fun padKeyPips(xKeyMarks: List<SliderKeyMark>, yKeyMarks: List<SliderKeyMark>): List<PadKeyPip> {
	val xShapes = markShapesByValue(xKeyMarks)
	val yShapes = markShapesByValue(yKeyMarks)

	return xShapes.flatMap { (x, xMarkShapes) ->
		yShapes.map { (y, yMarkShapes) -> PadKeyPip(x, y, pipShapeOf(xMarkShapes + yMarkShapes)) }
	}
}

/**
 * The shapes an axis's marks take at each of its values, the values in mark order.
 *
 * @param List keyMarks The axis's key marks.
 * @return Map<Float, Set<SliderKeyShape>> Each distinct value's mark shapes.
 */
private fun markShapesByValue(keyMarks: List<SliderKeyMark>): Map<Float, Set<SliderKeyShape>> =
	keyMarks.groupBy({ mark -> mark.value }, { mark -> mark.shape }).mapValues { (_, shapes) -> shapes.toSet() }

/**
 * The pip for a crossing where marks of [markShapes] meet.
 *
 * @param Set markShapes The shapes of every mark at the crossing, from both axes.
 * @return PadKeyPipShape The mixed pip when both shapes meet, else the one shape there.
 */
private fun pipShapeOf(markShapes: Set<SliderKeyShape>): PadKeyPipShape =
	when {
		SliderKeyShape.Circle in markShapes && SliderKeyShape.Square in markShapes -> PadKeyPipShape.SquareOverCircle
		SliderKeyShape.Square in markShapes -> PadKeyPipShape.Square
		else -> PadKeyPipShape.Circle
	}

/**
 * The extent an axis maps over, floored at one pixel so a pad with no size cannot divide by zero.
 *
 * @param Float extent The axis's length in pixels.
 * @return Float The extent to divide by.
 */
private fun usablePadExtent(extent: Float): Float = extent.takeIf { length -> length > 0f } ?: 1f