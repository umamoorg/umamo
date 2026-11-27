package org.umamo.geometry.triangulation

/*
 * Ordering points that lie exactly on one line.  On a line, either coordinate the line spans orders
 * its points, and the sign of a floating-point difference is always right, so comparing one coordinate
 * is exact where a dot product or a projection parameter would round.
 */

/**
 * Where a point on a line lies against a reference point on it, along the line's direction.  Exact
 * for two points exactly on the line.
 *
 * @param Double directionX The line's direction x.
 * @param Double directionY The line's direction y.
 * @param Double referenceX The reference point's x.
 * @param Double referenceY The reference point's y.
 * @param Double pointX     The point's x.
 * @param Double pointY     The point's y.
 * @return Int Negative when the point lies before the reference, zero at it (or for a line of no length), positive beyond it.
 */
internal fun compareAlongLine(directionX: Double, directionY: Double, referenceX: Double, referenceY: Double, pointX: Double, pointY: Double): Int {
	if (directionX != 0.0) {
		return compareOnAxis(pointX, referenceX, directionX > 0.0)
	}

	if (directionY != 0.0) {
		return compareOnAxis(pointY, referenceY, directionY > 0.0)
	}

	return 0
}

/**
 * Where one coordinate lies against another along an axis a line runs up or down.
 *
 * @param Double  value      The point's coordinate.
 * @param Double  reference  The reference point's coordinate.
 * @param Boolean increasing Whether the line runs toward larger coordinates.
 * @return Int Negative before the reference, zero at it, positive beyond it.
 */
private fun compareOnAxis(value: Double, reference: Double, increasing: Boolean): Int {
	if (value == reference) {
		return 0
	}

	return if ((value > reference) == increasing) 1 else -1
}