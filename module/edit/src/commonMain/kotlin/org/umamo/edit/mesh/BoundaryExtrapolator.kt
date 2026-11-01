package org.umamo.edit.mesh

import org.umamo.geometry.packVertexPair

/**
 * How an old mesh's geometry continues past its own boundary, for placing the vertices of a
 * regenerated mesh that fall outside it.
 *
 * A vertex outside the old mesh is anchored at the nearest point of the boundary, which lies on a
 * boundary FEATURE (one old vertex, or one old edge), and moves away from it by a linear map: the
 * 2x2 linear part of a least-squares affine fit from the old art-frame points to the old geometry over
 * the old vertices nearest that feature.  The fit spans a neighbourhood rather than one triangle, so
 * the thin sliver triangles real meshes carry at their edges cannot blow it up, and it is exact
 * wherever the old geometry is an affine map of the art.
 *
 * One fit per feature, kept for the next vertex anchored there, so every vertex along one boundary
 * edge moves by the same map.  Ties between equally near vertices go to the lower index, so the
 * result is a function of the old mesh alone.
 *
 * @param FloatArray  rasterPoints The old vertices in the tile's art pixels, x0, y0, ...
 * @param FloatArray  canvas       The old canvas positions.
 * @param FloatArray? local        The old keyform-space base, or null when it is the canvas array itself.
 * @param IntArray    candidates   The old vertices some triangle uses, ascending - the only ones a fit may lean on.
 */
internal class BoundaryExtrapolator(
	private val rasterPoints: FloatArray,
	private val canvas: FloatArray,
	private val local: FloatArray?,
	private val candidates: IntArray,
) {
	/**
	 * The linear maps a feature's neighbourhood fits, art pixels to geometry, each row-major
	 * (dxdu, dxdv, dydu, dydv).
	 *
	 * @property DoubleArray  canvasJacobian The map onto the canvas positions.
	 * @property DoubleArray? localJacobian  The map onto the keyform-space base, or null when that is the canvas.
	 */
	class Fit(val canvasJacobian: DoubleArray, val localJacobian: DoubleArray?)

	// Fits by feature, the two corner indices packed low-high; a lookup table only, never iterated.
	private val fitsByFeature = HashMap<Long, Fit?>()

	/**
	 * The fit for a boundary feature: one old vertex (both corners the same) or one old edge.
	 *
	 * @param Int firstCorner  One end of the feature.
	 * @param Int secondCorner The other end; equal to the first for a vertex.
	 * @return Fit? The fit, or null when even the whole mesh is too thin to fit (under a pixel across).
	 */
	fun fitFor(firstCorner: Int, secondCorner: Int): Fit? {
		val low = minOf(firstCorner, secondCorner)
		val high = maxOf(firstCorner, secondCorner)
		val key = packVertexPair(low, high)

		if (key in fitsByFeature) {
			return fitsByFeature[key]
		}

		val fit = computeFit(low, high)
		fitsByFeature[key] = fit

		return fit
	}

	/**
	 * Fits the neighbourhood of one feature, widening it until it spreads enough.
	 *
	 * @param Int low  The feature's lower corner.
	 * @param Int high The feature's higher corner.
	 * @return Fit? The fit, or null when no neighbourhood spreads enough.
	 */
	private fun computeFit(low: Int, high: Int): Fit? {
		val centerX = (rasterPoints[2 * low].toDouble() + rasterPoints[2 * high]) / 2.0
		val centerY = (rasterPoints[2 * low + 1].toDouble() + rasterPoints[2 * high + 1]) / 2.0
		val nearestFirst = candidatesByDistance(centerX, centerY)
		var neighbourCount = minOf(INITIAL_NEIGHBOURS, nearestFirst.size)

		while (true) {
			val fit = fitOver(nearestFirst, neighbourCount)

			if (fit != null) {
				return fit
			}

			if (neighbourCount >= nearestFirst.size) {
				return null
			}

			neighbourCount = minOf(neighbourCount * 2, nearestFirst.size)
		}
	}

	/**
	 * The candidate vertices ordered nearest first from a point, ties to the lower index.
	 *
	 * @param Double centerX The point's x.
	 * @param Double centerY The point's y.
	 * @return IntArray The candidates, nearest first.
	 */
	private fun candidatesByDistance(centerX: Double, centerY: Double): IntArray {
		val distances =
			DoubleArray(candidates.size) { position ->
				val vertex = candidates[position]
				val deltaX = rasterPoints[2 * vertex] - centerX
				val deltaY = rasterPoints[2 * vertex + 1] - centerY

				deltaX * deltaX + deltaY * deltaY
			}
		// Candidates are ascending, and the sort is stable, so equal distances keep the lower index first.
		val order = candidates.indices.sortedBy { position -> distances[position] }

		return IntArray(order.size) { rank -> candidates[order[rank]] }
	}

	/**
	 * The least-squares fit over the first vertices of a nearest-first list, when they spread enough.
	 *
	 * @param IntArray nearestFirst   The candidates, nearest first.
	 * @param Int      neighbourCount How many of them to fit over.
	 * @return Fit? The fit, or null when the neighbourhood is under a pixel across in some direction.
	 */
	private fun fitOver(nearestFirst: IntArray, neighbourCount: Int): Fit? {
		var meanU = 0.0
		var meanV = 0.0

		for (rank in 0 until neighbourCount) {
			meanU += rasterPoints[2 * nearestFirst[rank]]
			meanV += rasterPoints[2 * nearestFirst[rank] + 1]
		}

		meanU /= neighbourCount
		meanV /= neighbourCount
		var spreadUu = 0.0
		var spreadUv = 0.0
		var spreadVv = 0.0

		for (rank in 0 until neighbourCount) {
			val offsetU = rasterPoints[2 * nearestFirst[rank]] - meanU
			val offsetV = rasterPoints[2 * nearestFirst[rank] + 1] - meanV
			spreadUu += offsetU * offsetU
			spreadUv += offsetU * offsetV
			spreadVv += offsetV * offsetV
		}

		// The covariance's smaller eigenvalue is at least MINIMUM_SPREAD_SQUARED exactly when both of
		// these hold (for a symmetric 2x2: (a - t)(b - t) >= 0 with a + b >= 2t), with no square root.
		val covarianceUu = spreadUu / neighbourCount
		val covarianceUv = spreadUv / neighbourCount
		val covarianceVv = spreadVv / neighbourCount
		val trace = covarianceUu + covarianceVv
		val determinant = covarianceUu * covarianceVv - covarianceUv * covarianceUv
		val spreadsEnough = trace >= 2.0 * MINIMUM_SPREAD_SQUARED && determinant - MINIMUM_SPREAD_SQUARED * trace + MINIMUM_SPREAD_SQUARED * MINIMUM_SPREAD_SQUARED >= 0.0

		if (!spreadsEnough) {
			return null
		}

		val spreadDeterminant = spreadUu * spreadVv - spreadUv * spreadUv
		// The inverse of the spread matrix [[uu, uv], [uv, vv]].
		val inverseUu = spreadVv / spreadDeterminant
		val inverseUv = -spreadUv / spreadDeterminant
		val inverseVv = spreadUu / spreadDeterminant
		val canvasJacobian = jacobianOf(canvas, nearestFirst, neighbourCount, meanU, meanV, inverseUu, inverseUv, inverseVv)
		val localJacobian = local?.let { values -> jacobianOf(values, nearestFirst, neighbourCount, meanU, meanV, inverseUu, inverseUv, inverseVv) }

		return Fit(canvasJacobian, localJacobian)
	}

	/**
	 * The linear part of the least-squares affine map from the neighbourhood's art points to one
	 * geometry array: the cross-covariance times the inverse spread.
	 *
	 * @param FloatArray values         The geometry, x0, y0, ...
	 * @param IntArray   nearestFirst   The candidates, nearest first.
	 * @param Int        neighbourCount How many of them to fit over.
	 * @param Double     meanU          The neighbourhood's mean art x.
	 * @param Double     meanV          The neighbourhood's mean art y.
	 * @param Double     inverseUu      The inverse spread's first diagonal entry.
	 * @param Double     inverseUv      Its off-diagonal entry.
	 * @param Double     inverseVv      Its second diagonal entry.
	 * @return DoubleArray The map, row-major (dxdu, dxdv, dydu, dydv).
	 */
	private fun jacobianOf(
		values: FloatArray,
		nearestFirst: IntArray,
		neighbourCount: Int,
		meanU: Double,
		meanV: Double,
		inverseUu: Double,
		inverseUv: Double,
		inverseVv: Double,
	): DoubleArray {
		var meanX = 0.0
		var meanY = 0.0

		for (rank in 0 until neighbourCount) {
			meanX += values[2 * nearestFirst[rank]]
			meanY += values[2 * nearestFirst[rank] + 1]
		}

		meanX /= neighbourCount
		meanY /= neighbourCount
		var crossXu = 0.0
		var crossXv = 0.0
		var crossYu = 0.0
		var crossYv = 0.0

		for (rank in 0 until neighbourCount) {
			val vertex = nearestFirst[rank]
			val offsetU = rasterPoints[2 * vertex] - meanU
			val offsetV = rasterPoints[2 * vertex + 1] - meanV
			val offsetX = values[2 * vertex] - meanX
			val offsetY = values[2 * vertex + 1] - meanY
			crossXu += offsetX * offsetU
			crossXv += offsetX * offsetV
			crossYu += offsetY * offsetU
			crossYv += offsetY * offsetV
		}

		return doubleArrayOf(
			crossXu * inverseUu + crossXv * inverseUv,
			crossXu * inverseUv + crossXv * inverseVv,
			crossYu * inverseUu + crossYv * inverseUv,
			crossYu * inverseUv + crossYv * inverseVv,
		)
	}

	private companion object {
		/** The neighbourhood a fit starts from. */
		const val INITIAL_NEIGHBOURS: Int = 8

		/** The least variance, in square art pixels, a neighbourhood must have in every direction. */
		const val MINIMUM_SPREAD_SQUARED: Double = 1.0
	}
}