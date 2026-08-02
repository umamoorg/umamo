package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.type.CAffine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The atlas-page-to-canvas placement fit behind CTextureInput_TextureAtlasRegion's
 * inputImageLocalToCanvasTransform: a least-squares affine from uv*pageSize onto base positions,
 * recovered exactly for Cubism-authored (exactly affine) mappings and degraded gracefully for
 * degenerate meshes.
 */
class Cmo3AtlasPlacementFitTest {
	/**
	 * Applies the affine to a page-frame point.
	 *
	 * @param CAffine transform The fitted transform.
	 * @param Double  pageX     Page-frame x.
	 * @param Double  pageY     Page-frame y.
	 * @return Pair The mapped canvas point.
	 */
	private fun apply(transform: CAffine, pageX: Double, pageY: Double): Pair<Double, Double> =
		Pair(
			transform.m00 * pageX + transform.m01 * pageY + transform.m02,
			transform.m10 * pageX + transform.m11 * pageY + transform.m12,
		)

	@Test
	fun recoversAnExactAffineMappingIncludingAFlip() {
		// canvasX = 0.002*pageX - 1.5; canvasY = -0.002*pageY + 3.25 (a y-flip, the MOC3 case).
		val uvs = floatArrayOf(0.1f, 0.2f, 0.6f, 0.2f, 0.1f, 0.9f, 0.6f, 0.9f, 0.35f, 0.55f)
		val pageWidth = 4096
		val pageHeight = 2048
		val positions =
			FloatArray(uvs.size) { index ->
				val vertexIndex = index / 2
				if (index % 2 == 0) {
					(0.002 * uvs[2 * vertexIndex] * pageWidth - 1.5).toFloat()
				} else {
					(-0.002 * uvs[2 * vertexIndex + 1] * pageHeight + 3.25).toFloat()
				}
			}
		val transform = fitAtlasPageToCanvasTransform(uvs, positions, pageWidth, pageHeight)
		assertEquals(0.002f, transform.m00, 1e-6f, "x scale")
		assertEquals(0f, transform.m01, 1e-6f, "x shear")
		assertEquals(-1.5f, transform.m02, 1e-4f, "x offset")
		assertEquals(0f, transform.m10, 1e-6f, "y shear")
		assertEquals(-0.002f, transform.m11, 1e-6f, "y scale (flip)")
		assertEquals(3.25f, transform.m12, 1e-4f, "y offset")
	}

	@Test
	fun degenerateVerticalSpanStillFitsTheHorizontalAxis() {
		// All v identical: the full 3x3 system is singular; x must still fit exactly and y must
		// land on the centroid.
		val uvs = floatArrayOf(0.0f, 0.5f, 0.25f, 0.5f, 1.0f, 0.5f)
		val positions = floatArrayOf(10f, 7f, 12.5f, 7f, 20f, 7f)
		val transform = fitAtlasPageToCanvasTransform(uvs, positions, 100, 100)
		for (vertexIndex in 0 until 3) {
			val (canvasX, canvasY) = apply(transform, uvs[2 * vertexIndex] * 100.0, uvs[2 * vertexIndex + 1] * 100.0)
			assertEquals(positions[2 * vertexIndex].toDouble(), canvasX, 1e-3, "x of vertex $vertexIndex")
			assertEquals(7.0, canvasY, 1e-3, "y of vertex $vertexIndex")
		}
	}

	@Test
	fun singlePointMapsByCentroidTranslation() {
		val transform = fitAtlasPageToCanvasTransform(floatArrayOf(0.5f, 0.25f), floatArrayOf(42f, -7f), 200, 400)
		val (canvasX, canvasY) = apply(transform, 100.0, 100.0)
		assertEquals(42.0, canvasX, 1e-3)
		assertEquals(-7.0, canvasY, 1e-3)
	}

	@Test
	fun emptyMeshYieldsIdentity() {
		val transform = fitAtlasPageToCanvasTransform(FloatArray(0), FloatArray(0), 1024, 1024)
		assertTrue(
			transform.m00 == 1f && transform.m01 == 0f && transform.m02 == 0f &&
				transform.m10 == 0f && transform.m11 == 1f && transform.m12 == 0f,
			"identity for an empty mesh",
		)
	}
}
