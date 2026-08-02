package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.CAffine

/**
 * The texture web a synthesized drawable binds to when it has no existing source to clone - the
 * per-page objects the image-chain builder created (docs/format/CMO3.md §4 How a Drawable
 * References its Texture).  Instances are shared per atlas page: every drawable on the page
 * references the SAME GTexture2D and guid objects, so the writer hoists them exactly like the
 * editor's own files (one atlas GTexture2D for all packed drawables).
 */
public class Cmo3DrawableTextureBinding(
	/** The page's shared texture (srcImageResource = the page CImageResource). */
	val texture: GTexture2D,
	/** The page's CTextureAtlas guid - the atlas-region input's target. */
	val textureAtlasGuid: Guid,
	/** The drawable's own CModelImage guid (its patch web), or null when it has no patch. */
	val modelImageGuid: Guid?,
	/** The drawable's fitted atlas-page-to-canvas placement (the region input's transform). */
	val inputImageLocalToCanvasTransform: CAffine,
)

/**
 * Fits a drawable's atlas-page-to-canvas placement transform: the affine mapping page pixel
 * coordinates (uv times page size) onto the drawable's base mesh positions.
 *
 * CMO3: CTextureInput_TextureAtlasRegion field inputImageLocalToCanvasTransform - the editor
 * inverts this to place the mesh over its texture patch in the atlas and mesh-edit views, so an
 * identity here draws every mesh at its assembled canvas position instead.  Cubism-authored
 * mappings are exact per-drawable affines (the layer's canvas translation composed with the
 * packer's scale+translate), so a least-squares fit recovers them to float precision; a
 * degenerate mesh falls back to per-axis scale+offset, then to a pure centroid translation.
 *
 * @param FloatArray uvs        Interleaved atlas-frame texture coordinates (u then v per vertex).
 * @param FloatArray positions  Interleaved base canvas positions (x then y per vertex).
 * @param Int        pageWidth  The atlas page's pixel width.
 * @param Int        pageHeight The atlas page's pixel height.
 * @return CAffine The fitted transform (identity when there are no vertices).
 */
internal fun fitAtlasPageToCanvasTransform(uvs: FloatArray, positions: FloatArray, pageWidth: Int, pageHeight: Int): CAffine {
	val vertexCount = minOf(uvs.size, positions.size) / 2
	if (vertexCount == 0) {
		return CAffine()
	}
	var sumPageX = 0.0
	var sumPageY = 0.0
	var sumPageXX = 0.0
	var sumPageXY = 0.0
	var sumPageYY = 0.0
	val sumCanvas = DoubleArray(2)
	val sumCanvasPageX = DoubleArray(2)
	val sumCanvasPageY = DoubleArray(2)
	for (vertexIndex in 0 until vertexCount) {
		val pageX = uvs[2 * vertexIndex].toDouble() * pageWidth
		val pageY = uvs[2 * vertexIndex + 1].toDouble() * pageHeight
		sumPageX += pageX
		sumPageY += pageY
		sumPageXX += pageX * pageX
		sumPageXY += pageX * pageY
		sumPageYY += pageY * pageY
		for (axis in 0 until 2) {
			val canvas = positions[2 * vertexIndex + axis].toDouble()
			sumCanvas[axis] += canvas
			sumCanvasPageX[axis] += canvas * pageX
			sumCanvasPageY[axis] += canvas * pageY
		}
	}
	val pointCount = vertexCount.toDouble()
	// Full 6-dof fit: per target axis, solve the 3x3 normal equations for (a, b, t) in
	// canvas = a*pageX + b*pageY + t.
	val rows = Array(2) { axis -> solveThreeByThree(sumPageXX, sumPageXY, sumPageX, sumPageYY, sumPageY, pointCount, sumCanvasPageX[axis], sumCanvasPageY[axis], sumCanvas[axis]) }
	// A near-identity linear part is least-squares noise on a pure translation (the editor's own
	// unscaled packings): snap it and refit the translation as the mean offset, so the written
	// transform matches the editor's exact-translation shape instead of 0.9999997-style drift.
	val fittedRowX = rows[0]
	val fittedRowY = rows[1]
	if (fittedRowX != null && fittedRowY != null &&
		kotlin.math.abs(fittedRowX[0] - 1.0) < 1e-3 && kotlin.math.abs(fittedRowX[1]) < 1e-3 &&
		kotlin.math.abs(fittedRowY[0]) < 1e-3 && kotlin.math.abs(fittedRowY[1] - 1.0) < 1e-3
	) {
		rows[0] = doubleArrayOf(1.0, 0.0, (sumCanvas[0] - sumPageX) / pointCount)
		rows[1] = doubleArrayOf(0.0, 1.0, (sumCanvas[1] - sumPageY) / pointCount)
	}
	val meanPage = doubleArrayOf(sumPageX / pointCount, sumPageY / pointCount)
	val transform = CAffine()
	for (axis in 0 until 2) {
		var row = rows[axis]
		if (row == null) {
			// Per-axis fallback: canvas = s*page + t along the matching page axis alone.
			val (sumPage, sumPageSquared, sumCanvasPage) =
				if (axis == 0) {
					Triple(sumPageX, sumPageXX, sumCanvasPageX[axis])
				} else {
					Triple(sumPageY, sumPageYY, sumCanvasPageY[axis])
				}
			val denominator = pointCount * sumPageSquared - sumPage * sumPage
			row =
				if (denominator > 1e-9 * pointCount * maxOf(sumPageSquared, 1.0)) {
					val scale = (pointCount * sumCanvasPage - sumPage * sumCanvas[axis]) / denominator
					val offset = (sumCanvas[axis] - scale * sumPage) / pointCount
					if (axis == 0) doubleArrayOf(scale, 0.0, offset) else doubleArrayOf(0.0, scale, offset)
				} else {
					// Zero page span: centroid translation at unit scale.
					val offset = sumCanvas[axis] / pointCount - meanPage[axis]
					if (axis == 0) doubleArrayOf(1.0, 0.0, offset) else doubleArrayOf(0.0, 1.0, offset)
				}
		}
		if (axis == 0) {
			transform.m00 = row[0].toFloat()
			transform.m01 = row[1].toFloat()
			transform.m02 = row[2].toFloat()
		} else {
			transform.m10 = row[0].toFloat()
			transform.m11 = row[1].toFloat()
			transform.m12 = row[2].toFloat()
		}
	}
	return transform
}

/**
 * Solves the symmetric 3x3 normal system of the affine fit by Gaussian elimination.
 *
 * @param Double sumXX Sum of pageX*pageX.
 * @param Double sumXY Sum of pageX*pageY.
 * @param Double sumX  Sum of pageX.
 * @param Double sumYY Sum of pageY*pageY.
 * @param Double sumY  Sum of pageY.
 * @param Double count The vertex count.
 * @param Double rhs0  Sum of canvas*pageX.
 * @param Double rhs1  Sum of canvas*pageY.
 * @param Double rhs2  Sum of canvas.
 * @return DoubleArray The (a, b, t) solution, or null when the system is singular.
 */
private fun solveThreeByThree(sumXX: Double, sumXY: Double, sumX: Double, sumYY: Double, sumY: Double, count: Double, rhs0: Double, rhs1: Double, rhs2: Double): DoubleArray? {
	val matrix =
		arrayOf(
			doubleArrayOf(sumXX, sumXY, sumX, rhs0),
			doubleArrayOf(sumXY, sumYY, sumY, rhs1),
			doubleArrayOf(sumX, sumY, count, rhs2),
		)
	val scale = maxOf(kotlin.math.abs(sumXX), kotlin.math.abs(sumYY), count, 1.0)
	for (pivotIndex in 0 until 3) {
		var bestRow = pivotIndex
		for (rowIndex in pivotIndex + 1 until 3) {
			if (kotlin.math.abs(matrix[rowIndex][pivotIndex]) > kotlin.math.abs(matrix[bestRow][pivotIndex])) {
				bestRow = rowIndex
			}
		}
		if (kotlin.math.abs(matrix[bestRow][pivotIndex]) < 1e-9 * scale) {
			return null
		}
		val swap = matrix[pivotIndex]
		matrix[pivotIndex] = matrix[bestRow]
		matrix[bestRow] = swap
		for (rowIndex in pivotIndex + 1 until 3) {
			val factor = matrix[rowIndex][pivotIndex] / matrix[pivotIndex][pivotIndex]
			for (columnIndex in pivotIndex until 4) {
				matrix[rowIndex][columnIndex] -= factor * matrix[pivotIndex][columnIndex]
			}
		}
	}
	val solution = DoubleArray(3)
	for (rowIndex in 2 downTo 0) {
		var value = matrix[rowIndex][3]
		for (columnIndex in rowIndex + 1 until 3) {
			value -= matrix[rowIndex][columnIndex] * solution[columnIndex]
		}
		solution[rowIndex] = value / matrix[rowIndex][rowIndex]
	}
	return solution
}
