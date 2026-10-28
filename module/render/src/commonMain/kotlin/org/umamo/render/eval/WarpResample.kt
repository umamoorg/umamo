package org.umamo.render.eval

import org.umamo.runtime.keyform.withEveryLatticeMapped
import org.umamo.runtime.model.Deformer

/**
 * This warp on a lattice of [rows] x [columns] cells: every grid cell's and every blend-shape form's lattice
 * resampled by evaluating its old map at the new grid's points, so the deformation keeps its shape.  The new
 * point at (row, column) is the old map at u = column / [columns], v = row / [rows], the far edges included.
 *
 * In quad mode a refinement by whole multiples reproduces the old map exactly inside the lattice
 * ([isExactRefinementTo]): a bilinear patch restricted to a sub-cell is still bilinear.  Any other resample - a
 * coarser grid, a non-multiple, every triangle-split one - approximates it, so what lives in the lattice moves a
 * little unless it is re-expressed (warpChildrenKeptInPlace).
 *
 * @param Int rows The new cell count along v, at least 1.
 * @param Int columns The new cell count along u, at least 1.
 * @return Deformer.Warp The resampled warp, or this when the size is unchanged or not positive.
 */
fun Deformer.Warp.resampledTo(rows: Int, columns: Int): Deformer.Warp {
	if (rows < 1 || columns < 1 || (rows == this.rows && columns == this.columns)) {
		return this
	}

	val oldRows = this.rows
	val oldColumns = this.columns
	val bilinear = isQuadTransform

	return withEveryLatticeMapped { points ->
		val resampled = FloatArray((rows + 1) * (columns + 1) * 2)

		for (row in 0..rows) {
			for (column in 0..columns) {
				val pointIndex = row * (columns + 1) + column
				latticePointAt(points, oldColumns, oldRows, bilinear, column.toFloat() / columns, row.toFloat() / rows, resampled, pointIndex * 2)
			}
		}

		resampled
	}.copy(rows = rows, columns = columns)
}

/**
 * Whether resampling this warp onto [rows] x [columns] cells keeps its map exactly inside the lattice: a quad-mode
 * lattice refined by whole multiples on both axes.  Then nothing inside the lattice moves, and a child lying wholly
 * inside it keeps its coordinates as they are.
 *
 * @param Int rows The new cell count along v.
 * @param Int columns The new cell count along u.
 * @return Boolean True for an exact refinement.
 */
fun Deformer.Warp.isExactRefinementTo(rows: Int, columns: Int): Boolean =
	isQuadTransform && rows >= this.rows && columns >= this.columns && rows % this.rows == 0 && columns % this.columns == 0