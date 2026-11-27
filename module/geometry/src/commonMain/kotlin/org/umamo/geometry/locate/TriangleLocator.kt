package org.umamo.geometry.locate

import org.umamo.geometry.predicate.orient2d
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Where a query point fell on a triangle mesh.
 *
 * @property Int     triangleIndex The triangle (its position in the mesh's index array, divided by three).
 * @property Double  weightA       The barycentric weight of the triangle's first corner.
 * @property Double  weightB       The weight of its second corner.
 * @property Double  weightC       The weight of its third corner.
 * @property Boolean isInside      True when the point lies in the triangle (edges included); false when it lies off the mesh and the weights describe the nearest point on the triangle's boundary.
 * @property Double  distance      Zero inside; otherwise the distance to that nearest point.
 */
public data class TriangleLocation(
	val triangleIndex: Int,
	val weightA: Double,
	val weightB: Double,
	val weightC: Double,
	val isInside: Boolean,
	val distance: Double,
)

/**
 * Point location over an arbitrary triangle mesh: overlapping, folded, either winding, not Delaunay -
 * the shape a hand-edited UV layout can take.
 *
 * A query inside some triangle reports the LOWEST-indexed triangle containing it (edges included, by
 * exact orientation tests), so a point on a shared edge or in an overlap always resolves the same way.
 * A query inside none reports the nearest triangle, ties to the lower index, with its weights clamped
 * onto that triangle's boundary.  Zero-area triangles are skipped throughout; a mesh with nothing else
 * locates nothing.
 *
 * Containment is answered through a uniform grid of the triangles' bounding boxes, each cell listing
 * its triangles in ascending order; the nearest-triangle fallback scans every triangle, which is
 * simple and plenty for meshes of a few thousand triangles.
 *
 * @param FloatArray positions The vertices, x0, y0, x1, y1, ...
 * @param IntArray   triangles Three vertex indices per triangle.
 */
public class TriangleLocator(private val positions: FloatArray, private val triangles: IntArray) {
	private val triangleCount: Int = triangles.size / 3

	/** Per triangle, whether it has nonzero area and so takes part. */
	private val usable: BooleanArray

	private val gridMinimumX: Double
	private val gridMinimumY: Double
	private val gridMaximumX: Double
	private val gridMaximumY: Double
	private val cellsPerAxis: Int
	private val cellWidth: Double
	private val cellHeight: Double

	/** Where each cell's triangle list starts in [cellTriangles]; one extra entry closes the last. */
	private val cellStart: IntArray

	/** Every cell's triangles, ascending within each cell. */
	private val cellTriangles: IntArray

	init {
		require(positions.size % 2 == 0) { "positions must hold x, y pairs, but has ${positions.size} components" }
		require(triangles.size % 3 == 0) { "triangles must hold index triples, but has ${triangles.size} entries" }
		val vertexCount = positions.size / 2

		for (entry in triangles.indices) {
			require(triangles[entry] in 0 until vertexCount) { "triangle entry $entry is ${triangles[entry]}, outside 0 until $vertexCount" }
		}

		usable = BooleanArray(triangleCount) { triangle -> signedArea(triangle) != 0.0 }
		var minimumX = Double.POSITIVE_INFINITY
		var minimumY = Double.POSITIVE_INFINITY
		var maximumX = Double.NEGATIVE_INFINITY
		var maximumY = Double.NEGATIVE_INFINITY
		var usableCount = 0

		for (triangle in 0 until triangleCount) {
			if (!usable[triangle]) {
				continue
			}

			usableCount++

			for (corner in 0 until 3) {
				val vertex = triangles[3 * triangle + corner]
				minimumX = minOf(minimumX, xOf(vertex))
				minimumY = minOf(minimumY, yOf(vertex))
				maximumX = maxOf(maximumX, xOf(vertex))
				maximumY = maxOf(maximumY, yOf(vertex))
			}
		}

		gridMinimumX = minimumX
		gridMinimumY = minimumY
		gridMaximumX = maximumX
		gridMaximumY = maximumY
		cellsPerAxis = ceil(sqrt(usableCount.toDouble())).toInt().coerceIn(1, MAXIMUM_CELLS_PER_AXIS)
		cellWidth = if (usableCount > 0) (maximumX - minimumX) / cellsPerAxis else 0.0
		cellHeight = if (usableCount > 0) (maximumY - minimumY) / cellsPerAxis else 0.0

		// Count, then fill: walking triangles in ascending order twice leaves every cell sorted.
		val cellCount = cellsPerAxis * cellsPerAxis
		cellStart = IntArray(cellCount + 1)
		forEachCellOfUsable { cell, _ -> cellStart[cell + 1]++ }

		for (cell in 0 until cellCount) {
			cellStart[cell + 1] += cellStart[cell]
		}

		cellTriangles = IntArray(cellStart[cellCount])
		val filled = cellStart.copyOf(cellCount)
		forEachCellOfUsable { cell, triangle ->
			cellTriangles[filled[cell]] = triangle
			filled[cell]++
		}
	}

	/**
	 * Locates a point.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 * @return TriangleLocation? The containing triangle, else the nearest one; null only when every triangle has zero area.
	 */
	public fun locate(x: Double, y: Double): TriangleLocation? {
		val insideGrid = x >= gridMinimumX && x <= gridMaximumX && y >= gridMinimumY && y <= gridMaximumY

		if (insideGrid) {
			val cell = cellRow(y) * cellsPerAxis + cellColumn(x)

			for (entry in cellStart[cell] until cellStart[cell + 1]) {
				val triangle = cellTriangles[entry]

				if (contains(triangle, x, y)) {
					return insideLocation(triangle, x, y)
				}
			}
		}

		return nearestLocation(x, y)
	}

	/**
	 * Whether a usable triangle contains a point, edges included, whichever its winding.
	 *
	 * @param Int    triangle The triangle.
	 * @param Double x        The point's x.
	 * @param Double y        The point's y.
	 * @return Boolean True when the point is inside or on the boundary.
	 */
	private fun contains(triangle: Int, x: Double, y: Double): Boolean {
		val first = triangles[3 * triangle]
		val second = triangles[3 * triangle + 1]
		val third = triangles[3 * triangle + 2]
		val winding = signedArea(triangle)
		val acrossFirst = orient2d(xOf(first), yOf(first), xOf(second), yOf(second), x, y)
		val acrossSecond = orient2d(xOf(second), yOf(second), xOf(third), yOf(third), x, y)
		val acrossThird = orient2d(xOf(third), yOf(third), xOf(first), yOf(first), x, y)

		return if (winding > 0.0) {
			acrossFirst >= 0.0 && acrossSecond >= 0.0 && acrossThird >= 0.0
		} else {
			acrossFirst <= 0.0 && acrossSecond <= 0.0 && acrossThird <= 0.0
		}
	}

	/**
	 * The barycentric location of a point inside a triangle.
	 *
	 * @param Int    triangle The containing triangle.
	 * @param Double x        The point's x.
	 * @param Double y        The point's y.
	 * @return TriangleLocation The location, inside, at distance zero.
	 */
	private fun insideLocation(triangle: Int, x: Double, y: Double): TriangleLocation {
		val first = triangles[3 * triangle]
		val second = triangles[3 * triangle + 1]
		val third = triangles[3 * triangle + 2]
		val firstX = xOf(first)
		val firstY = yOf(first)
		val secondX = xOf(second)
		val secondY = yOf(second)
		val thirdX = xOf(third)
		val thirdY = yOf(third)
		val doubleArea = (secondX - firstX) * (thirdY - firstY) - (secondY - firstY) * (thirdX - firstX)
		val weightA = ((secondX - x) * (thirdY - y) - (secondY - y) * (thirdX - x)) / doubleArea
		val weightB = ((thirdX - x) * (firstY - y) - (thirdY - y) * (firstX - x)) / doubleArea

		return TriangleLocation(triangle, weightA, weightB, 1.0 - weightA - weightB, true, 0.0)
	}

	/**
	 * The nearest usable triangle to a point inside none of them, ties to the lower index, with the
	 * weights of the closest point on its boundary.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 * @return TriangleLocation? The location, or null when no triangle is usable.
	 */
	private fun nearestLocation(x: Double, y: Double): TriangleLocation? {
		var best: TriangleLocation? = null
		var bestDistanceSquared = Double.POSITIVE_INFINITY

		for (triangle in 0 until triangleCount) {
			if (!usable[triangle]) {
				continue
			}

			for (edge in 0 until 3) {
				val start = triangles[3 * triangle + edge]
				val end = triangles[3 * triangle + (edge + 1) % 3]
				val startX = xOf(start)
				val startY = yOf(start)
				val alongX = xOf(end) - startX
				val alongY = yOf(end) - startY
				val lengthSquared = alongX * alongX + alongY * alongY
				val fraction = (((x - startX) * alongX + (y - startY) * alongY) / lengthSquared).coerceIn(0.0, 1.0)
				val offsetX = startX + fraction * alongX - x
				val offsetY = startY + fraction * alongY - y
				val distanceSquared = offsetX * offsetX + offsetY * offsetY

				if (distanceSquared < bestDistanceSquared) {
					bestDistanceSquared = distanceSquared
					val weights = DoubleArray(3)
					weights[edge] = 1.0 - fraction
					weights[(edge + 1) % 3] = fraction
					best = TriangleLocation(triangle, weights[0], weights[1], weights[2], false, sqrt(distanceSquared))
				}
			}
		}

		return best
	}

	/**
	 * Twice a triangle's signed area, with an exact sign.
	 *
	 * @param Int triangle The triangle.
	 * @return Double Positive for one winding, negative for the other, zero exactly when degenerate.
	 */
	private fun signedArea(triangle: Int): Double {
		val first = triangles[3 * triangle]
		val second = triangles[3 * triangle + 1]
		val third = triangles[3 * triangle + 2]

		return orient2d(xOf(first), yOf(first), xOf(second), yOf(second), xOf(third), yOf(third))
	}

	/**
	 * Calls an action once for every (cell, usable triangle) pair whose bounding box covers the cell,
	 * triangles ascending.
	 *
	 * @param (Int, Int) -> Unit action Receives the cell and the triangle.
	 */
	private inline fun forEachCellOfUsable(action: (Int, Int) -> Unit) {
		for (triangle in 0 until triangleCount) {
			if (!usable[triangle]) {
				continue
			}

			var minimumX = Double.POSITIVE_INFINITY
			var minimumY = Double.POSITIVE_INFINITY
			var maximumX = Double.NEGATIVE_INFINITY
			var maximumY = Double.NEGATIVE_INFINITY

			for (corner in 0 until 3) {
				val vertex = triangles[3 * triangle + corner]
				minimumX = minOf(minimumX, xOf(vertex))
				minimumY = minOf(minimumY, yOf(vertex))
				maximumX = maxOf(maximumX, xOf(vertex))
				maximumY = maxOf(maximumY, yOf(vertex))
			}

			for (row in cellRow(minimumY)..cellRow(maximumY)) {
				for (column in cellColumn(minimumX)..cellColumn(maximumX)) {
					action(row * cellsPerAxis + column, triangle)
				}
			}
		}
	}

	/**
	 * The grid column holding an x inside the grid's bounds.
	 *
	 * @param Double x The coordinate.
	 * @return Int The column, clamped to the grid.
	 */
	private fun cellColumn(x: Double): Int = if (cellWidth > 0.0) ((x - gridMinimumX) / cellWidth).toInt().coerceIn(0, cellsPerAxis - 1) else 0

	/**
	 * The grid row holding a y inside the grid's bounds.
	 *
	 * @param Double y The coordinate.
	 * @return Int The row, clamped to the grid.
	 */
	private fun cellRow(y: Double): Int = if (cellHeight > 0.0) ((y - gridMinimumY) / cellHeight).toInt().coerceIn(0, cellsPerAxis - 1) else 0

	/**
	 * A vertex's x.
	 *
	 * @param Int vertex The vertex.
	 * @return Double The coordinate, widened exactly from Float.
	 */
	private fun xOf(vertex: Int): Double = positions[2 * vertex].toDouble()

	/**
	 * A vertex's y.
	 *
	 * @param Int vertex The vertex.
	 * @return Double The coordinate, widened exactly from Float.
	 */
	private fun yOf(vertex: Int): Double = positions[2 * vertex + 1].toDouble()

	private companion object {
		/** Caps the grid at 256 x 256 cells however large the mesh. */
		const val MAXIMUM_CELLS_PER_AXIS = 256
	}
}