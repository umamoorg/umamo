package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaChannel
import org.umamo.format.art.opaqueBounds
import org.umamo.geometry.predicate.orient2d
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Whether a mesh covers every pixel of its art at or above a threshold: each such pixel's center lies
 * inside, or on the edge of, one of the mesh's triangles, the mesh read in the art's own pixels.  An
 * art pixel no triangle covers is art the mesh cuts off - what a reload flags as outgrown, however the
 * art grew, into a concave mesh's notch included.  Both windings count as inside, and a triangle with
 * no area covers nothing.
 *
 * A pixel [alreadyCutOff] names does not count: art the mesh left out before the art changed (a faint
 * fringe a hand-made mesh never reached) is the modeller's choice, not art that grew past the mesh.
 *
 * Only the art's opaque bounds are scanned, one bit per pixel, so a large mostly-empty layer costs its
 * art, not its canvas.
 *
 * @param AlphaChannel alpha          The art.
 * @param Int          alphaThreshold The least alpha byte (1..255) counted as art.
 * @param FloatArray   artPoints      The mesh's vertices in the art's pixels, x right and y down: x0, y0, ...
 * @param IntArray     indices        The mesh's triangles, three vertex indices each.
 * @param Function     alreadyCutOff  Whether a pixel (column, row) of the art was already art the mesh
 *   left out; by default none was.
 * @return Boolean True when no pixel at or above the threshold lies outside the mesh, the excused ones aside.
 */
public fun meshCoversOpaquePixels(
	alpha: AlphaChannel,
	alphaThreshold: Int,
	artPoints: FloatArray,
	indices: IntArray,
	alreadyCutOff: (column: Int, row: Int) -> Boolean = { _, _ -> false },
): Boolean {
	val bounds = opaqueBoundsOf(alpha, alphaThreshold) ?: return true
	val covered = CoverageBits(bounds)

	for (triangle in 0 until indices.size / 3) {
		markTriangle(covered, artPoints, indices[3 * triangle], indices[3 * triangle + 1], indices[3 * triangle + 2])
	}

	for (row in bounds.top..bounds.bottom) {
		for (column in bounds.left..bounds.right) {
			if (alpha.alphaAt(column, row) >= alphaThreshold && !covered.isSet(column, row) && !alreadyCutOff(column, row)) {
				return false
			}
		}
	}

	return true
}

/**
 * The inclusive pixel bounds of the art at or above a threshold.
 *
 * @property Int left   The leftmost column.
 * @property Int top    The topmost row.
 * @property Int right  The rightmost column.
 * @property Int bottom The bottommost row.
 */
private class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * One bit per pixel over a bounds: whether some triangle covers the pixel's center.
 *
 * @property PixelBounds bounds The pixels the bits stand for.
 */
private class CoverageBits(val bounds: PixelBounds) {
	private val width = bounds.right - bounds.left + 1
	private val bits = LongArray(((width.toLong() * (bounds.bottom - bounds.top + 1) + 63) / 64).toInt())

	/**
	 * Marks a pixel covered.
	 *
	 * @param Int column The pixel's column.
	 * @param Int row    The pixel's row.
	 */
	fun set(column: Int, row: Int) {
		val bit = (row - bounds.top) * width + (column - bounds.left)
		bits[bit ushr 6] = bits[bit ushr 6] or (1L shl (bit and 63))
	}

	/**
	 * Whether a pixel is covered.
	 *
	 * @param Int column The pixel's column.
	 * @param Int row    The pixel's row.
	 * @return Boolean True when marked.
	 */
	fun isSet(column: Int, row: Int): Boolean {
		val bit = (row - bounds.top) * width + (column - bounds.left)

		return (bits[bit ushr 6] ushr (bit and 63)) and 1L == 1L
	}
}

/**
 * The inclusive pixel bounds of the art at or above a threshold, or null when there are none.
 *
 * @param AlphaChannel alpha          The art.
 * @param Int          alphaThreshold The least alpha byte counted as art.
 * @return PixelBounds? The bounds.
 */
private fun opaqueBoundsOf(alpha: AlphaChannel, alphaThreshold: Int): PixelBounds? =
	alpha.opaqueBounds(alphaThreshold)?.let { bounds -> PixelBounds(bounds.left, bounds.top, bounds.left + bounds.width - 1, bounds.top + bounds.height - 1) }

/**
 * Marks every pixel of the bounds whose center lies inside or on one triangle, decided by the exact
 * orientation test against each edge.
 *
 * @param CoverageBits covered   The bits.
 * @param FloatArray   artPoints The vertices.
 * @param Int          first     The first vertex.
 * @param Int          second    The second vertex.
 * @param Int          third     The third vertex.
 */
private fun markTriangle(covered: CoverageBits, artPoints: FloatArray, first: Int, second: Int, third: Int) {
	val ax = artPoints[2 * first].toDouble()
	val ay = artPoints[2 * first + 1].toDouble()
	val bx = artPoints[2 * second].toDouble()
	val by = artPoints[2 * second + 1].toDouble()
	val cx = artPoints[2 * third].toDouble()
	val cy = artPoints[2 * third + 1].toDouble()
	val area = orient2d(ax, ay, bx, by, cx, cy)

	if (area == 0.0) {
		return
	}

	val bounds = covered.bounds
	// Pixel centers sit at column + 0.5, so the columns whose centers the triangle's box can hold run
	// from ceil(min - 0.5) to floor(max - 0.5).
	val firstColumn = maxOf(bounds.left, ceil(minOf(ax, bx, cx) - 0.5).toInt())
	val lastColumn = minOf(bounds.right, floor(maxOf(ax, bx, cx) - 0.5).toInt())
	val firstRow = maxOf(bounds.top, ceil(minOf(ay, by, cy) - 0.5).toInt())
	val lastRow = minOf(bounds.bottom, floor(maxOf(ay, by, cy) - 0.5).toInt())
	val sign = if (area > 0.0) 1.0 else -1.0

	for (row in firstRow..lastRow) {
		val centerY = row + 0.5

		for (column in firstColumn..lastColumn) {
			val centerX = column + 0.5

			if (sign * orient2d(ax, ay, bx, by, centerX, centerY) >= 0.0 &&
				sign * orient2d(bx, by, cx, cy, centerX, centerY) >= 0.0 &&
				sign * orient2d(cx, cy, ax, ay, centerX, centerY) >= 0.0
			) {
				covered.set(column, row)
			}
		}
	}
}