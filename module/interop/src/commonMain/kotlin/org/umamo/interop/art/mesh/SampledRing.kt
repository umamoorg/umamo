package org.umamo.interop.art.mesh

import org.umamo.geometry.polyline.douglasPeuckerKeypoints
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A densely traced closed ring and a sparse selection of points on it, chosen by arc length.
 *
 * The dense ring is the field's contour, vertex by vertex; the samples are arc positions along it, so
 * every sample lies exactly on the dense ring and every chord between two samples replaces a known
 * stretch of it.  That correspondence is what lets a chord be checked against the stretch it cuts
 * across, and refined by promoting a dense vertex from that stretch.  Sample coordinates are rounded
 * to Float, because Float is what a mesh stores and every check must see the stored values.
 *
 * @property DoubleArray dense The traced ring, x0, y0, x1, y1, ...; closed implicitly.
 */
internal class SampledRing(private val dense: DoubleArray) {
	/** The dense ring's vertex count. */
	private val denseCount: Int = dense.size / 2

	/** The arc length at each dense vertex; the extra last entry is the perimeter. */
	private val arcAt: DoubleArray = DoubleArray(denseCount + 1)

	/** The chosen arc positions, ascending, each in [0, perimeter). */
	private val samples = ArrayList<Double>()

	/** Chords split by [refine] so far. */
	var refinedChordCount: Int = 0
		private set

	init {
		for (vertex in 0 until denseCount) {
			arcAt[vertex + 1] = arcAt[vertex] + segmentLength(vertex)
		}
	}

	/** The dense ring's perimeter. */
	private val perimeter: Double
		get() = arcAt[denseCount]

	/** The number of chosen samples. */
	val sampleCount: Int
		get() = samples.size

	/**
	 * Chooses samples: keypoints at the ring's sharp turns, then even spacing between them, each span
	 * rounding the spacing to divide it evenly.  A ring too short for [minimumPoints] at that spacing
	 * is divided into exactly [minimumPoints] even pieces instead, with no keypoints.
	 *
	 * @param Double spacing       The target arc distance between samples.
	 * @param Double tolerance     The Douglas-Peucker tolerance for finding candidate corners.
	 * @param Int    minimumPoints The fewest samples the ring may have.
	 */
	fun sampleEvenly(spacing: Double, tolerance: Double, minimumPoints: Int) {
		samples.clear()
		refinedChordCount = 0

		if (perimeter / spacing < minimumPoints) {
			addUniform(0.0, perimeter, minimumPoints)

			return
		}

		val keypointArcs = sharpTurnArcs(tolerance, spacing)

		if (keypointArcs.isEmpty()) {
			addUniform(0.0, perimeter, maxOf(1, (perimeter / spacing).roundToInt()))
		} else {
			for (keypoint in keypointArcs.indices) {
				val start = keypointArcs[keypoint]
				val end = if (keypoint + 1 < keypointArcs.size) keypointArcs[keypoint + 1] else keypointArcs[0] + perimeter
				addUniform(start, end - start, maxOf(1, ((end - start) / spacing).roundToInt()))
			}
		}

		samples.sort()

		if (samples.size < minimumPoints) {
			samples.clear()
			addUniform(0.0, perimeter, minimumPoints)
		}
	}

	/**
	 * Splits every chord the acceptance check refuses, by promoting the dense vertex nearest the
	 * chord's arc midpoint to a sample, until every chord is accepted.  A chord with no dense vertex
	 * between its ends lies along one dense segment and is accepted as it stands.
	 *
	 * @param ChordCheck accepts Decides whether a chord may stand, given the stretch of dense ring it replaces.
	 */
	fun refine(accepts: ChordCheck) {
		var scanAgain = true

		while (scanAgain) {
			scanAgain = false
			var chord = 0

			while (chord < samples.size) {
				val startArc = samples[chord]
				val endArc = if (chord + 1 < samples.size) samples[chord + 1] else samples[0] + perimeter
				val between = denseVerticesBetween(startArc, endArc)

				if (between.isEmpty() || accepts.accepts(this, startArc, endArc, between)) {
					chord++
					continue
				}

				val midpoint = (startArc + endArc) / 2.0
				var promoted = between[0]

				for (candidate in between) {
					if (abs(unwrappedArc(candidate, startArc) - midpoint) < abs(unwrappedArc(promoted, startArc) - midpoint)) {
						promoted = candidate
					}
				}

				insertSample(arcAt[promoted])
				refinedChordCount++

				// The chord now ends at the promoted sample; re-check it from the same start.  A sample
				// promoted across the ring's start lands first in the list, which opens a NEW first chord
				// the scan has already passed - so the whole ring is scanned again afterwards.
				if (arcAt[promoted] < startArc) {
					chord = samples.size - 1
					scanAgain = true
				}
			}
		}
	}

	/**
	 * Replaces one chord with every dense vertex it skipped.
	 *
	 * @param Int chord The chord's index: it runs from sample [chord] to the next.
	 */
	fun densifyChord(chord: Int) {
		val startArc = samples[chord]
		val endArc = if (chord + 1 < samples.size) samples[chord + 1] else samples[0] + perimeter

		for (vertex in denseVerticesBetween(startArc, endArc)) {
			insertSample(arcAt[vertex])
		}
	}

	/** Replaces every chord with the dense ring itself. */
	fun densifyAll() {
		samples.clear()

		for (vertex in 0 until denseCount) {
			samples.add(arcAt[vertex])
		}
	}

	/**
	 * Drops the samples a predicate rejects (used to clear inner-ring points crowding a pin).
	 *
	 * @param (Double, Double) -> Boolean keep Whether to keep the sample at a point.
	 */
	fun retainSamples(keep: (Double, Double) -> Boolean) {
		samples.retainAll { arc -> keep(roundedX(arc), roundedY(arc)) }
	}

	/**
	 * The samples' coordinates, rounded to Float.
	 *
	 * @return DoubleArray x0, y0, x1, y1, ... in sample order.
	 */
	fun points(): DoubleArray {
		val points = DoubleArray(2 * samples.size)

		for ((position, arc) in samples.withIndex()) {
			points[2 * position] = roundedX(arc)
			points[2 * position + 1] = roundedY(arc)
		}

		return points
	}

	/**
	 * The x of the point at an arc position, rounded to Float.
	 *
	 * @param Double arc The arc position, [0, perimeter] (or one perimeter past, for a wrapped chord end).
	 * @return Double The rounded x.
	 */
	fun roundedX(arc: Double): Double = pointAt(arc, 0).toFloat().toDouble()

	/**
	 * The y of the point at an arc position, rounded to Float.
	 *
	 * @param Double arc The arc position.
	 * @return Double The rounded y.
	 */
	fun roundedY(arc: Double): Double = pointAt(arc, 1).toFloat().toDouble()

	/**
	 * The x of a dense vertex, rounded to Float.
	 *
	 * @param Int vertex The dense vertex.
	 * @return Double The rounded x.
	 */
	fun denseX(vertex: Int): Double = dense[2 * vertex].toFloat().toDouble()

	/**
	 * The y of a dense vertex, rounded to Float.
	 *
	 * @param Int vertex The dense vertex.
	 * @return Double The rounded y.
	 */
	fun denseY(vertex: Int): Double = dense[2 * vertex + 1].toFloat().toDouble()

	/**
	 * The arc positions of the dense ring's sharp turns: Douglas-Peucker candidates whose direction
	 * changes by more than 45 degrees, thinned so no two lie closer than a quarter of the spacing.
	 *
	 * @param Double tolerance The Douglas-Peucker tolerance.
	 * @param Double spacing   The sampling spacing.
	 * @return List<Double> The keypoint arcs, ascending.
	 */
	private fun sharpTurnArcs(tolerance: Double, spacing: Double): List<Double> {
		val candidates = douglasPeuckerKeypoints(dense, tolerance, closed = true)

		if (candidates.size < 3) {
			return emptyList()
		}

		val sharp = ArrayList<Double>()

		for (position in candidates.indices) {
			val previous = candidates[(position + candidates.size - 1) % candidates.size]
			val current = candidates[position]
			val next = candidates[(position + 1) % candidates.size]
			val inX = dense[2 * current] - dense[2 * previous]
			val inY = dense[2 * current + 1] - dense[2 * previous + 1]
			val outX = dense[2 * next] - dense[2 * current]
			val outY = dense[2 * next + 1] - dense[2 * current + 1]
			val dot = inX * outX + inY * outY
			val lengths = sqrt(inX * inX + inY * inY) * sqrt(outX * outX + outY * outY)

			if (dot < COSINE_OF_SHARP_TURN * lengths) {
				sharp.add(arcAt[current])
			}
		}

		val thinned = ArrayList<Double>()

		for (arc in sharp) {
			if (thinned.isEmpty() || arc - thinned.last() >= spacing / 4.0) {
				thinned.add(arc)
			}
		}

		// The last keypoint must also keep its distance from the first, across the ring's start.
		while (thinned.size > 1 && thinned[0] + perimeter - thinned.last() < spacing / 4.0) {
			thinned.removeAt(thinned.size - 1)
		}

		return thinned
	}

	/**
	 * Adds evenly spaced samples along a stretch: its start and every interior division.
	 *
	 * @param Double start      The stretch's starting arc (may run past the perimeter).
	 * @param Double length     The stretch's arc length.
	 * @param Int    pieceCount The number of even pieces.
	 */
	private fun addUniform(start: Double, length: Double, pieceCount: Int) {
		for (piece in 0 until pieceCount) {
			val arc = start + length * piece / pieceCount
			samples.add(if (arc >= perimeter) arc - perimeter else arc)
		}
	}

	/**
	 * Inserts one sample, keeping the samples ascending; an existing sample at the same arc is kept as is.
	 *
	 * @param Double arc The arc position, [0, perimeter).
	 */
	private fun insertSample(arc: Double) {
		var position = samples.binarySearch(arc)

		if (position >= 0) {
			return
		}

		position = -position - 1
		samples.add(position, arc)
	}

	/**
	 * The dense vertices strictly between two arc positions, in ring order.
	 *
	 * @param Double startArc The stretch's start.
	 * @param Double endArc   The stretch's end, after the start (up to one perimeter past it).
	 * @return List<Int> The dense vertex indices.
	 */
	private fun denseVerticesBetween(startArc: Double, endArc: Double): List<Int> {
		val vertices = ArrayList<Int>()
		var vertex = firstVertexAfter(startArc)
		var lap = 0.0

		// Past the last vertex, the next one is vertex 0 on the following lap.
		if (vertex == denseCount) {
			vertex = 0
			lap = perimeter
		}

		while (vertices.size < denseCount) {
			val arc = arcAt[vertex] + lap

			if (arc >= endArc) {
				break
			}

			if (arc > startArc) {
				vertices.add(vertex)
			}

			vertex++

			if (vertex == denseCount) {
				vertex = 0
				lap += perimeter
			}
		}

		return vertices
	}

	/**
	 * The first dense vertex whose arc position lies strictly after an arc.
	 *
	 * @param Double arc The arc position, [0, perimeter).
	 * @return Int The vertex index, or the vertex count when the arc lies past the last vertex.
	 */
	private fun firstVertexAfter(arc: Double): Int {
		var low = 0
		var high = denseCount

		while (low < high) {
			val middle = (low + high) ushr 1

			if (arcAt[middle] <= arc) {
				low = middle + 1
			} else {
				high = middle
			}
		}

		return low
	}

	/**
	 * A dense vertex's arc position, unwrapped to lie after a chord's start.
	 *
	 * @param Int    vertex   The dense vertex.
	 * @param Double startArc The chord's start.
	 * @return Double The arc, plus one perimeter when the vertex lies across the ring's start.
	 */
	private fun unwrappedArc(vertex: Int, startArc: Double): Double = if (arcAt[vertex] > startArc) arcAt[vertex] else arcAt[vertex] + perimeter

	/**
	 * One coordinate of the point at an arc position on the dense ring.
	 *
	 * @param Double arc       The arc position (wrapped into the ring).
	 * @param Int    component 0 for x, 1 for y.
	 * @return Double The coordinate.
	 */
	private fun pointAt(arc: Double, component: Int): Double {
		val wrapped = if (arc >= perimeter) arc - perimeter else arc
		var low = 0
		var high = denseCount - 1

		// The last dense vertex whose arc is at or before the position.
		while (low < high) {
			val middle = (low + high + 1) ushr 1

			if (arcAt[middle] <= wrapped) {
				low = middle
			} else {
				high = middle - 1
			}
		}

		val next = (low + 1) % denseCount
		val length = arcAt[low + 1] - arcAt[low]
		val fraction = if (length > 0.0) ((wrapped - arcAt[low]) / length).coerceIn(0.0, 1.0) else 0.0
		val start = dense[2 * low + component]

		return start + fraction * (dense[2 * next + component] - start)
	}

	/**
	 * The length of the dense segment leaving a vertex.
	 *
	 * @param Int vertex The dense vertex.
	 * @return Double The segment length.
	 */
	private fun segmentLength(vertex: Int): Double {
		val next = (vertex + 1) % denseCount
		val deltaX = dense[2 * next] - dense[2 * vertex]
		val deltaY = dense[2 * next + 1] - dense[2 * vertex + 1]

		return sqrt(deltaX * deltaX + deltaY * deltaY)
	}

	private companion object {
		/** cos(45 degrees): a turn sharper than this becomes a keypoint. */
		val COSINE_OF_SHARP_TURN: Double = sqrt(0.5)
	}
}

/**
 * Thins a traced ring: drops every vertex closer than a separation to the last vertex kept (and,
 * at the end, to the first).  Where the field sits almost exactly on the level at a grid node, the
 * crossings on that node's edges all land beside it, a cluster of vertices hundredths of a pixel
 * apart; promoted as samples they would make near-zero-area triangles.  Each dropped vertex lies
 * within the separation of a kept one, so the thinned ring stays within that distance of the traced
 * one - the outline's level carries the slack to pay for it.
 *
 * @param DoubleArray ring       The traced ring, x0, y0, ...; closed implicitly.
 * @param Double      separation The least distance kept between consecutive vertices.
 * @return DoubleArray The thinned ring (the input itself when it would fall below three vertices).
 */
internal fun thinTracedRing(ring: DoubleArray, separation: Double): DoubleArray {
	val vertexCount = ring.size / 2
	val separationSquared = separation * separation
	val kept = ArrayList<Int>()

	for (vertex in 0 until vertexCount) {
		if (kept.isEmpty() || squaredDistance(ring, vertex, kept.last()) >= separationSquared) {
			kept.add(vertex)
		}
	}

	while (kept.size > 1 && squaredDistance(ring, kept.last(), kept.first()) < separationSquared) {
		kept.removeAt(kept.size - 1)
	}

	if (kept.size < 3 || kept.size == vertexCount) {
		return ring
	}

	val thinned = DoubleArray(2 * kept.size)

	for ((position, vertex) in kept.withIndex()) {
		thinned[2 * position] = ring[2 * vertex]
		thinned[2 * position + 1] = ring[2 * vertex + 1]
	}

	return thinned
}

/**
 * The squared distance between two vertices of a ring.
 *
 * @param DoubleArray ring   The ring.
 * @param Int         first  One vertex.
 * @param Int         second The other.
 * @return Double The squared distance.
 */
private fun squaredDistance(ring: DoubleArray, first: Int, second: Int): Double {
	val deltaX = ring[2 * first] - ring[2 * second]
	val deltaY = ring[2 * first + 1] - ring[2 * second + 1]

	return deltaX * deltaX + deltaY * deltaY
}

/** Decides whether one chord of a [SampledRing] may stand. */
internal fun interface ChordCheck {
	/**
	 * Whether a chord may stand.
	 *
	 * @param SampledRing ring     The ring.
	 * @param Double      startArc The chord's start arc.
	 * @param Double      endArc   The chord's end arc (up to one perimeter past the start).
	 * @param List<Int>   between  The dense vertices the chord skips, in ring order (never empty).
	 * @return Boolean True when the chord may stand.
	 */
	fun accepts(ring: SampledRing, startArc: Double, endArc: Double, between: List<Int>): Boolean
}