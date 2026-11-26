package org.umamo.render.eval

import org.umamo.runtime.keyform.withEveryLatticeMapped
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.LatticeDepth
import org.umamo.runtime.model.WarpLatticeLayout
import org.umamo.runtime.model.valuesFor

/**
 * This warp on a lattice of [rows] x [columns] cells: every grid cell's and every blend-shape form's lattice
 * resampled by evaluating its old map at the new grid's points, so the deformation keeps its shape.  The new
 * point at (row, column) is the old map at u = column / [columns], v = row / [rows], the far edges included, and a
 * grid cell's depth is resampled at the same places the same way.
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

	val layout = WarpLatticeLayout(rows, columns)

	/**
	 * Interleaved values over the old lattice resampled onto the new grid's points by the old lattice's own map.
	 *
	 * @param FloatArray points The old lattice's interleaved pairs.
	 * @return FloatArray The new lattice's interleaved pairs.
	 */
	fun resampledPairs(points: FloatArray): FloatArray {
		val resampled = FloatArray(layout.pointCount * 2)

		for (row in 0..rows) {
			for (column in 0..columns) {
				latticePointAt(points, oldColumns, oldRows, bilinear, column.toFloat() / columns, row.toFloat() / rows, resampled, layout.pointIndex(row, column) * 2)
			}
		}

		return resampled
	}

	// A depth rides the x slot of the same evaluation, so it is interpolated exactly as the points are.
	val resampledDepth = { depth: LatticeDepth ->
		when (depth) {
			LatticeDepth.Flat -> {
				depth
			}

			is LatticeDepth.PerPoint -> {
				val oldDepths = depth.valuesFor((oldRows + 1) * (oldColumns + 1))
				val depthPairs = FloatArray(oldDepths.size * 2) { componentIndex -> if (componentIndex % 2 == 0) oldDepths[componentIndex / 2] else 0f }
				val resampledPairs = resampledPairs(depthPairs)
				LatticeDepth.PerPoint(FloatArray(layout.pointCount) { pointIndex -> resampledPairs[pointIndex * 2] })
			}
		}
	}

	return withEveryLatticeMapped(resampledDepth) { points -> resampledPairs(points) }.copy(rows = rows, columns = columns)
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