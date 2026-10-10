package org.umamo.format.art

import kotlin.math.floor
import kotlin.math.sqrt

/** The default cap on an [AlphaField]'s grid: at most this many cells along either side. */
public const val DEFAULT_MAXIMUM_GRID_SIDE: Int = 2048

/**
 * A layer's signed distance field: how far each point lies from the layer's opaque pixels, positive
 * outside them and negative inside, in raster pixels - the field the auto-mesh's outline and inner
 * rings are contours of.  Built by [alphaField].
 *
 * The field is sampled at the centers of a working grid of square cells, [cellSize] pixels on a side
 * (a cell is opaque when any pixel in it is), and its zero lies on the shared edge between an opaque
 * and a transparent cell.  Alongside the grid it keeps the FULL-RESOLUTION mask of the same area, so
 * the exact checks ([segmentClears], [containsOpaquePixelCenter], [isOpaquePixel]) never see the
 * grid's coarseness.
 *
 * Coordinates are raster px, y down.  The grid covers the opaque pixels and any seeds with a padding
 * of whole cells on every side, aligned to multiples of [cellSize] - so it may reach past the raster
 * into negative coordinates, where everything is transparent.
 *
 * A plain class: it holds arrays.
 *
 * @property Int cellSize         The working cell's side in pixels.
 * @property Int gridLeft         The grid's left edge, raster px.
 * @property Int gridTop          The grid's top edge, raster px.
 * @property Int columns          The grid's width in cells.
 * @property Int rows             The grid's height in cells.
 * @property Int opaquePixelCount The raster's pixels at or above the threshold (seeds not counted).
 */
public class AlphaField internal constructor(
	public val cellSize: Int,
	public val gridLeft: Int,
	public val gridTop: Int,
	public val columns: Int,
	public val rows: Int,
	public val opaquePixelCount: Int,
	private val mask: CroppedAlphaMask,
	private val values: FloatArray,
) {
	/**
	 * The field at a point, interpolated bilinearly between cell centers; constant beyond the outer
	 * cell centers up to the grid's edge, and +infinity outside the grid.
	 *
	 * @param Double x The point's x, raster px.
	 * @param Double y The point's y.
	 * @return Double The signed distance, raster px.
	 */
	public fun valueAt(x: Double, y: Double): Double {
		val gridX = (x - gridLeft) / cellSize - 0.5
		val gridY = (y - gridTop) / cellSize - 0.5

		if (gridX < -0.5 || gridY < -0.5 || gridX > columns - 0.5 || gridY > rows - 0.5) {
			return Double.POSITIVE_INFINITY
		}

		val clampedX = gridX.coerceIn(0.0, columns - 1.0)
		val clampedY = gridY.coerceIn(0.0, rows - 1.0)
		val column = floor(clampedX).toInt().coerceAtMost(columns - 2)
		val row = floor(clampedY).toInt().coerceAtMost(rows - 2)
		val fractionX = clampedX - column
		val fractionY = clampedY - row
		val top = cellValue(column, row) * (1.0 - fractionX) + cellValue(column + 1, row) * fractionX
		val bottom = cellValue(column, row + 1) * (1.0 - fractionX) + cellValue(column + 1, row + 1) * fractionX

		return top * (1.0 - fractionY) + bottom * fractionY
	}

	/**
	 * The field at one cell's center.
	 *
	 * @param Int column The cell's column.
	 * @param Int row    The cell's row.
	 * @return Double The signed distance, raster px.
	 */
	public fun cellValue(column: Int, row: Int): Double = values[row * columns + column].toDouble()

	/**
	 * The closed contours of the field at a level, by marching squares over the cell centers.  Inside
	 * (field below the level) lies to the left of travel in raw coordinates, so an outer boundary
	 * has positive shoelace area and a hole negative; rings at one level are pairwise disjoint and
	 * never pass through a cell center.  Every level up to the padding stays closed.
	 *
	 * @param Double level The level, raster px (positive outside the art, negative inside).
	 * @return List<DoubleArray> The rings, x0, y0, x1, y1, ... in raster px, each closed implicitly.
	 */
	public fun isoRings(level: Double): List<DoubleArray> = traceIsoRings(values, columns, rows, level, gridLeft + cellSize / 2.0, gridTop + cellSize / 2.0, cellSize.toDouble())

	/**
	 * Whether every opaque pixel's square lies at least a distance from a segment, checked exactly
	 * against the full-resolution pixels.
	 *
	 * @param Double startX           The segment's start x, raster px.
	 * @param Double startY           The segment's start y.
	 * @param Double endX             The segment's end x.
	 * @param Double endY             The segment's end y.
	 * @param Double requiredDistance The clearance required, raster px.
	 * @return Boolean True when no opaque pixel square comes closer.
	 */
	public fun segmentClears(startX: Double, startY: Double, endX: Double, endY: Double, requiredDistance: Double): Boolean = mask.segmentClears(startX, startY, endX, endY, requiredDistance)

	/**
	 * Whether any opaque pixel's center lies strictly inside a polygon (even-odd), checked exactly
	 * against the full-resolution pixels.
	 *
	 * @param DoubleArray polygon The polygon's vertices, x0, y0, ..., raster px; closed implicitly.
	 * @return Boolean True when some opaque pixel's center is inside.
	 */
	public fun containsOpaquePixelCenter(polygon: DoubleArray): Boolean = mask.containsOpaquePixelCenter(polygon)

	/**
	 * Whether a pixel is opaque (or a seed).
	 *
	 * @param Int column The pixel's raster column.
	 * @param Int row    The pixel's raster row.
	 * @return Boolean True for an opaque or seeded pixel.
	 */
	public fun isOpaquePixel(column: Int, row: Int): Boolean = mask.isOpaque(column, row)
}

/**
 * Builds this raster's signed distance field for meshing.
 *
 * The grid covers every pixel at or above the threshold plus every seed point, padded by
 * [paddingCells] cells on each side.  [cellSize] is a minimum: when the padded grid would exceed
 * [maximumGridSide] cells along either side, the cell grows until it fits (read the field's
 * [AlphaField.cellSize]).  Seeds are points that must count as opaque (a mesh's pinned vertices):
 * the pixel under each is marked opaque in both the mask and the grid.
 *
 * @param Int         alphaThreshold  The minimum alpha byte counted as opaque (1..255).
 * @param Int         cellSize        The smallest working cell side, pixels (at least 1).
 * @param Int         paddingCells    Transparent cells kept around the art on every side (at least 1); contours stay closed for levels up to (paddingCells - 1) * cellSize.
 * @param DoubleArray seeds           Seed points, x0, y0, ..., raster px; finite.
 * @param Int         maximumGridSide The most cells allowed along either side of the grid.
 * @return AlphaField? The field, or null when no raster pixel meets the threshold (seeds alone do not make a field).
 */
public fun LayerRaster.alphaField(
	alphaThreshold: Int,
	cellSize: Int,
	paddingCells: Int,
	seeds: DoubleArray = DoubleArray(0),
	maximumGridSide: Int = DEFAULT_MAXIMUM_GRID_SIDE,
): AlphaField? {
	require(alphaThreshold in 1..255) { "alphaThreshold must be in 1..255: $alphaThreshold" }
	require(cellSize >= 1) { "cellSize must be at least 1: $cellSize" }
	require(paddingCells >= 1) { "paddingCells must be at least 1: $paddingCells" }
	require(maximumGridSide > 2 * paddingCells + 1) { "maximumGridSide $maximumGridSide leaves no room inside $paddingCells padding cells" }
	require(seeds.size % 2 == 0) { "seeds must hold x, y pairs, but has ${seeds.size} components" }

	for (component in seeds.indices) {
		require(seeds[component].isFinite()) { "seed component $component is not finite: ${seeds[component]}" }
	}

	val opaqueExtent = opaquePixelExtent(this, alphaThreshold) ?: return null
	val seedPixels = IntArray(seeds.size) { component -> floor(seeds[component]).toInt() }
	var minimumColumn = opaqueExtent[0]
	var minimumRow = opaqueExtent[1]
	var maximumColumn = opaqueExtent[2]
	var maximumRow = opaqueExtent[3]

	for (seed in 0 until seedPixels.size / 2) {
		minimumColumn = minOf(minimumColumn, seedPixels[2 * seed])
		minimumRow = minOf(minimumRow, seedPixels[2 * seed + 1])
		maximumColumn = maxOf(maximumColumn, seedPixels[2 * seed])
		maximumRow = maxOf(maximumRow, seedPixels[2 * seed + 1])
	}

	// Grow the cell until the padded grid fits the cap; cells stay aligned to multiples of their size.
	var effectiveCellSize = cellSize
	var firstCellColumn: Int
	var firstCellRow: Int
	var columns: Int
	var rows: Int

	while (true) {
		firstCellColumn = minimumColumn.floorDiv(effectiveCellSize) - paddingCells
		firstCellRow = minimumRow.floorDiv(effectiveCellSize) - paddingCells
		columns = maximumColumn.floorDiv(effectiveCellSize) + paddingCells - firstCellColumn + 1
		rows = maximumRow.floorDiv(effectiveCellSize) + paddingCells - firstCellRow + 1

		if (maxOf(columns, rows) <= maximumGridSide) {
			break
		}

		effectiveCellSize++
	}

	val gridLeft = firstCellColumn * effectiveCellSize
	val gridTop = firstCellRow * effectiveCellSize
	val mask = CroppedAlphaMask.build(this, alphaThreshold, gridLeft, gridTop, columns * effectiveCellSize, rows * effectiveCellSize, seedPixels)
	val opaqueCells = BooleanArray(columns * rows)

	mask.forEachOpaquePixel { column, row ->
		val cellColumn = (column - gridLeft) / effectiveCellSize
		val cellRow = (row - gridTop) / effectiveCellSize
		opaqueCells[cellRow * columns + cellColumn] = true
	}

	val values = signedField(opaqueCells, columns, rows, effectiveCellSize)

	return AlphaField(effectiveCellSize, gridLeft, gridTop, columns, rows, opaqueExtent[4], mask, values)
}

/**
 * The extent of a raster's pixels at or above a threshold.
 *
 * @param LayerRaster raster         The raster.
 * @param Int         alphaThreshold The minimum alpha counted as opaque.
 * @return IntArray? Minimum column, minimum row, maximum column, maximum row, and the opaque count; null when nothing qualifies.
 */
private fun opaquePixelExtent(raster: LayerRaster, alphaThreshold: Int): IntArray? {
	require(raster.rgba.size.toLong() == raster.width.toLong() * raster.height.toLong() * 4L) {
		"rgba size ${raster.rgba.size} does not match ${raster.width} x ${raster.height} x 4"
	}

	var minimumColumn = Int.MAX_VALUE
	var minimumRow = Int.MAX_VALUE
	var maximumColumn = -1
	var maximumRow = -1
	var count = 0

	for (row in 0 until raster.height) {
		for (column in 0 until raster.width) {
			// The `and 0xFF` is load-bearing: Kotlin Byte is signed.
			val alpha = raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF

			if (alpha >= alphaThreshold) {
				count++
				minimumColumn = minOf(minimumColumn, column)
				minimumRow = minOf(minimumRow, row)
				maximumColumn = maxOf(maximumColumn, column)
				maximumRow = maxOf(maximumRow, row)
			}
		}
	}

	if (count == 0) {
		return null
	}

	return intArrayOf(minimumColumn, minimumRow, maximumColumn, maximumRow, count)
}

/**
 * The signed field at every cell center: the distance to the nearest opaque cell minus half a cell
 * outside, the negated distance to the nearest transparent cell plus half a cell inside, in pixels -
 * so the field crosses zero on the shared edge between an opaque and a transparent cell.
 *
 * @param BooleanArray opaqueCells The cells' opacity, row-major.
 * @param Int          columns     The grid width.
 * @param Int          rows        The grid height.
 * @param Int          cellSize    The cell side in pixels.
 * @return FloatArray The field, row-major.
 */
private fun signedField(opaqueCells: BooleanArray, columns: Int, rows: Int, cellSize: Int): FloatArray {
	val values = FloatArray(columns * rows)
	val squaredDistances = IntArray(columns * rows)
	val halfCell = cellSize / 2.0

	squaredDistanceToFeatures(opaqueCells, true, columns, rows, squaredDistances)

	for (cell in values.indices) {
		if (!opaqueCells[cell]) {
			values[cell] = (sqrt(squaredDistances[cell].toDouble()) * cellSize - halfCell).toFloat()
		}
	}

	// The padding guarantees transparent cells exist, so every opaque cell reaches one.
	squaredDistanceToFeatures(opaqueCells, false, columns, rows, squaredDistances)

	for (cell in values.indices) {
		if (opaqueCells[cell]) {
			values[cell] = (-(sqrt(squaredDistances[cell].toDouble()) * cellSize - halfCell)).toFloat()
		}
	}

	return values
}