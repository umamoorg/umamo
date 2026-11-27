package org.umamo.format.art

/*
 * Exact squared Euclidean distance transform over a grid, after Felzenszwalb and Huttenlocher,
 * "Distance Transforms of Sampled Functions" (Theory of Computing 8(19), 2012): a 1-D pass along
 * every row, then along every column, each computing the lower envelope of parabolas rooted at the
 * feature cells.  Linear time, and exact - every distance is an integer, held exactly in a Double.
 */

/** Stands in for "no feature on this line"; finite so envelope intersections never turn into NaN. */
private const val FAR: Double = 1e20

/** Marks a cell no feature reaches in the output; larger than any real squared grid distance. */
internal const val UNREACHED: Int = Int.MAX_VALUE

/**
 * Writes, for every cell, the squared distance in cell units to the nearest FEATURE cell - a cell
 * whose flag equals [featureFlag].  Distances are measured between cell centers.
 *
 * @param BooleanArray cells       The per-cell flags, row-major.
 * @param Boolean      featureFlag The flag value that marks a feature cell.
 * @param Int          columns     The grid width.
 * @param Int          rows        The grid height.
 * @param IntArray     out         Receives the squared distances, [UNREACHED] where no feature exists at all.
 */
internal fun squaredDistanceToFeatures(cells: BooleanArray, featureFlag: Boolean, columns: Int, rows: Int, out: IntArray) {
	val lineLength = maxOf(columns, rows)
	val input = DoubleArray(lineLength)
	val output = DoubleArray(lineLength)
	val envelope = IntArray(lineLength)
	val boundaries = DoubleArray(lineLength + 1)

	for (row in 0 until rows) {
		for (column in 0 until columns) {
			input[column] = if (cells[row * columns + column] == featureFlag) 0.0 else FAR
		}

		lowerEnvelope(input, columns, output, envelope, boundaries)

		for (column in 0 until columns) {
			out[row * columns + column] = toStored(output[column])
		}
	}

	for (column in 0 until columns) {
		for (row in 0 until rows) {
			val stored = out[row * columns + column]
			input[row] = if (stored == UNREACHED) FAR else stored.toDouble()
		}

		lowerEnvelope(input, rows, output, envelope, boundaries)

		for (row in 0 until rows) {
			out[row * columns + column] = toStored(output[row])
		}
	}
}

/**
 * One 1-D pass: for every position q, the minimum over p of (q - p)^2 + values[p].
 *
 * @param DoubleArray values     The sampled function, [FAR] where nothing is rooted.
 * @param Int         length     The number of samples in use.
 * @param DoubleArray out        Receives the transform.
 * @param IntArray    envelope   Scratch: the roots of the parabolas on the lower envelope.
 * @param DoubleArray boundaries Scratch: where each envelope parabola takes over (length + 1 slots).
 */
private fun lowerEnvelope(values: DoubleArray, length: Int, out: DoubleArray, envelope: IntArray, boundaries: DoubleArray) {
	var top = 0
	envelope[0] = 0
	boundaries[0] = Double.NEGATIVE_INFINITY
	boundaries[1] = Double.POSITIVE_INFINITY

	for (position in 1 until length) {
		var crossing = parabolaCrossing(values, position, envelope[top])

		while (crossing <= boundaries[top]) {
			top--
			crossing = parabolaCrossing(values, position, envelope[top])
		}

		top++
		envelope[top] = position
		boundaries[top] = crossing
		boundaries[top + 1] = Double.POSITIVE_INFINITY
	}

	var segment = 0

	for (position in 0 until length) {
		while (boundaries[segment + 1] < position) {
			segment++
		}

		val root = envelope[segment]
		val offset = (position - root).toDouble()
		out[position] = offset * offset + values[root]
	}
}

/**
 * Where the parabola rooted at a new position overtakes the one rooted at an earlier position.
 *
 * @param DoubleArray values  The sampled function.
 * @param Int         newer   The later root.
 * @param Int         earlier The earlier root.
 * @return Double The crossing abscissa.
 */
private fun parabolaCrossing(values: DoubleArray, newer: Int, earlier: Int): Double {
	val newerSquare = newer.toDouble() * newer
	val earlierSquare = earlier.toDouble() * earlier

	return ((values[newer] + newerSquare) - (values[earlier] + earlierSquare)) / (2.0 * (newer - earlier))
}

/**
 * Stores one transform value as an Int, mapping anything a [FAR] root produced to [UNREACHED].
 *
 * @param Double value The squared distance.
 * @return Int The stored value.
 */
private fun toStored(value: Double): Int = if (value >= FAR / 2) UNREACHED else value.toInt()