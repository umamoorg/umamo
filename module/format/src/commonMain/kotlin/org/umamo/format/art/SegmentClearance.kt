package org.umamo.format.art

import kotlin.math.ceil
import kotlin.math.floor

/*
 * The two exact checks an outline chord must pass before the mesher trusts it, both against the
 * full-resolution pixels rather than the downsampled field.
 *
 * CLEARANCE: every opaque pixel SQUARE stays at least the required distance from the chord, measured
 * as true Euclidean distance between the segment and the unit square.  Only pixels within the
 * chord's dilated band are visited.
 *
 * CAP: no opaque pixel CENTER lies inside a polygon - the polygon between a chord and the stretch of
 * dense ring it replaces.  Clearance alone cannot rule this out: a chord bridging a concave stretch
 * can swallow a whole speck far from every pixel it passes, and even-odd classification would then
 * erase the speck.  Counted by scanlines through pixel-center rows, even-odd.
 */

/**
 * Whether every opaque pixel square of the mask lies at least a distance from a segment.
 *
 * @param Double startX           The segment's start x, raster px.
 * @param Double startY           The segment's start y.
 * @param Double endX             The segment's end x.
 * @param Double endY             The segment's end y.
 * @param Double requiredDistance The clearance required (non-negative).
 * @return Boolean True when no opaque pixel square comes closer.
 */
internal fun CroppedAlphaMask.segmentClears(startX: Double, startY: Double, endX: Double, endY: Double, requiredDistance: Double): Boolean {
	val requiredSquared = requiredDistance * requiredDistance
	val firstRow = maxOf(floor(minOf(startY, endY) - requiredDistance).toInt() - 1, top)
	val lastRow = minOf(floor(maxOf(startY, endY) + requiredDistance).toInt() + 1, top + height - 1)

	for (row in firstRow..lastRow) {
		// The part of the segment that can reach this row's squares lies in y within the row's own
		// band widened by the clearance; scan only the columns under that part, widened the same way.
		val bandTop = row - requiredDistance
		val bandBottom = row + 1 + requiredDistance
		val parameterRange = parameterRangeInBand(startY, endY, bandTop, bandBottom) ?: continue
		val firstX = startX + parameterRange.first * (endX - startX)
		val lastX = startX + parameterRange.second * (endX - startX)
		val firstColumn = maxOf(floor(minOf(firstX, lastX) - requiredDistance).toInt() - 1, left)
		val lastColumn = minOf(floor(maxOf(firstX, lastX) + requiredDistance).toInt() + 1, left + width - 1)

		for (column in firstColumn..lastColumn) {
			if (isOpaque(column, row) && squaredDistanceToSquare(startX, startY, endX, endY, column.toDouble(), row.toDouble()) < requiredSquared) {
				return false
			}
		}
	}

	return true
}

/**
 * Whether any opaque pixel center lies strictly inside a polygon, by the even-odd rule.
 *
 * @param DoubleArray polygon The polygon's vertices, x0, y0, ...; closed implicitly.
 * @return Boolean True when some opaque pixel's center is inside.
 */
internal fun CroppedAlphaMask.containsOpaquePixelCenter(polygon: DoubleArray): Boolean {
	val vertexCount = polygon.size / 2

	if (vertexCount < 3) {
		return false
	}

	var minimumY = Double.POSITIVE_INFINITY
	var maximumY = Double.NEGATIVE_INFINITY

	for (vertex in 0 until vertexCount) {
		minimumY = minOf(minimumY, polygon[2 * vertex + 1])
		maximumY = maxOf(maximumY, polygon[2 * vertex + 1])
	}

	val firstRow = maxOf(ceil(minimumY - 0.5).toInt(), top)
	val lastRow = minOf(floor(maximumY - 0.5).toInt(), top + height - 1)
	val crossings = DoubleArray(vertexCount)

	for (row in firstRow..lastRow) {
		val centerY = row + 0.5
		var crossingCount = 0

		for (vertex in 0 until vertexCount) {
			val next = (vertex + 1) % vertexCount
			val fromY = polygon[2 * vertex + 1]
			val toY = polygon[2 * next + 1]

			// Half-open in y, so a scanline through a vertex counts the two edges meeting there once.
			if ((fromY <= centerY && toY > centerY) || (toY <= centerY && fromY > centerY)) {
				val fromX = polygon[2 * vertex]
				val toX = polygon[2 * next]
				crossings[crossingCount] = fromX + (centerY - fromY) * (toX - fromX) / (toY - fromY)
				crossingCount++
			}
		}

		crossings.sort(0, crossingCount)

		for (pair in 0 until crossingCount / 2) {
			val spanStart = crossings[2 * pair]
			val spanEnd = crossings[2 * pair + 1]
			// Columns whose center column + 0.5 falls strictly inside the span.
			val firstColumn = maxOf(floor(spanStart - 0.5).toInt() + 1, left)
			val lastColumn = minOf(ceil(spanEnd - 0.5).toInt() - 1, left + width - 1)

			for (column in firstColumn..lastColumn) {
				if (isOpaque(column, row)) {
					return true
				}
			}
		}
	}

	return false
}

/**
 * The part of a segment's parameter range whose y lies within a band.
 *
 * @param Double startY     The segment's start y.
 * @param Double endY       The segment's end y.
 * @param Double bandTop    The band's top.
 * @param Double bandBottom The band's bottom.
 * @return Pair<Double, Double>? The parameter range within [0, 1], or null when the segment misses the band.
 */
private fun parameterRangeInBand(startY: Double, endY: Double, bandTop: Double, bandBottom: Double): Pair<Double, Double>? {
	if (startY == endY) {
		return if (startY in bandTop..bandBottom) 0.0 to 1.0 else null
	}

	val atTop = (bandTop - startY) / (endY - startY)
	val atBottom = (bandBottom - startY) / (endY - startY)
	val first = maxOf(minOf(atTop, atBottom), 0.0)
	val last = minOf(maxOf(atTop, atBottom), 1.0)

	return if (first <= last) first to last else null
}

/**
 * The squared Euclidean distance between a segment and a unit pixel square.
 *
 * Zero when they intersect; otherwise the two convex sets are disjoint and the distance is attained
 * at a vertex of one of them - a segment end against the square, or a square corner against the
 * segment.
 *
 * @param Double startX The segment's start x.
 * @param Double startY The segment's start y.
 * @param Double endX   The segment's end x.
 * @param Double endY   The segment's end y.
 * @param Double left   The square's left edge.
 * @param Double top    The square's top edge.
 * @return Double The squared distance.
 */
internal fun squaredDistanceToSquare(startX: Double, startY: Double, endX: Double, endY: Double, left: Double, top: Double): Double {
	if (segmentIntersectsSquare(startX, startY, endX, endY, left, top)) {
		return 0.0
	}

	var nearest = minOf(squaredDistancePointToSquare(startX, startY, left, top), squaredDistancePointToSquare(endX, endY, left, top))

	for (corner in 0 until 4) {
		val cornerX = left + (corner and 1)
		val cornerY = top + (corner shr 1)
		nearest = minOf(nearest, squaredDistancePointToSegment(cornerX, cornerY, startX, startY, endX, endY))
	}

	return nearest
}

/**
 * Whether a segment meets a unit square (Liang-Barsky clipping).
 *
 * @param Double startX The segment's start x.
 * @param Double startY The segment's start y.
 * @param Double endX   The segment's end x.
 * @param Double endY   The segment's end y.
 * @param Double left   The square's left edge.
 * @param Double top    The square's top edge.
 * @return Boolean True when some point of the segment lies in the closed square.
 */
private fun segmentIntersectsSquare(startX: Double, startY: Double, endX: Double, endY: Double, left: Double, top: Double): Boolean {
	var entering = 0.0
	var leaving = 1.0
	val deltaX = endX - startX
	val deltaY = endY - startY
	val directions = doubleArrayOf(-deltaX, deltaX, -deltaY, deltaY)
	val distances = doubleArrayOf(startX - left, left + 1.0 - startX, startY - top, top + 1.0 - startY)

	for (side in 0 until 4) {
		if (directions[side] == 0.0) {
			if (distances[side] < 0.0) {
				return false
			}
		} else {
			val parameter = distances[side] / directions[side]

			if (directions[side] < 0.0) {
				entering = maxOf(entering, parameter)
			} else {
				leaving = minOf(leaving, parameter)
			}
		}
	}

	return entering <= leaving
}

/**
 * The squared distance from a point to a unit square (zero inside it).
 *
 * @param Double pointX The point's x.
 * @param Double pointY The point's y.
 * @param Double left   The square's left edge.
 * @param Double top    The square's top edge.
 * @return Double The squared distance.
 */
private fun squaredDistancePointToSquare(pointX: Double, pointY: Double, left: Double, top: Double): Double {
	val offsetX = maxOf(left - pointX, 0.0, pointX - (left + 1.0))
	val offsetY = maxOf(top - pointY, 0.0, pointY - (top + 1.0))

	return offsetX * offsetX + offsetY * offsetY
}

/**
 * The squared distance from a point to a segment.
 *
 * @param Double pointX The point's x.
 * @param Double pointY The point's y.
 * @param Double startX The segment's start x.
 * @param Double startY The segment's start y.
 * @param Double endX   The segment's end x.
 * @param Double endY   The segment's end y.
 * @return Double The squared distance.
 */
private fun squaredDistancePointToSegment(pointX: Double, pointY: Double, startX: Double, startY: Double, endX: Double, endY: Double): Double {
	val alongX = endX - startX
	val alongY = endY - startY
	val lengthSquared = alongX * alongX + alongY * alongY
	val fraction = if (lengthSquared > 0.0) (((pointX - startX) * alongX + (pointY - startY) * alongY) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
	val offsetX = startX + fraction * alongX - pointX
	val offsetY = startY + fraction * alongY - pointY

	return offsetX * offsetX + offsetY * offsetY
}