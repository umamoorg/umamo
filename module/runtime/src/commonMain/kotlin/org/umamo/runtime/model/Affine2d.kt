package org.umamo.runtime.model

import kotlin.math.cos
import kotlin.math.sin

/*
 * The 2x3 affine algebra every 2D transform in the project shares: an atlas placement, a uv frame, a whole
 * object moved in world space, a warp's every lattice moved as one.  Each affine is row-major
 * (m00, m01, m02, m10, m11, m12): x' = m00 x + m01 y + m02, y' = m10 x + m11 y + m12, in whatever space the
 * caller's points are in - the builders here know no frame, so a y-down page and a z-up world read them alike.
 */

/** The affine that changes nothing. */
fun identityAffine(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

/**
 * The translation by ([deltaX], [deltaY]).
 *
 * @param Float deltaX The move along the space's first axis.
 * @param Float deltaY The move along its second axis.
 * @return FloatArray The affine.
 */
fun translationAffine(deltaX: Float, deltaY: Float): FloatArray = floatArrayOf(1f, 0f, deltaX, 0f, 1f, deltaY)

/**
 * The rotation by [radians] about ([pivotX], [pivotY]), turning the first axis toward the second.
 *
 * @param Float pivotX The pivot on the first axis.
 * @param Float pivotY The pivot on the second axis.
 * @param Float radians The angle.
 * @return FloatArray The affine.
 */
fun rotationAboutAffine(pivotX: Float, pivotY: Float, radians: Float): FloatArray {
	val cosine = cos(radians)
	val sine = sin(radians)

	return floatArrayOf(
		cosine,
		-sine,
		pivotX - cosine * pivotX + sine * pivotY,
		sine,
		cosine,
		pivotY - sine * pivotX - cosine * pivotY,
	)
}

/**
 * The scale by ([factorX], [factorY]) about ([pivotX], [pivotY]), along the space's own axes.
 *
 * @param Float pivotX The pivot on the first axis.
 * @param Float pivotY The pivot on the second axis.
 * @param Float factorX The factor along the first axis.
 * @param Float factorY The factor along the second axis.
 * @return FloatArray The affine.
 */
fun scaleAboutAffine(pivotX: Float, pivotY: Float, factorX: Float, factorY: Float): FloatArray =
	floatArrayOf(factorX, 0f, pivotX - factorX * pivotX, 0f, factorY, pivotY - factorY * pivotY)

/**
 * Composes two 2x3 affines: the result applies [inner] first, then [outer].
 *
 * @param FloatArray outer The affine applied second.
 * @param FloatArray inner The affine applied first.
 * @return FloatArray The composition, a fresh array.
 */
fun composeAffine(outer: FloatArray, inner: FloatArray): FloatArray =
	floatArrayOf(
		outer[0] * inner[0] + outer[1] * inner[3],
		outer[0] * inner[1] + outer[1] * inner[4],
		outer[0] * inner[2] + outer[1] * inner[5] + outer[2],
		outer[3] * inner[0] + outer[4] * inner[3],
		outer[3] * inner[1] + outer[4] * inner[4],
		outer[3] * inner[2] + outer[4] * inner[5] + outer[5],
	)

/**
 * Inverts a 2x3 affine, turning a mapping INTO a frame into the mapping back OUT of it.
 *
 * @param FloatArray affine The affine to invert (m00, m01, m02, m10, m11, m12).
 * @return FloatArray? The inverse, or null when the affine is degenerate (a zero determinant).
 */
fun invertAffine(affine: FloatArray): FloatArray? {
	if (affine.size < 6) {
		return null
	}

	val determinant = affine[0] * affine[4] - affine[1] * affine[3]

	if (determinant == 0f) {
		return null
	}

	val m00 = affine[4] / determinant
	val m01 = -affine[1] / determinant
	val m10 = -affine[3] / determinant
	val m11 = affine[0] / determinant

	return floatArrayOf(
		m00,
		m01,
		-(m00 * affine[2] + m01 * affine[5]),
		m10,
		m11,
		-(m10 * affine[2] + m11 * affine[5]),
	)
}

/**
 * Applies a 2x3 affine to a whole interleaved point array.  A trailing odd component is left out rather than
 * half-mapped: the arrays are interleaved pairs, so an odd length is malformed input.
 *
 * @param FloatArray points The interleaved (x, y) pairs to map.
 * @param FloatArray affine The affine to apply.
 * @return FloatArray The mapped pairs, a fresh array of the same length.
 */
fun applyAffine(points: FloatArray, affine: FloatArray): FloatArray {
	val mapped = FloatArray(points.size)
	var componentIndex = 0

	while (componentIndex + 1 < points.size) {
		val x = points[componentIndex]
		val y = points[componentIndex + 1]
		mapped[componentIndex] = affine[0] * x + affine[1] * y + affine[2]
		mapped[componentIndex + 1] = affine[3] * x + affine[4] * y + affine[5]
		componentIndex += 2
	}

	return mapped
}