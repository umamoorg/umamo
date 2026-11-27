package org.umamo.format.art

/**
 * A bit-packed boolean mask over a raster's pixels, one bit per pixel, row-major from the top.
 *
 * Out-of-range coordinates read as transparent so the boundary tracer needs no padding ring
 * around the raster.  Packed into a LongArray rather than a BooleanArray because the tracer's
 * inputs can be large (an 8192^2 layer is 67M pixels) and two same-geometry bitsets are alive
 * at once during tracing.
 *
 * @param Int width Mask width in pixels.
 * @param Int height Mask height in pixels.
 * @param LongArray words The packed bits, bit (rowIndex * width + columnIndex).
 */
internal class AlphaMask(
	val width: Int,
	val height: Int,
	private val words: LongArray,
) {
	/**
	 * Whether the pixel at the given coordinates is opaque; out-of-range is transparent.
	 *
	 * @param Int columnIndex Pixel column, any value.
	 * @param Int rowIndex Pixel row, any value.
	 * @return Boolean True when in range and the mask bit is set.
	 */
	fun isOpaque(columnIndex: Int, rowIndex: Int): Boolean {
		if (columnIndex < 0 || columnIndex >= width || rowIndex < 0 || rowIndex >= height) {
			return false
		}

		val pixelIndex = rowIndex * width + columnIndex

		return (words[pixelIndex ushr 6] and (1L shl (pixelIndex and 63))) != 0L
	}
}

/**
 * A growable flat (x, y) point accumulator for contour tracing, doubling on demand.
 *
 * Exists because commonMain has no java.util growable primitives and boxing every lattice
 * corner into a List would swamp the trace loop on large contours.
 */
private class ContourPointBuffer {
	private var storage = IntArray(INITIAL_CAPACITY)
	private var size = 0

	/**
	 * Appends one point.
	 *
	 * @param Int x Lattice corner x.
	 * @param Int y Lattice corner y.
	 */
	fun add(x: Int, y: Int) {
		if (size + 2 > storage.size) {
			storage = storage.copyOf(storage.size * 2)
		}

		storage[size] = x
		storage[size + 1] = y
		size += 2
	}

	/**
	 * The accumulated points as a right-sized flat array.
	 *
	 * @return IntArray The points, in a copy sized to fit.
	 */
	fun toArray(): IntArray = storage.copyOf(size)

	private companion object {
		const val INITIAL_CAPACITY = 32
	}
}

/**
 * Twice the signed shoelace area of a closed flat-point ring (implicit closure, y-down).
 *
 * Under the tracer's opaque-on-the-right winding, outer contours are positive and holes are
 * negative, and the signed areas of all contours of one mask sum to exactly twice the opaque
 * pixel count — the tracer's core invariant, which tests assert.  Long because a full-canvas
 * ring at PSB scale overflows Int.
 *
 * @param IntArray points Flattened x0, y0, x1, y1, ... ring; last point connects to the first.
 * @return Long Twice the signed area.
 */
internal fun signedAreaTwice(points: IntArray): Long {
	var doubledArea = 0L
	val pointCount = points.size / 2

	for (pointIndex in 0 until pointCount) {
		val nextIndex = if (pointIndex + 1 == pointCount) 0 else pointIndex + 1
		val x = points[pointIndex * 2].toLong()
		val y = points[pointIndex * 2 + 1].toLong()
		val nextX = points[nextIndex * 2].toLong()
		val nextY = points[nextIndex * 2 + 1].toLong()
		doubledArea += x * nextY - nextX * y
	}

	return doubledArea
}

/**
 * Traces every closed boundary contour of the mask — outer island boundaries and enclosed
 * holes — as exact pixel-corner lattice rings.
 *
 * This traces the outline of the union of pixel squares, not a midpoint-interpolating
 * marching-squares isoline, which would offset every polygon by half a pixel and cut corners.
 * Every loop, outer or hole, contains at least one north edge (opaque pixel below, none above),
 * which the walk always takes eastward, so marking walked north edges makes each loop
 * discoverable exactly once, and the row-major scan fixes the contour order that downstream
 * repacking relies on.
 *
 * A 50 percent checkerboard-noise mask yields one unit-square contour per opaque pixel; that
 * pathological input is not mitigated here.
 *
 * @param AlphaMask mask The thresholded pixel mask; must contain at least one opaque pixel.
 * @param LayerBounds opaqueBounds The pixel-tight trimmed bounds of the mask's opaque pixels.
 * @return List The traced contours; never empty, at least one non-hole.
 */
internal fun traceAlphaContours(mask: AlphaMask, opaqueBounds: LayerBounds): List<AlphaContour> {
	val contours = mutableListOf<AlphaContour>()
	// Bit per pixel: set when the north edge of that pixel (the edge to the pixel above) has
	// been walked.  Same geometry as the mask itself.
	val northEdgeVisited = LongArray(((mask.width * mask.height) + 63) ushr 6)

	for (rowIndex in opaqueBounds.top until opaqueBounds.top + opaqueBounds.height) {
		for (columnIndex in opaqueBounds.left until opaqueBounds.left + opaqueBounds.width) {
			if (!mask.isOpaque(columnIndex, rowIndex)) {
				continue
			}

			if (mask.isOpaque(columnIndex, rowIndex - 1)) {
				continue
			}

			val edgeIndex = rowIndex * mask.width + columnIndex

			if ((northEdgeVisited[edgeIndex ushr 6] and (1L shl (edgeIndex and 63))) != 0L) {
				continue
			}

			contours.add(traceLoop(mask, northEdgeVisited, columnIndex, rowIndex))
		}
	}

	return contours
}

/**
 * Walks one closed boundary loop starting from the north edge of the given opaque pixel,
 * heading east, and returns its ring with the hole flag derived from the shoelace sign.
 *
 * Every traversed lattice edge keeps an opaque pixel on its right and a transparent one on its
 * left (y-down).  The loop ends when it is about to re-traverse the starting directed edge: the
 * start corner alone is not enough, because a weakly simple loop may pass through it again with
 * a different heading.
 *
 * @param AlphaMask mask The thresholded pixel mask.
 * @param LongArray northEdgeVisited Bit per pixel, set here for every eastward edge walked.
 * @param Int startColumn Column of the opaque pixel whose north edge starts the loop.
 * @param Int startRow Row of the opaque pixel whose north edge starts the loop.
 * @return AlphaContour The traced ring.
 */
private fun traceLoop(
	mask: AlphaMask,
	northEdgeVisited: LongArray,
	startColumn: Int,
	startRow: Int,
): AlphaContour {
	val points = ContourPointBuffer()
	var cornerX = startColumn
	var cornerY = startRow
	var headingX = 1
	var headingY = 0
	// The start corner is always a genuine vertex: the walk can only enter it turning (the
	// pixel to its west having a north edge on the same loop would have been discovered
	// first), so emitting it up front never duplicates a straight-run point.
	points.add(cornerX, cornerY)

	while (true) {
		if (headingX == 1) {
			// An eastward edge always has its opaque pixel below (opaque-on-right), so the
			// below pixel (cornerX, cornerY) is in range and this is its north edge.
			val edgeIndex = cornerY * mask.width + cornerX
			northEdgeVisited[edgeIndex ushr 6] = northEdgeVisited[edgeIndex ushr 6] or (1L shl (edgeIndex and 63))
		}

		cornerX += headingX
		cornerY += headingY
		// The two pixels flanking the edge straight ahead.  The quadrant offsets reduce to
		// these closed forms; each division operand is always -2 or 0, so truncation is exact.
		val leftOpaque =
			mask.isOpaque(
				cornerX + (headingX + headingY - 1) / 2,
				cornerY + (headingY - headingX - 1) / 2,
			)
		val rightOpaque =
			mask.isOpaque(
				cornerX + (headingX - headingY - 1) / 2,
				cornerY + (headingY + headingX - 1) / 2,
			)
		val newHeadingX: Int
		val newHeadingY: Int

		if (leftOpaque && rightOpaque) {
			// Turn left.
			newHeadingX = headingY
			newHeadingY = -headingX
		} else if (rightOpaque) {
			// Straight on.
			newHeadingX = headingX
			newHeadingY = headingY
		} else {
			// Both clear, or the diagonal saddle (left opaque, right clear): turn right.  The
			// saddle right turn is the 4-connected-foreground policy — diagonal-only contact
			// separates rather than merges.
			newHeadingX = -headingY
			newHeadingY = headingX
		}

		if (cornerX == startColumn && cornerY == startRow && newHeadingX == 1 && newHeadingY == 0) {
			break
		}

		if (newHeadingX != headingX || newHeadingY != headingY) {
			points.add(cornerX, cornerY)
		}

		headingX = newHeadingX
		headingY = newHeadingY
	}

	val ring = points.toArray()

	return AlphaContour(ring, isHole = signedAreaTwice(ring) < 0L)
}