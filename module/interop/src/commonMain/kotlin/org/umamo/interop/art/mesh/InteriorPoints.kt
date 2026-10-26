package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaField
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * The points inside the art: inner rings a margin in from its edge, and a hexagonal lattice filling
 * the rest.  Neither carries any coverage duty - the outline does - so they only need to sit inside
 * the art and keep their distance from the outline, from each other, and from pins.
 */

/** sqrt(3) / 2: a hexagonal lattice's row height per unit spacing. */
private val HEXAGON_ROW_HEIGHT: Double = sqrt(3.0) / 2.0

/**
 * Samples the inner rings: keypoints at sharp turns, even spacing between, and any chord whose
 * midpoint strays more than halfway back toward the art's edge refined from the traced ring (so a
 * chord cannot cut across a notch).  Points within half a spacing of a pin are dropped; a ring left
 * with fewer than three points is dropped whole.
 *
 * Only a ring with room for the spacing is kept: at least three spacings around, and on average at
 * least a quarter spacing wide (its area over its perimeter, which is about half a thin loop's
 * width).  Where the art is barely wider than twice the inner margin, the inner contour breaks into
 * tiny loops or a thin sliver of a loop; forcing points onto those packs them together into
 * near-degenerate triangles, so such a stretch is meshed from its outline alone.
 *
 * @param List<DoubleArray> denseRings  The field's contours at minus the inner margin.
 * @param AlphaField        field       The field.
 * @param Double            spacing     The interior spacing.
 * @param Double            innerMargin The inner margin.
 * @param Double            tolerance   The Douglas-Peucker tolerance for corners.
 * @param DoubleArray       pins        The pins, Float-rounded, x0, y0, ...
 * @return List<DoubleArray> Each kept ring's points, Float-rounded.
 */
internal fun innerRingPoints(denseRings: List<DoubleArray>, field: AlphaField, spacing: Double, innerMargin: Double, tolerance: Double, pins: DoubleArray): List<DoubleArray> {
	val rings = ArrayList<DoubleArray>()
	val stayInside =
		ChordCheck { ring, startArc, endArc, _ ->
			val middleX = (ring.roundedX(startArc) + ring.roundedX(endArc)) / 2.0
			val middleY = (ring.roundedY(startArc) + ring.roundedY(endArc)) / 2.0

			field.valueAt(middleX, middleY) <= -innerMargin / 2.0
		}

	for (dense in denseRings) {
		if (!hasRoomForSpacing(dense, spacing)) {
			continue
		}

		val ring = SampledRing(dense)
		ring.sampleEvenly(spacing, tolerance, MINIMUM_INNER_RING_POINTS)
		ring.refine(stayInside)
		ring.retainSamples { x, y -> !nearAnyPin(x, y, pins, spacing / 2.0) }

		if (ring.sampleCount >= MINIMUM_INNER_RING_POINTS) {
			rings.add(ring.points())
		}
	}

	return rings
}

/**
 * The hexagonal lattice over the field's grid, anchored at the raster origin, kept where the field is
 * at most minus (inner margin + half a spacing) - well inside the art, clear of the inner ring - and
 * away from pins.  Rows alternate their half-spacing offset by row parity (floorMod, so negative rows
 * alternate too) and every position is a product, never an accumulated sum, so the lattice is the
 * same on every platform.
 *
 * @param AlphaField  field       The field.
 * @param Double      spacing     The lattice spacing.
 * @param Double      innerMargin The inner margin.
 * @param DoubleArray pins        The pins, Float-rounded.
 * @return DoubleArray The lattice points, Float-rounded, x0, y0, ...
 */
internal fun latticePoints(field: AlphaField, spacing: Double, innerMargin: Double, pins: DoubleArray): DoubleArray {
	val rowHeight = spacing * HEXAGON_ROW_HEIGHT
	val depth = -(innerMargin + spacing / 2.0)
	val left = field.gridLeft.toDouble()
	val top = field.gridTop.toDouble()
	val right = left + field.columns * field.cellSize
	val bottom = top + field.rows * field.cellSize
	val points = ArrayList<Double>()

	for (row in ceil(top / rowHeight).toLong()..floor(bottom / rowHeight).toLong()) {
		val offset = if (row.mod(2L) == 1L) spacing / 2.0 else 0.0
		val y = row * rowHeight

		for (column in ceil((left - offset) / spacing).toLong()..floor((right - offset) / spacing).toLong()) {
			val x = column * spacing + offset

			if (field.valueAt(x, y) <= depth && !nearAnyPin(x, y, pins, spacing / 2.0)) {
				points.add(x.toFloat().toDouble())
				points.add(y.toFloat().toDouble())
			}
		}
	}

	return points.toDoubleArray()
}

/**
 * Whether a traced ring is long and wide enough to hold points at a spacing.
 *
 * @param DoubleArray ring    The traced ring.
 * @param Double      spacing The spacing.
 * @return Boolean True when the ring is at least three spacings around and a quarter spacing wide on average.
 */
private fun hasRoomForSpacing(ring: DoubleArray, spacing: Double): Boolean {
	val measure = RingMeasure.of(ring)

	return measure.perimeter >= MINIMUM_INNER_RING_POINTS * spacing && abs(measure.doubledArea) / 2.0 / measure.perimeter >= spacing / 4.0
}

/**
 * Whether a point lies within a distance of any pin.
 *
 * @param Double      x        The point's x.
 * @param Double      y        The point's y.
 * @param DoubleArray pins     The pins.
 * @param Double      distance The distance.
 * @return Boolean True when some pin is that close.
 */
internal fun nearAnyPin(x: Double, y: Double, pins: DoubleArray, distance: Double): Boolean {
	val distanceSquared = distance * distance

	for (pin in 0 until pins.size / 2) {
		val deltaX = pins[2 * pin] - x
		val deltaY = pins[2 * pin + 1] - y

		if (deltaX * deltaX + deltaY * deltaY < distanceSquared) {
			return true
		}
	}

	return false
}

/**
 * One inner polyline handed to the triangulation as a neutral segment: a whole inner ring, or what is
 * left of one where a strand cut it.
 *
 * @property DoubleArray points The points, Float-rounded, x0, y0, ...
 * @property Boolean     closed Whether the last point joins back to the first.
 */
internal class InnerPolyline(val points: DoubleArray, val closed: Boolean) {
	/** The number of points. */
	val pointCount: Int
		get() = points.size / 2
}

/**
 * Takes the inner rings out of the strands: a ring with no point in a strand stays whole; one that
 * reaches into a strand loses the points there and is handed over as the open stretches between,
 * each at least two points long.  A strand's rungs and centerline already span it, so an inner ring's
 * points inside one would only crowd them.
 *
 * @param List<DoubleArray> rings   The inner rings' points.
 * @param StrandPlacement   strands Where the strands lie.
 * @return List<InnerPolyline> The polylines.
 */
internal fun pruneInsideStrands(rings: List<DoubleArray>, strands: StrandPlacement): List<InnerPolyline> {
	val polylines = ArrayList<InnerPolyline>()

	for (ring in rings) {
		val count = ring.size / 2
		val inside = BooleanArray(count) { point -> strands.contains(ring[2 * point], ring[2 * point + 1]) }
		val firstInside = inside.indexOfFirst { it }

		if (firstInside < 0) {
			polylines.add(InnerPolyline(ring, closed = true))

			continue
		}

		for (stretch in runsOf(inside, wanted = false, circular = true)) {
			if (stretch.size >= MINIMUM_STRETCH_POINTS) {
				polylines.add(InnerPolyline(DoubleArray(2 * stretch.size) { component -> ring[2 * stretch[component / 2] + component % 2] }, closed = false))
			}
		}
	}

	return polylines
}

/** An open stretch of inner ring with fewer points than this holds no edge. */
private const val MINIMUM_STRETCH_POINTS: Int = 2

/** An inner ring with fewer points than this cannot enclose anything. */
private const val MINIMUM_INNER_RING_POINTS: Int = 3