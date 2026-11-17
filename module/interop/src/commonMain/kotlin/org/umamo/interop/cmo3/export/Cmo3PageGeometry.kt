package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.type.CAffine

/*
 * Page-space geometry of a packed drawable: the page rect its uvs span, and the fit that places a page
 * on the canvas.  Pure arithmetic, shared by the crop web and the twin colocation that runs before it.
 */

/**
 * A drawable's pixel-aligned texture-patch rect on its page, from its atlas-frame uv bounds.
 *
 * Mesh margins may reach slightly outside [0,1]; the rect is clamped to the page and forced to
 * at least one pixel.
 *
 * @param FloatArray uvs        Interleaved atlas-frame uvs.
 * @param Int        pageWidth  The page's pixel width.
 * @param Int        pageHeight The page's pixel height.
 * @return IntArray? [x0, y0, x1, y1] (exclusive max), or null when there are no uvs.
 */
internal fun patchRectOf(uvs: FloatArray, pageWidth: Int, pageHeight: Int): IntArray? {
	if (uvs.size < 2) {
		return null
	}

	var minU = Float.POSITIVE_INFINITY
	var minV = Float.POSITIVE_INFINITY
	var maxU = Float.NEGATIVE_INFINITY
	var maxV = Float.NEGATIVE_INFINITY
	var componentIndex = 0

	while (componentIndex + 1 < uvs.size) {
		minU = minOf(minU, uvs[componentIndex])
		maxU = maxOf(maxU, uvs[componentIndex])
		minV = minOf(minV, uvs[componentIndex + 1])
		maxV = maxOf(maxV, uvs[componentIndex + 1])
		componentIndex += 2
	}

	if (!minU.isFinite() || !minV.isFinite() || !maxU.isFinite() || !maxV.isFinite()) {
		return null
	}

	val x0 = kotlin.math.floor(minU * pageWidth).toInt().coerceIn(0, pageWidth - 1)
	val y0 = kotlin.math.floor(minV * pageHeight).toInt().coerceIn(0, pageHeight - 1)
	val x1 = kotlin.math.ceil(maxU * pageWidth).toInt().coerceIn(x0 + 1, pageWidth)
	val y1 = kotlin.math.ceil(maxV * pageHeight).toInt().coerceIn(y0 + 1, pageHeight)

	return intArrayOf(x0, y0, x1, y1)
}

/**
 * Fits a drawable's atlas-page-to-canvas placement transform: the least-squares affine mapping page
 * pixel coordinates (uv times page size) onto the drawable's base mesh positions.
 *
 * CMO3: CTextureInput_TextureAtlasRegion field inputImageLocalToCanvasTransform - the editor
 * inverts this to place the mesh over its texture patch in the atlas and mesh-edit views, so an
 * identity here draws every mesh at its assembled canvas position instead.  The fit exists only
 * because a MOC3-origin export has to RECONSTRUCT the placement a CMO3 would have read straight off
 * its source art.  A drawable under a warp deformer keeps a residual by construction, since the
 * warp lattice's bilinear map is not affine; the closest affine is what the atlas view needs.
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

	val pointCount = vertexCount.toDouble()
	var meanPageX = 0.0
	var meanPageY = 0.0
	val meanCanvas = DoubleArray(2)

	for (vertexIndex in 0 until vertexCount) {
		meanPageX += uvs[2 * vertexIndex].toDouble() * pageWidth
		meanPageY += uvs[2 * vertexIndex + 1].toDouble() * pageHeight

		for (axis in 0 until 2) {
			meanCanvas[axis] += positions[2 * vertexIndex + axis].toDouble()
		}
	}

	meanPageX /= pointCount
	meanPageY /= pointCount
	meanCanvas[0] /= pointCount
	meanCanvas[1] /= pointCount
	// Centered second moments: the page cloud's own covariance plus its cross-covariance with each
	// canvas axis.  These are the normal equations of `canvas = a*pageX + b*pageY + t` with t
	// eliminated, which is exactly what centering buys: moments about the page origin, for a patch a
	// few hundred pixels wide sitting thousands out on a large page, would be dominated by the offset
	// and fail the degeneracy test below, squashing a rotated packing onto one axis.
	var pageXX = 0.0
	var pageXY = 0.0
	var pageYY = 0.0
	val canvasPageX = DoubleArray(2)
	val canvasPageY = DoubleArray(2)

	for (vertexIndex in 0 until vertexCount) {
		val pageX = uvs[2 * vertexIndex].toDouble() * pageWidth - meanPageX
		val pageY = uvs[2 * vertexIndex + 1].toDouble() * pageHeight - meanPageY
		pageXX += pageX * pageX
		pageXY += pageX * pageY
		pageYY += pageY * pageY

		for (axis in 0 until 2) {
			val canvas = positions[2 * vertexIndex + axis].toDouble() - meanCanvas[axis]
			canvasPageX[axis] += pageX * canvas
			canvasPageY[axis] += pageY * canvas
		}
	}

	val determinant = pageXX * pageYY - pageXY * pageXY
	// Relative test: the page cloud spans two independent directions only when its covariance
	// determinant is a real fraction of the product of its variances.  A patch collapsed to a line
	// (a degenerate mesh) fails here and takes the single-axis path below.
	val hasTwoAxisSpread = determinant > 1e-12 * pageXX * pageYY
	val linear = Array(2) { DoubleArray(2) }

	for (axis in 0 until 2) {
		if (hasTwoAxisSpread) {
			linear[axis][0] = (canvasPageX[axis] * pageYY - canvasPageY[axis] * pageXY) / determinant
			linear[axis][1] = (canvasPageY[axis] * pageXX - canvasPageX[axis] * pageXY) / determinant

			continue
		}

		// Collinear page cloud: regress against whichever page axis actually carries the spread, so
		// a rotated strip still recovers its coefficient instead of dividing by nothing.  The other
		// column is unconstrained by the data and takes its identity value rather than zero - a zero
		// column would make the affine singular, and the editor INVERTS this transform to draw the
		// mesh over its patch.
		if (pageXX >= pageYY && pageXX > 0.0) {
			linear[axis][0] = canvasPageX[axis] / pageXX
			linear[axis][1] = if (axis == 1) 1.0 else 0.0
		} else if (pageYY > 0.0) {
			linear[axis][0] = if (axis == 0) 1.0 else 0.0
			linear[axis][1] = canvasPageY[axis] / pageYY
		} else {
			// Every vertex shares one page pixel: nothing to scale, so place by the centroid alone.
			linear[axis][0] = if (axis == 0) 1.0 else 0.0
			linear[axis][1] = if (axis == 0) 0.0 else 1.0
		}
	}

	// A near-identity linear part is least-squares noise on a pure translation (the editor's own
	// unscaled packings): snap it so the written transform matches the editor's exact-translation
	// shape instead of 0.9999997-style drift.
	if (kotlin.math.abs(linear[0][0] - 1.0) < 1e-3 &&
		kotlin.math.abs(linear[0][1]) < 1e-3 &&
		kotlin.math.abs(linear[1][0]) < 1e-3 &&
		kotlin.math.abs(linear[1][1] - 1.0) < 1e-3
	) {
		linear[0][0] = 1.0
		linear[0][1] = 0.0
		linear[1][0] = 0.0
		linear[1][1] = 1.0
	}

	val transform = CAffine()
	// The translation is whatever carries the page mean onto the canvas mean under the linear part.
	transform.m00 = linear[0][0].toFloat()
	transform.m01 = linear[0][1].toFloat()
	transform.m02 = (meanCanvas[0] - linear[0][0] * meanPageX - linear[0][1] * meanPageY).toFloat()
	transform.m10 = linear[1][0].toFloat()
	transform.m11 = linear[1][1].toFloat()
	transform.m12 = (meanCanvas[1] - linear[1][0] * meanPageX - linear[1][1] * meanPageY).toFloat()

	return transform
}