package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.triangulation.HalfEdgeTriangulation
import kotlin.math.PI
import kotlin.math.sqrt

/*
 * Angle tests without inverse trigonometry, so every decision refinement makes is the same on every
 * platform.  An angle is compared through its sine: in a triangle the smallest angle is at most 60
 * degrees, where sine rises with the angle, and twice the triangle's area is the product of the two
 * edges at a corner times the sine there.  So "the smallest angle is below the target" is a plain
 * inequality of squared lengths and a squared cross product.  The target's own sine comes from a
 * fixed-term series in additions, multiplications, and divisions - each correctly rounded - rather
 * than a library sine whose last bit may differ between platforms.
 */

/** The radians in one degree. */
private const val RADIANS_PER_DEGREE: Double = PI / 180.0

/** The series terms: x^21 / 21! is below 1e-19 at 90 degrees, past the last bit of a Double. */
private const val SINE_SERIES_TERMS: Int = 10

/**
 * The squared sine of an angle in degrees, from the Taylor series of sine evaluated in Horner form:
 * sin x = x (1 - x^2 / (2 * 3) (1 - x^2 / (4 * 5) (1 - ...))).
 *
 * @param Double degrees The angle, 0 to 90 degrees.
 * @return Double Its squared sine.
 */
internal fun sineSquaredOfDegrees(degrees: Double): Double {
	require(degrees in 0.0..90.0) { "the angle $degrees is outside 0 to 90 degrees" }
	val radians = degrees * RADIANS_PER_DEGREE
	val radiansSquared = radians * radians
	var series = 1.0

	for (term in SINE_SERIES_TERMS downTo 1) {
		series = 1.0 - radiansSquared / ((2 * term) * (2 * term + 1)).toDouble() * series
	}

	val sine = radians * series

	return sine * sine
}

/**
 * The cotangent of half an angle, from the angle's squared sine: (1 + cos a) / sin a.
 *
 * @param Double sineSquared The squared sine of an angle between 0 (exclusive) and 90 degrees.
 * @return Double cot(a / 2).
 */
internal fun cotangentOfHalfAngle(sineSquared: Double): Double = (1.0 + sqrt(1.0 - sineSquared)) / sqrt(sineSquared)

/**
 * The squared cosine of a diametral lens's angle, from the angle its arcs make with the segment: a lens
 * whose arcs meet the segment at 2 theta holds the points that see the segment under more than
 * 180 - 2 theta degrees, and cos(180 - 2 theta) = -(1 - 2 sin^2 theta).
 *
 * @param Double halfArcSineSquared sin^2 theta.
 * @return Double cos^2 of the lens angle.
 */
internal fun lensCosineSquared(halfArcSineSquared: Double): Double {
	val cosine = 1.0 - 2.0 * halfArcSineSquared

	return cosine * cosine
}

/**
 * Whether a point lies strictly inside a segment's diametral lens: it sees the segment under an angle
 * wider than the lens angle (at least 90 degrees, so the angle is obtuse and its cosine negative).
 *
 * @param Double startX             The segment's start x.
 * @param Double startY             The segment's start y.
 * @param Double endX               The segment's end x.
 * @param Double endY               The segment's end y.
 * @param Double pointX             The point's x.
 * @param Double pointY             The point's y.
 * @param Double lensCosineSquared  cos^2 of the lens angle (see [lensCosineSquared]).
 * @return Boolean True when the point encroaches the segment.
 */
internal fun liesInLens(startX: Double, startY: Double, endX: Double, endY: Double, pointX: Double, pointY: Double, lensCosineSquared: Double): Boolean {
	val toStartX = startX - pointX
	val toStartY = startY - pointY
	val toEndX = endX - pointX
	val toEndY = endY - pointY
	val dot = toStartX * toEndX + toStartY * toEndY

	if (dot >= 0.0) {
		return false
	}

	return dot * dot > lensCosineSquared * (toStartX * toStartX + toStartY * toStartY) * (toEndX * toEndX + toEndY * toEndY)
}

/**
 * One triangle's shape, measured without inverse trigonometry: which corner holds its smallest angle
 * (the corner opposite the shortest edge, ties to the lower corner) and that angle's squared sine.
 * Reused across triangles, so measuring allocates nothing.
 */
internal class TriangleShape {
	/** The corner (0, 1, or 2, in the order measured) opposite the shortest edge. */
	var smallestCorner: Int = 0
		private set

	/** The squared sine of the smallest angle; it rises with the angle, which is never above 60 degrees. */
	var smallestSineSquared: Double = 0.0
		private set

	/**
	 * Measures a positively oriented triangle.
	 *
	 * @param Double firstX  The first corner's x.
	 * @param Double firstY  The first corner's y.
	 * @param Double secondX The second corner's x.
	 * @param Double secondY The second corner's y.
	 * @param Double thirdX  The third corner's x.
	 * @param Double thirdY  The third corner's y.
	 */
	fun measure(firstX: Double, firstY: Double, secondX: Double, secondY: Double, thirdX: Double, thirdY: Double) {
		val oppositeFirst = squaredDistance(secondX, secondY, thirdX, thirdY)
		val oppositeSecond = squaredDistance(thirdX, thirdY, firstX, firstY)
		val oppositeThird = squaredDistance(firstX, firstY, secondX, secondY)
		val doubledArea = (secondX - firstX) * (thirdY - firstY) - (secondY - firstY) * (thirdX - firstX)
		val doubledAreaSquared = doubledArea * doubledArea

		// The smallest angle's sine is twice the area over the two longer edges at its corner.
		if (oppositeFirst <= oppositeSecond && oppositeFirst <= oppositeThird) {
			smallestCorner = 0
			smallestSineSquared = doubledAreaSquared / (oppositeSecond * oppositeThird)
		} else if (oppositeSecond <= oppositeThird) {
			smallestCorner = 1
			smallestSineSquared = doubledAreaSquared / (oppositeThird * oppositeFirst)
		} else {
			smallestCorner = 2
			smallestSineSquared = doubledAreaSquared / (oppositeFirst * oppositeSecond)
		}
	}

	/**
	 * Measures a store triangle given by its corners, in counterclockwise order.
	 *
	 * @param HalfEdgeTriangulation store  The store.
	 * @param Int                   first  The first corner.
	 * @param Int                   second The second corner.
	 * @param Int                   third  The third corner.
	 */
	fun measure(store: HalfEdgeTriangulation, first: Int, second: Int, third: Int) {
		measure(store.pointX(first), store.pointY(first), store.pointX(second), store.pointY(second), store.pointX(third), store.pointY(third))
	}
}

/**
 * Runs an action for every inside triangle whose smallest angle lies below a target, in slot order:
 * the one test refinement, its statistics, and an unrefined count all read.
 *
 * @param Double                  targetSineSquared The target angle's squared sine.
 * @param TriangleShape           shape             Measures each triangle; holds that triangle's shape while the action runs.
 * @param (Int, Int, Int) -> Unit action            Receives the triangle's corners, counterclockwise.
 */
internal inline fun HalfEdgeTriangulation.forEachInsideBelow(targetSineSquared: Double, shape: TriangleShape, action: (first: Int, second: Int, third: Int) -> Unit) {
	for (triangle in 0 until triangleCount) {
		if (!regions[triangle]) {
			continue
		}

		val base = 3 * triangle
		val first = origin[base]
		val second = origin[base + 1]
		val third = origin[base + 2]
		shape.measure(this, first, second, third)

		if (shape.smallestSineSquared < targetSineSquared) {
			action(first, second, third)
		}
	}
}

/**
 * The squared distance between two points.
 *
 * @param Double firstX  The first point's x.
 * @param Double firstY  The first point's y.
 * @param Double secondX The second point's x.
 * @param Double secondY The second point's y.
 * @return Double The squared distance.
 */
internal fun squaredDistance(firstX: Double, firstY: Double, secondX: Double, secondY: Double): Double {
	val deltaX = secondX - firstX
	val deltaY = secondY - firstY

	return deltaX * deltaX + deltaY * deltaY
}