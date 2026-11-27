package org.umamo.geometry.polyline

import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Even resampling of a polyline by arc length, with pinned keypoints.
 *
 * The polyline is cut at its keypoints into spans; each span of length L gets n = max(1, round(L /
 * interval)) equal pieces, so its interior samples sit at L * k / n along it.  Keypoints are copied
 * exactly - a corner the caller pinned stays a vertex - and each sample is measured from its span's
 * start rather than accumulated from the previous sample, so spacing does not drift.
 */

/**
 * A resampled polyline.
 *
 * A plain class: both fields are arrays.
 *
 * @property DoubleArray points                The resampled points, x0, y0, x1, y1, ...; a closed ring does not repeat its first point at the end.
 * @property IntArray    keypointOutputIndices Where each keypoint landed in [points] (as a point index), in keypoint order.
 */
public class ResampledPolyline(
	public val points: DoubleArray,
	public val keypointOutputIndices: IntArray,
) {
	/** The number of resampled points. */
	public val pointCount: Int
		get() = points.size / 2
}

/**
 * Resamples a closed ring evenly by arc length between its keypoints.
 *
 * @param DoubleArray points          The ring's vertices, x0, y0, ...; the last joins the first.
 * @param IntArray    keypointIndices Vertex indices to keep exactly, strictly ascending; empty keeps vertex 0 only.
 * @param Double      interval        The target spacing (positive); each span rounds it to divide evenly.
 * @return ResampledPolyline The resampled ring, keypoints included exactly.
 */
public fun resampleClosedRing(points: DoubleArray, keypointIndices: IntArray, interval: Double): ResampledPolyline {
	val vertexCount = validatedVertexCount(points, keypointIndices, interval)

	if (vertexCount == 0) {
		return ResampledPolyline(DoubleArray(0), IntArray(0))
	}

	val keypoints = if (keypointIndices.isEmpty()) intArrayOf(0) else keypointIndices
	val output = DoubleList()
	val keypointOutput = IntArray(keypoints.size)

	for (keypointPosition in keypoints.indices) {
		val spanStart = keypoints[keypointPosition]
		// The last span wraps around to the first keypoint; a lone keypoint's span is the whole ring.
		val nextKeypoint = keypoints[(keypointPosition + 1) % keypoints.size]
		val spanVertexCount = if (nextKeypoint > spanStart) nextKeypoint - spanStart else nextKeypoint + vertexCount - spanStart
		keypointOutput[keypointPosition] = output.pointCount
		appendSpan(points, vertexCount, spanStart, spanVertexCount, interval, output)
	}

	return ResampledPolyline(output.toArray(), keypointOutput)
}

/**
 * Resamples an open polyline evenly by arc length between its keypoints.  Its first and last vertices
 * are always keypoints.
 *
 * @param DoubleArray points          The polyline's vertices, x0, y0, ...
 * @param IntArray    keypointIndices Further vertex indices to keep exactly, strictly ascending.
 * @param Double      interval        The target spacing (positive); each span rounds it to divide evenly.
 * @return ResampledPolyline The resampled polyline, keypoints (ends included) placed exactly; keypointOutputIndices lists the ends too.
 */
public fun resamplePolyline(points: DoubleArray, keypointIndices: IntArray, interval: Double): ResampledPolyline {
	val vertexCount = validatedVertexCount(points, keypointIndices, interval)

	if (vertexCount == 0) {
		return ResampledPolyline(DoubleArray(0), IntArray(0))
	}

	val keypoints = withEnds(keypointIndices, vertexCount)
	val output = DoubleList()
	val keypointOutput = IntArray(keypoints.size)

	for (keypointPosition in 0 until keypoints.size - 1) {
		keypointOutput[keypointPosition] = output.pointCount
		appendSpan(points, vertexCount, keypoints[keypointPosition], keypoints[keypointPosition + 1] - keypoints[keypointPosition], interval, output)
	}

	keypointOutput[keypoints.size - 1] = output.pointCount
	val lastVertex = vertexCount - 1
	output.add(points[2 * lastVertex], points[2 * lastVertex + 1])

	return ResampledPolyline(output.toArray(), keypointOutput)
}

/**
 * Validates a resampling request.
 *
 * @param DoubleArray points          The vertices.
 * @param IntArray    keypointIndices The keypoints.
 * @param Double      interval        The spacing.
 * @return Int The number of vertices.
 */
private fun validatedVertexCount(points: DoubleArray, keypointIndices: IntArray, interval: Double): Int {
	require(points.size % 2 == 0) { "points must hold x, y pairs, but has ${points.size} components" }
	require(interval > 0.0 && interval.isFinite()) { "interval must be positive and finite: $interval" }

	for (componentIndex in points.indices) {
		require(points[componentIndex].isFinite()) { "point component $componentIndex is not finite: ${points[componentIndex]}" }
	}

	val vertexCount = points.size / 2

	for (position in keypointIndices.indices) {
		require(keypointIndices[position] in 0 until vertexCount) { "keypoint $position is ${keypointIndices[position]}, outside 0 until $vertexCount" }
		require(position == 0 || keypointIndices[position] > keypointIndices[position - 1]) { "keypoints must be strictly ascending, but $position is ${keypointIndices[position]}" }
	}

	return vertexCount
}

/**
 * An open polyline's keypoints with its two ends added.
 *
 * @param IntArray keypointIndices The caller's keypoints, strictly ascending.
 * @param Int      vertexCount     The number of vertices.
 * @return IntArray The keypoints, starting at 0 and ending at the last vertex (one entry when there is a single vertex).
 */
private fun withEnds(keypointIndices: IntArray, vertexCount: Int): IntArray {
	val keypoints = ArrayList<Int>()
	keypoints.add(0)

	for (keypoint in keypointIndices) {
		if (keypoint != 0 && keypoint != vertexCount - 1) {
			keypoints.add(keypoint)
		}
	}

	if (vertexCount > 1) {
		keypoints.add(vertexCount - 1)
	}

	return keypoints.toIntArray()
}

/**
 * Appends one span's start and its interior samples (not its end, which starts the next span).
 *
 * @param DoubleArray points          The vertices.
 * @param Int         vertexCount     The number of vertices (indices wrap modulo it).
 * @param Int         spanStart       The span's first vertex.
 * @param Int         spanVertexCount The number of segments in the span.
 * @param Double      interval        The target spacing.
 * @param DoubleList  output          Receives the points.
 */
private fun appendSpan(points: DoubleArray, vertexCount: Int, spanStart: Int, spanVertexCount: Int, interval: Double, output: DoubleList) {
	output.add(points[2 * spanStart], points[2 * spanStart + 1])
	var spanLength = 0.0

	for (segment in 0 until spanVertexCount) {
		spanLength += segmentLength(points, vertexCount, spanStart + segment)
	}

	val pieceCount = maxOf(1, (spanLength / interval).roundToInt())
	var segment = 0
	var travelled = 0.0
	var currentLength = segmentLength(points, vertexCount, spanStart)

	for (sample in 1 until pieceCount) {
		val target = spanLength * sample / pieceCount

		// Advance to the segment holding the target; zero-length segments are stepped over.
		while (segment < spanVertexCount - 1 && (currentLength == 0.0 || travelled + currentLength < target)) {
			travelled += currentLength
			segment++
			currentLength = segmentLength(points, vertexCount, spanStart + segment)
		}

		val startVertex = (spanStart + segment) % vertexCount
		val endVertex = (spanStart + segment + 1) % vertexCount
		val fraction = if (currentLength > 0.0) ((target - travelled) / currentLength).coerceIn(0.0, 1.0) else 0.0
		val startX = points[2 * startVertex]
		val startY = points[2 * startVertex + 1]
		output.add(startX + fraction * (points[2 * endVertex] - startX), startY + fraction * (points[2 * endVertex + 1] - startY))
	}
}

/**
 * The length of the segment leaving a vertex.
 *
 * @param DoubleArray points      The vertices.
 * @param Int         vertexCount The number of vertices (indices wrap modulo it).
 * @param Int         from        The segment's first vertex, unwrapped.
 * @return Double The Euclidean length.
 */
private fun segmentLength(points: DoubleArray, vertexCount: Int, from: Int): Double {
	val startVertex = from % vertexCount
	val endVertex = (from + 1) % vertexCount
	val deltaX = points[2 * endVertex] - points[2 * startVertex]
	val deltaY = points[2 * endVertex + 1] - points[2 * startVertex + 1]

	return sqrt(deltaX * deltaX + deltaY * deltaY)
}

/** A growable list of x, y pairs; commonMain has no growable primitive double list. */
private class DoubleList {
	private var storage = DoubleArray(INITIAL_CAPACITY)
	private var size = 0

	/** The number of points held. */
	val pointCount: Int
		get() = size / 2

	/**
	 * Appends one point.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 */
	fun add(x: Double, y: Double) {
		if (size + 2 > storage.size) {
			storage = storage.copyOf(storage.size * 2)
		}

		storage[size] = x
		storage[size + 1] = y
		size += 2
	}

	/**
	 * The points as an exactly sized array.
	 *
	 * @return DoubleArray x0, y0, x1, y1, ...
	 */
	fun toArray(): DoubleArray = storage.copyOf(size)

	private companion object {
		const val INITIAL_CAPACITY = 64
	}
}