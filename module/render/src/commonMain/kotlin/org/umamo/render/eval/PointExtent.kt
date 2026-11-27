package org.umamo.render.eval

/**
 * The axis-aligned box around a set of points, in the space the points were given in.
 *
 * @property Float minX The smallest x.
 * @property Float minY The smallest y.
 * @property Float maxX The largest x.
 * @property Float maxY The largest y.
 */
internal class PointExtent(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

/**
 * The box around interleaved (x, y) points, such as a warp lattice's control points.
 *
 * @param FloatArray points The points, interleaved x, y.
 * @return PointExtent? The box, or null when there is no whole point.
 */
internal fun pointExtentOf(points: FloatArray): PointExtent? {
	if (points.size < 2) {
		return null
	}

	var minX = Float.POSITIVE_INFINITY
	var minY = Float.POSITIVE_INFINITY
	var maxX = Float.NEGATIVE_INFINITY
	var maxY = Float.NEGATIVE_INFINITY
	var slotIndex = 0

	while (slotIndex + 1 < points.size) {
		minX = minOf(minX, points[slotIndex])
		maxX = maxOf(maxX, points[slotIndex])
		minY = minOf(minY, points[slotIndex + 1])
		maxY = maxOf(maxY, points[slotIndex + 1])
		slotIndex += 2
	}

	return PointExtent(minX, minY, maxX, maxY)
}

/**
 * The box around the points of every entry of [pointsByKey] that [keys] lists, in the points' own space: the one
 * sweep a framing box is measured by, whatever the points belong to.
 *
 * @param Map<Key, FloatArray> pointsByKey Interleaved (x, y) points per object.
 * @param Set<Key> keys The objects to measure.
 * @return PointExtent? The box, or null when no listed object has a whole point.
 */
internal fun <Key> listedPointsExtent(pointsByKey: Map<Key, FloatArray>, keys: Set<Key>): PointExtent? {
	var extent: PointExtent? = null

	for ((key, points) in pointsByKey) {
		if (key !in keys) {
			continue
		}

		val own = pointExtentOf(points) ?: continue
		extent = extent?.let { held -> PointExtent(minOf(held.minX, own.minX), minOf(held.minY, own.minY), maxOf(held.maxX, own.maxX), maxOf(held.maxY, own.maxY)) } ?: own
	}

	return extent
}