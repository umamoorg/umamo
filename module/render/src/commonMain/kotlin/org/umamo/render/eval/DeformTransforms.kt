package org.umamo.render.eval

import kotlin.math.cos
import kotlin.math.sin

// The float pi the Umamo C++ Runtime uses (matches its sincosf bit-for-bit)
internal const val PI_F = 3.1415927f

/** Below this a 2x2 map's determinant counts as singular: the map folds or collapses there, and no inverse holds. */
internal const val SINGULAR_DETERMINANT = 1e-12f

/**
 * [angle] wrapped into [-pi, pi], so a difference of two wrapped angles never turns the long way round.
 *
 * @param Float angle The angle in radians.
 * @return Float The wrapped angle.
 */
internal fun wrapRadians(angle: Float): Float {
	var turned = angle

	while (turned > PI_F) {
		turned -= 2f * PI_F
	}

	while (turned < -PI_F) {
		turned += 2f * PI_F
	}

	return turned
}

/**
 * The Jacobian of [warp]'s map at (u, v) by forward differences of [step], from the map's value there
 * ([originX], [originY]), written row-major into [jacobian]: d(x)/du, d(x)/dv, d(y)/du, d(y)/dv.  What the
 * inverses and the extrapolation take where no closed form holds ([latticeJacobianAt] is the exact one inside the
 * lattice).
 *
 * @param WarpWorld warp The baked warp.
 * @param Float u The horizontal coordinate.
 * @param Float v The vertical coordinate.
 * @param Float originX The map's x at (u, v).
 * @param Float originY The map's y at (u, v).
 * @param Float step The difference step, in normalized lattice coordinates.
 * @param FloatArray stepped Two scratch slots for the stepped evaluations.
 * @param FloatArray jacobian Four slots for the Jacobian.
 */
internal fun warpDifferenceJacobian(
	warp: WarpWorld,
	u: Float,
	v: Float,
	originX: Float,
	originY: Float,
	step: Float,
	stepped: FloatArray,
	jacobian: FloatArray,
) {
	warpApply(warp.cp, warp.cols, warp.rows, warp.bilinear, u + step, v, stepped, 0)
	jacobian[0] = (stepped[0] - originX) / step
	jacobian[2] = (stepped[1] - originY) / step
	warpApply(warp.cp, warp.cols, warp.rows, warp.bilinear, u, v + step, stepped, 0)
	jacobian[1] = (stepped[0] - originX) / step
	jacobian[3] = (stepped[1] - originY) / step
}

/**
 * The cell ordinal along one lattice axis that grid coordinate [grid] falls in: its whole part, a far edge belonging
 * to the last cell.  The one border rule every lattice lookup by cell reads.
 *
 * @param Float grid The coordinate in cells along the axis.
 * @param Int cellCount The axis's cell count.
 * @return Int The cell's ordinal along the axis.
 */
internal fun cellOrdinal(grid: Float, cellCount: Int): Int = grid.toInt().coerceAtMost(cellCount - 1)

/**
 * In-grid warp transform: maps a child point `(u, v)` inside the lattice `[0,1)²` to world via the
 * deformed control points [cp] - `(cols+1)×(rows+1)` points, interleaved x,y. [bilinear] true does the
 * quad (bilinear) blend of the 4 surrounding control points, false the triangle split. Writes the
 * result to `out[outIndex]`,`out[outIndex+1]`; returns false (and writes nothing) when `(u, v)` is
 * outside the grid.
 *
 * @param FloatArray cp       Control points, `(cols+1)*(rows+1)` interleaved x,y.
 * @param Int        cols     Grid columns.
 * @param Int        rows     Grid rows.
 * @param Boolean    bilinear Quad blend (true) vs triangle split (false).
 * @param Float      u        Normalized horizontal coordinate.
 * @param Float      v        Normalized vertical coordinate.
 * @param FloatArray out      Destination for the transformed point.
 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
 * @return Boolean true when in grid (and written), false when outside.
 */
internal fun warpInGrid(
	cp: FloatArray,
	cols: Int,
	rows: Int,
	bilinear: Boolean,
	u: Float,
	v: Float,
	out: FloatArray,
	outIndex: Int,
): Boolean {
	if (u < 0f || v < 0f || u >= 1f || v >= 1f) {
		return false
	}

	val pointsPerRow = cols + 1
	val gridU = cols * u
	val gridV = rows * v
	val cellU = gridU - gridU.toInt()
	val cellV = gridV - gridV.toInt()
	val cellIndex = gridV.toInt() * pointsPerRow + gridU.toInt()
	latticeCellPoint(cp, pointsPerRow, bilinear, cellIndex, cellU, cellV, out, outIndex)

	return true
}

/**
 * A lattice point inside the lattice INCLUDING its far edges: [warpInGrid]'s map, with u = 1 and v = 1 landing
 * on the last column's and row's points instead of falling to the extrapolation.  For resampling a lattice onto
 * a new grid, whose far edge sits exactly on the old one's.  [u] and [v] are clamped into [0, 1].
 *
 * @param FloatArray cp       Control points, `(cols+1)*(rows+1)` interleaved x,y.
 * @param Int        cols     Grid columns.
 * @param Int        rows     Grid rows.
 * @param Boolean    bilinear Quad blend (true) vs triangle split (false).
 * @param Float      u        Normalized horizontal coordinate.
 * @param Float      v        Normalized vertical coordinate.
 * @param FloatArray out      Destination for the point.
 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
 */
internal fun latticePointAt(
	cp: FloatArray,
	cols: Int,
	rows: Int,
	bilinear: Boolean,
	u: Float,
	v: Float,
	out: FloatArray,
	outIndex: Int,
) {
	val gridU = cols * u.coerceIn(0f, 1f)
	val gridV = rows * v.coerceIn(0f, 1f)
	val cellColumn = cellOrdinal(gridU, cols)
	val cellRow = cellOrdinal(gridV, rows)
	latticeCellPoint(cp, cols + 1, bilinear, cellRow * (cols + 1) + cellColumn, gridU - cellColumn, gridV - cellRow, out, outIndex)
}

/**
 * The Jacobian of the lattice map at (u, v), in closed form from the cell it falls in - exact, where a finite
 * difference of a float map is not - written row-major into [out]: d(x)/du, d(x)/dv, d(y)/du, d(y)/dv.  The cell
 * is chosen as [latticePointAt] chooses it, so on a cell border it is the derivative of the cell the point is
 * evaluated in.  Outside the lattice (the extrapolation, which has no cell) nothing is written.
 *
 * @param FloatArray cp       Control points, `(cols+1)*(rows+1)` interleaved x,y.
 * @param Int        cols     Grid columns.
 * @param Int        rows     Grid rows.
 * @param Boolean    bilinear Quad blend (true) vs triangle split (false).
 * @param Float      u        Normalized horizontal coordinate.
 * @param Float      v        Normalized vertical coordinate.
 * @param FloatArray out      Four slots for the Jacobian.
 * @return Boolean true when (u, v) is inside the lattice, its far edges included, and the Jacobian was written.
 */
internal fun latticeJacobianAt(
	cp: FloatArray,
	cols: Int,
	rows: Int,
	bilinear: Boolean,
	u: Float,
	v: Float,
	out: FloatArray,
): Boolean {
	if (u < 0f || v < 0f || u > 1f || v > 1f) {
		return false
	}

	val pointsPerRow = cols + 1
	val gridU = cols * u
	val gridV = rows * v
	val cellColumn = cellOrdinal(gridU, cols)
	val cellRow = cellOrdinal(gridV, rows)
	val cellU = gridU - cellColumn
	val cellV = gridV - cellRow
	val cellIndex = cellRow * pointsPerRow + cellColumn
	val p00 = cellIndex * 2
	val p10 = (cellIndex + 1) * 2
	val p01 = (cellIndex + pointsPerRow) * 2
	val p11 = (cellIndex + pointsPerRow + 1) * 2

	for (axis in 0..1) {
		val alongCellU: Float
		val alongCellV: Float

		if (bilinear) {
			alongCellU = (cp[p10 + axis] - cp[p00 + axis]) * (1 - cellV) + (cp[p11 + axis] - cp[p01 + axis]) * cellV
			alongCellV = (cp[p01 + axis] - cp[p00 + axis]) * (1 - cellU) + (cp[p11 + axis] - cp[p10 + axis]) * cellU
		} else if (cellU + cellV <= 1f) {
			alongCellU = cp[p10 + axis] - cp[p00 + axis]
			alongCellV = cp[p01 + axis] - cp[p00 + axis]
		} else {
			alongCellU = cp[p11 + axis] - cp[p01 + axis]
			alongCellV = cp[p11 + axis] - cp[p10 + axis]
		}

		// A cell spans 1 / cols of u and 1 / rows of v.
		out[axis * 2] = alongCellU * cols
		out[axis * 2 + 1] = alongCellV * rows
	}

	return true
}

/**
 * The point at ([cellU], [cellV]) inside one lattice cell: the quad (bilinear) blend of its four control points,
 * or the triangle split along the cell's top-right to bottom-left diagonal.  The one formula [warpInGrid] and
 * [latticePointAt] share.
 *
 * @param FloatArray cp           Control points, interleaved x,y.
 * @param Int        pointsPerRow The lattice's columns plus one.
 * @param Boolean    bilinear     Quad blend (true) vs triangle split (false).
 * @param Int        cellIndex    The point index of the cell's top-left control point.
 * @param Float      cellU        The horizontal position inside the cell, 0 to 1.
 * @param Float      cellV        The vertical position inside the cell, 0 to 1.
 * @param FloatArray out          Destination for the point.
 * @param Int        outIndex     Index of the x slot in [out] (y is `outIndex+1`).
 */
private fun latticeCellPoint(
	cp: FloatArray,
	pointsPerRow: Int,
	bilinear: Boolean,
	cellIndex: Int,
	cellU: Float,
	cellV: Float,
	out: FloatArray,
	outIndex: Int,
) {
	val p00 = cellIndex * 2
	val p10 = (cellIndex + 1) * 2
	val p01 = (cellIndex + pointsPerRow) * 2
	val p11 = (cellIndex + pointsPerRow + 1) * 2

	if (!bilinear) {
		if (cellU + cellV <= 1f) {
			out[outIndex] = cp[p10] * cellU + cp[p00] * (1 - cellU - cellV) + cp[p01] * cellV
			out[outIndex + 1] = (1 - cellU - cellV) * cp[p00 + 1] + cp[p10 + 1] * cellU + cp[p01 + 1] * cellV
		} else {
			val weight = (cellU - 1) + cellV
			out[outIndex] = cp[p01] * (1 - cellU) + cp[p11] * weight + cp[p10] * (1 - cellV)
			out[outIndex + 1] = weight * cp[p11 + 1] + (1 - cellU) * cp[p01 + 1] + (1 - cellV) * cp[p10 + 1]
		}
	} else {
		val invCellV = 1 - cellV
		out[outIndex] =
			cp[p10] * cellU * invCellV + cp[p00] * (1 - cellU) * invCellV + cp[p01] * (1 - cellU) * cellV + cp[p11] * cellU * cellV
		out[outIndex + 1] =
			invCellV * cp[p10 + 1] * cellU + cp[p00 + 1] * (1 - cellU) * invCellV + (1 - cellU) * cp[p01 + 1] * cellV + cellU * cp[p11 + 1] * cellV
	}
}

/**
 * Out-of-grid warp extrapolation: a pure affine far outside the lattice, edge-blended near the border
 * (mode-independent). Assumes `(u, v)` is outside `[0,1)²` (call only after [warpInGrid] returned
 * false).
 *
 * @param FloatArray cp       Control points, `(cols+1)*(rows+1)` interleaved x,y.
 * @param Int        cols     Grid columns.
 * @param Int        rows     Grid rows.
 * @param Float      u        Normalized horizontal coordinate (outside `[0,1)`).
 * @param Float      v        Normalized vertical coordinate (outside `[0,1)`).
 * @param FloatArray out      Destination for the transformed point.
 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
 */
internal fun warpExtrap(cp: FloatArray, cols: Int, rows: Int, u: Float, v: Float, out: FloatArray, outIndex: Int) {
	val pointsPerRow = cols + 1
	val lastRowBase = rows * pointsPerRow
	val topLeftX = cp[0]
	val topLeftY = cp[1]
	val topRightX = cp[cols * 2]
	val topRightY = cp[cols * 2 + 1]
	val botLeftX = cp[lastRowBase * 2]
	val botLeftY = cp[lastRowBase * 2 + 1]
	val botRightX = cp[(cols + lastRowBase) * 2]
	val botRightY = cp[(cols + lastRowBase) * 2 + 1]
	val gridU = cols * u
	val gridV = rows * v
	val dUx = ((botRightX - topLeftX) + (topRightX - botLeftX)) * 0.5f
	val dVx = ((botRightX - topLeftX) - (topRightX - botLeftX)) * 0.5f
	val dUy = ((botRightY - topLeftY) + (topRightY - botLeftY)) * 0.5f
	val dVy = ((botRightY - topLeftY) - (topRightY - botLeftY)) * 0.5f
	val originX = (topLeftX + topRightX + botLeftX + botRightX) * 0.25f - (botRightX - topLeftX) * 0.5f
	val originY = (topLeftY + topRightY + botLeftY + botRightY) * 0.25f - (botRightY - topLeftY) * 0.5f

	if (u <= -2f || u >= 3f || v <= -2f || v >= 3f) {
		out[outIndex] = dUx * u + originX + dVx * v
		out[outIndex + 1] = dVy * v + dUy * u + originY

		return
	}

	var blendU = 0f
	var blendV = 0f
	var corner0x = 0f
	var corner0y = 0f
	var corner1x = 0f
	var corner1y = 0f
	var corner2x = 0f
	var corner2y = 0f
	var corner3x = 0f
	var corner3y = 0f

	if (u <= 0f) {
		blendU = (u + 2f) * 0.5f
		corner3x = originX - 2f * dUx
		corner3y = originY - 2f * dUy

		if (v <= 0f) {
			corner2x = topLeftX
			corner2y = topLeftY
			blendV = (v + 2f) * 0.5f
			corner1x = originX - 2f * dVx
			corner0x = corner3x - 2f * dVx
			corner1y = originY - 2f * dVy
			corner0y = corner3y - 2f * dVy
		} else if (v < 1f) {
			var loIdx = gridV.toInt()
			val edgeHi: Float
			val hiIdx: Int

			if (rows == loIdx) {
				loIdx = rows - 1
				hiIdx = rows
				edgeHi = rows.toFloat()
			} else {
				hiIdx = loIdx + 1
				edgeHi = (loIdx + 1).toFloat()
			}

			blendV = gridV - loIdx
			val edgeLo = loIdx.toFloat() / rows
			corner1x = cp[loIdx * pointsPerRow * 2]
			corner1y = cp[loIdx * pointsPerRow * 2 + 1]
			corner2x = cp[hiIdx * pointsPerRow * 2]
			corner2y = cp[hiIdx * pointsPerRow * 2 + 1]
			corner0x = edgeLo * dVx + corner3x
			corner0y = edgeLo * dVy + corner3y
			corner3x = (edgeHi / rows) * dVx + corner3x
			corner3y = (edgeHi / rows) * dVy + corner3y
		} else {
			corner1x = botLeftX
			corner1y = botLeftY
			blendV = (v - 1f) * 0.5f
			corner0x = corner3x + dVx
			corner0y = corner3y + dVy
			corner3x = 3f * dVx + corner3x
			corner2x = 3f * dVx + originX
			corner3y = 3f * dVy + corner3y
			corner2y = 3f * dVy + originY
		}
	} else if (u < 1f) {
		if (v <= 0f) {
			var loIdx = gridU.toInt()
			val edgeX: Float
			val edgeY: Float
			val edgeHi: Float

			if (cols == loIdx) {
				loIdx = cols - 1
				edgeX = topRightX
				edgeY = topRightY
				edgeHi = cols.toFloat()
			} else {
				edgeX = cp[(loIdx + 1) * 2]
				edgeY = cp[(loIdx + 1) * 2 + 1]
				edgeHi = (loIdx + 1).toFloat()
			}

			corner2y = edgeY
			blendV = (v + 2f) * 0.5f
			blendU = gridU - loIdx
			val edgeLo = loIdx.toFloat() / cols
			corner3x = cp[loIdx * 2]
			corner3y = cp[loIdx * 2 + 1]
			corner2x = edgeX
			corner0x = (edgeLo * dUx + originX) - 2f * dVx
			corner0y = (edgeLo * dUy + originY) - 2f * dVy
			corner1x = ((edgeHi / cols) * dUx + originX) - 2f * dVx
			corner1y = ((edgeHi / cols) * dUy + originY) - 2f * dVy
		} else if (v >= 1f) {
			var loIdx = gridU.toInt()
			val edgeHi: Float
			val hiIdx: Int

			if (cols == loIdx) {
				loIdx = cols - 1
				hiIdx = cols
				edgeHi = cols.toFloat()
			} else {
				hiIdx = loIdx + 1
				edgeHi = (loIdx + 1).toFloat()
			}

			blendV = (v - 1f) * 0.5f
			blendU = gridU - loIdx
			val edgeLo = loIdx.toFloat() / cols
			corner0x = cp[(loIdx + lastRowBase) * 2]
			corner0y = cp[(loIdx + lastRowBase) * 2 + 1]
			corner1x = cp[(lastRowBase + hiIdx) * 2]
			corner1y = cp[(lastRowBase + hiIdx) * 2 + 1]
			corner2x = (edgeHi / cols) * dUx + originX + 3f * dVx
			corner3x = edgeLo * dUx + originX + 3f * dVx
			corner3y = edgeLo * dUy + originY + 3f * dVy
			corner2y = (edgeHi / cols) * dUy + originY + 3f * dVy
		} else {
			out[outIndex] = originX + dUx * u + dVx * v
			out[outIndex + 1] = originY + dVy * v + dUy * u

			return
		}
	} else {
		blendU = (u - 1f) * 0.5f
		corner2x = 3f * dUx + originX
		corner2y = 3f * dUy + originY

		if (v <= 0f) {
			corner3x = topRightX
			corner3y = topRightY
			blendV = (v + 2f) * 0.5f
			corner0x = (originX + dUx) - 2f * dVx
			corner1x = corner2x - 2f * dVx
			corner1y = corner2y - 2f * dVy
			corner0y = (originY + dUy) - 2f * dVy
		} else if (v < 1f) {
			var loIdx = gridV.toInt()
			val edgeHi: Float
			val hiIdx: Int

			if (rows == loIdx) {
				loIdx = rows - 1
				hiIdx = rows
				edgeHi = rows.toFloat()
			} else {
				hiIdx = loIdx + 1
				edgeHi = (loIdx + 1).toFloat()
			}

			blendV = gridV - loIdx
			corner0x = cp[(loIdx * pointsPerRow + cols) * 2]
			corner0y = cp[(loIdx * pointsPerRow + cols) * 2 + 1]
			corner3x = cp[(hiIdx * pointsPerRow + cols) * 2]
			corner3y = cp[(hiIdx * pointsPerRow + cols) * 2 + 1]
			val edgeLo = loIdx.toFloat() / rows
			corner1x = edgeLo * dVx + corner2x
			corner1y = edgeLo * dVy + corner2y
			corner2x = (edgeHi / rows) * dVx + corner2x
			corner2y = (edgeHi / rows) * dVy + corner2y
		} else {
			corner0x = botRightX
			corner0y = botRightY
			blendV = (v - 1f) * 0.5f
			corner1x = corner2x + dVx
			corner1y = corner2y + dVy
			corner3x = originX + dUx + 3f * dVx
			corner2x = 3f * dVx + corner2x
			corner3y = originY + dUy + 3f * dVy
			corner2y = 3f * dVy + corner2y
		}
	}

	if (blendU + blendV <= 1f) {
		out[outIndex] = (corner1x - corner0x) * blendU + corner0x + (corner3x - corner0x) * blendV
		out[outIndex + 1] = (corner1y - corner0y) * blendU + corner0y + (corner3y - corner0y) * blendV
	} else {
		out[outIndex] = (corner3x - corner2x) * (1f - blendU) + corner2x + (corner1x - corner2x) * (1f - blendV)
		out[outIndex + 1] = (1f - blendU) * (corner3y - corner2y) + corner2y + (1f - blendV) * (corner1y - corner2y)
	}
}

/**
 * Warp-transforms `(u, v)` through [cp]: the in-grid fast path, falling back to extrapolation outside.
 *
 * @param FloatArray cp       Control points, `(cols+1)*(rows+1)` interleaved x,y.
 * @param Int        cols     Grid columns.
 * @param Int        rows     Grid rows.
 * @param Boolean    bilinear Quad blend (true) vs triangle split (false).
 * @param Float      u        Normalized horizontal coordinate.
 * @param Float      v        Normalized vertical coordinate.
 * @param FloatArray out      Destination for the transformed point.
 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
 */
internal fun warpApply(
	cp: FloatArray,
	cols: Int,
	rows: Int,
	bilinear: Boolean,
	u: Float,
	v: Float,
	out: FloatArray,
	outIndex: Int,
) {
	if (!warpInGrid(cp, cols, rows, bilinear, u, v, out, outIndex)) {
		warpExtrap(cp, cols, rows, u, v, out, outIndex)
	}
}

/**
 * A rotation deformer's baked world affine - the per-vertex-invariant coefficients (xformSetup's exact
 * grouping), applied as `x' = c15·y + c12·x + ox`, `y' = c13·y + c14·x + oy`.
 */
internal class RotationXform(
	val c12: Float,
	val c13: Float,
	val c14: Float,
	val c15: Float,
	val ox: Float,
	val oy: Float,
) {
	/**
	 * Applies the affine to point `(x, y)`, writing the result to `out[outIndex]`,`out[outIndex+1]`.
	 *
	 * @param Float      x        Input x.
	 * @param Float      y        Input y.
	 * @param FloatArray out      Destination for the transformed point.
	 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
	 */
	fun apply(x: Float, y: Float, out: FloatArray, outIndex: Int) {
		out[outIndex] = c15 * y + c12 * x + ox
		out[outIndex + 1] = y * c13 + x * c14 + oy
	}
}

/**
 * Builds a [RotationXform] from a rotation deformer's world angle (degrees), scale, reflection flags,
 * and origin.
 *
 * @param Float   angleDegrees World rotation angle in degrees.
 * @param Float   scale        World scale.
 * @param Boolean flipX        Reflect across the vertical axis.
 * @param Boolean flipY        Reflect across the horizontal axis.
 * @param Float   ox           World origin x.
 * @param Float   oy           World origin y.
 * @return RotationXform The baked affine.
 */
internal fun rotationXform(
	angleDegrees: Float,
	scale: Float,
	flipX: Boolean,
	flipY: Boolean,
	ox: Float,
	oy: Float,
): RotationXform {
	val radians = angleDegrees * PI_F / 180f
	val sn = sin(radians.toDouble()).toFloat()
	val cs = cos(radians.toDouble()).toFloat()
	val fx = if (flipX) -1f else 1f
	val fy = if (flipY) -1f else 1f

	return RotationXform(
		c12 = cs * scale * fx,
		c13 = fy * cs * scale,
		c14 = fx * scale * sn,
		c15 = -sn * scale * fy,
		ox = ox,
		oy = oy,
	)
}