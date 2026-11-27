package org.umamo.format.art

/**
 * The cells outside a grid field's contours at a level: every cell at or above the level that the
 * grid's border reaches through such cells, joined exactly as the marching squares in IsoContour.kt
 * join them - side neighbors always, diagonal neighbors through a square whose four-corner average
 * is at or above the level (the saddle rule that keeps a square's outside corners connected).  So a
 * cell is exterior exactly when no ring traced at the level encloses its center.
 *
 * @param FloatArray values  The field, row-major, one value per cell center.
 * @param Int        columns The grid width.
 * @param Int        rows    The grid height.
 * @param Double     level   The level.
 * @return BooleanArray True for each exterior cell, row-major.
 */
internal fun exteriorCells(values: FloatArray, columns: Int, rows: Int, level: Double): BooleanArray {
	val exterior = BooleanArray(columns * rows)
	val queue = IntArray(columns * rows)
	var queueEnd = 0

	for (row in 0 until rows) {
		for (column in 0 until columns) {
			val cell = row * columns + column
			val onBorder = row == 0 || column == 0 || row == rows - 1 || column == columns - 1

			if (onBorder && values[cell] >= level) {
				exterior[cell] = true
				queue[queueEnd++] = cell
			}
		}
	}

	var queueStart = 0

	while (queueStart < queueEnd) {
		val cell = queue[queueStart++]
		val row = cell / columns
		val column = cell % columns

		for (rowStep in -1..1) {
			for (columnStep in -1..1) {
				val neighborRow = row + rowStep
				val neighborColumn = column + columnStep

				if ((rowStep == 0 && columnStep == 0) || neighborRow !in 0 until rows || neighborColumn !in 0 until columns) {
					continue
				}

				val neighbor = neighborRow * columns + neighborColumn

				if (exterior[neighbor] || values[neighbor] < level) {
					continue
				}

				// A diagonal step crosses the square the two cells share; the contours keep its outside
				// corners joined only when the square's center is outside.  The corners are summed in the
				// tracer's order (top-left, top-right, bottom-right, bottom-left) so a tie rounds the same.
				val isDiagonal = rowStep != 0 && columnStep != 0

				if (isDiagonal) {
					val top = minOf(row, neighborRow)
					val left = minOf(column, neighborColumn)
					val centerAverage =
						(
							values[top * columns + left].toDouble() +
								values[top * columns + left + 1] +
								values[(top + 1) * columns + left + 1] +
								values[(top + 1) * columns + left]
						) / 4.0

					if (centerAverage < level) {
						continue
					}
				}

				exterior[neighbor] = true
				queue[queueEnd++] = neighbor
			}
		}
	}

	return exterior
}