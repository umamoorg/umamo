package org.umamo.edit.transform

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Accumulates a modal rotate gesture's angle from per-move increments, each wrapped into (-pi, pi], so
 * the total walks smoothly through the atan2 branch cut at +-pi and supports multi-turn rotation.  A
 * raw start-to-current atan2 subtraction cannot: a far pivot (the 2D cursor off to the side) places the
 * start angle right AT the cut, where the difference jumps by ~2*pi and the gesture's arc direction
 * latches (it can never reverse past its start).  One tracker lives per gesture capture; feeding the
 * same pointer angle twice adds zero, so re-derives without pointer motion are safe.
 */
class RotationAngleTracker {
	private var previousPointerAngle: Float? = null

	/** The accumulated gesture angle in radians (screen-space sign; the caller negates into world space). */
	var totalAngle: Float = 0f
		private set

	/**
	 * Advances the accumulator to the pointer's current angle about the pivot and returns the total.
	 *
	 * @param Float pointerAngle The pointer's atan2 angle about the rotation pivot, radians.
	 * @return Float The accumulated gesture angle, radians.
	 */
	fun advance(pointerAngle: Float): Float {
		val previous = previousPointerAngle
		if (previous != null) {
			totalAngle += wrapAngle(pointerAngle - previous)
		}
		previousPointerAngle = pointerAngle
		return totalAngle
	}
}

/**
 * Wraps an angle difference into (-pi, pi] - the shortest signed arc between two angles.
 *
 * @param Float delta The raw angle difference, radians.
 * @return Float The wrapped difference.
 */
fun wrapAngle(delta: Float): Float = atan2(sin(delta), cos(delta))