package org.umamo.geometry.triangulation.refine

/*
 * A point's barycentric weights in a triangle and the blend they make, the one linear interpolation a
 * guide and the refinement measuring against it share: an error is the distance between two of these
 * interpolations, so both must round alike.  The weights are unclamped, so a point outside the
 * triangle carries its linear map on.
 */

/**
 * Hands a point's barycentric weights in a triangle to a block, allocating nothing.
 *
 * @param Double                        firstX  The first corner's x.
 * @param Double                        firstY  The first corner's y.
 * @param Double                        secondX The second corner's x.
 * @param Double                        secondY The second corner's y.
 * @param Double                        thirdX  The third corner's x.
 * @param Double                        thirdY  The third corner's y.
 * @param Double                        x       The point's x.
 * @param Double                        y       The point's y.
 * @param (Double, Double, Double) -> T block   Receives the first, second, and third corner's weights.
 * @return T What the block returns.
 */
internal inline fun <T> withBarycentricWeights(
	firstX: Double,
	firstY: Double,
	secondX: Double,
	secondY: Double,
	thirdX: Double,
	thirdY: Double,
	x: Double,
	y: Double,
	block: (firstWeight: Double, secondWeight: Double, thirdWeight: Double) -> T,
): T {
	val secondOffsetX = secondX - firstX
	val secondOffsetY = secondY - firstY
	val thirdOffsetX = thirdX - firstX
	val thirdOffsetY = thirdY - firstY
	val pointOffsetX = x - firstX
	val pointOffsetY = y - firstY
	val determinant = secondOffsetX * thirdOffsetY - secondOffsetY * thirdOffsetX
	val secondWeight = (pointOffsetX * thirdOffsetY - pointOffsetY * thirdOffsetX) / determinant
	val thirdWeight = (secondOffsetX * pointOffsetY - secondOffsetY * pointOffsetX) / determinant

	return block(1.0 - secondWeight - thirdWeight, secondWeight, thirdWeight)
}

/**
 * Three corner values blended by barycentric weights.
 *
 * @param Double firstWeight  The first corner's weight.
 * @param Double secondWeight The second corner's weight.
 * @param Double thirdWeight  The third corner's weight.
 * @param Double firstValue   The first corner's value.
 * @param Double secondValue  The second corner's value.
 * @param Double thirdValue   The third corner's value.
 * @return Double The blended value.
 */
internal fun blendCorners(firstWeight: Double, secondWeight: Double, thirdWeight: Double, firstValue: Double, secondValue: Double, thirdValue: Double): Double =
	firstWeight * firstValue + secondWeight * secondValue + thirdWeight * thirdValue