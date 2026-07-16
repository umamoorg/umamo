package org.umamo.format.art

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the alpha-analysis entry points over synthetic pixels: trimmed bounds, the
 * occupancy count, threshold semantics (including the signed-byte alpha trap), the
 * null-when-empty contract, argument validation, and the convenience extensions.  Contour
 * geometry has its own suite in AlphaContourTest; simplification in ContourSimplifyTest.
 */
class AlphaAnalysisTest {
	@Test
	fun fullyTransparentYieldsNull() {
		// Nonzero RGB with zero alpha must not count: only the alpha channel decides.
		assertNull(rasterOfRows("...", "...").analyzeAlpha())
	}

	@Test
	fun zeroSizedRasterYieldsNull() {
		assertNull(analyzeAlpha(0, 0, ByteArray(0)))
		assertNull(analyzeAlpha(3, 0, ByteArray(0)))
	}

	@Test
	fun singleOpaquePixelHasUnitBounds() {
		val analysis = assertNotNull(rasterOfRows("....", "..#.", "....").analyzeAlpha())
		assertEquals(LayerBounds(left = 2, top = 1, width = 1, height = 1), analysis.opaqueBounds)
		assertEquals(1, analysis.opaquePixelCount)
		assertEquals(1f, analysis.boundsCoverage)
	}

	@Test
	fun fullyOpaqueRectCoversItsBounds() {
		val analysis = assertNotNull(rasterOfRows("###", "###").analyzeAlpha())
		assertEquals(LayerBounds(left = 0, top = 0, width = 3, height = 2), analysis.opaqueBounds)
		assertEquals(6, analysis.opaquePixelCount)
		assertEquals(1f, analysis.boundsCoverage)
	}

	@Test
	fun offsetRectangleTrimsExactly() {
		val raster =
			rasterOfRows(
				"......",
				"..###.",
				"..###.",
				"..###.",
				"......",
			)
		val analysis = assertNotNull(raster.analyzeAlpha())
		assertEquals(LayerBounds(left = 2, top = 1, width = 3, height = 3), analysis.opaqueBounds)
		assertEquals(9, analysis.opaquePixelCount)
		assertNoOpaquePixelOutside(raster, analysis.opaqueBounds, DEFAULT_ALPHA_THRESHOLD)
	}

	@Test
	fun coverageReflectsPartialOccupancy() {
		// L-shape: 3 opaque pixels in a 2x2 trimmed rect.
		val analysis = assertNotNull(rasterOfRows("#.", "##").analyzeAlpha())
		assertEquals(LayerBounds(left = 0, top = 0, width = 2, height = 2), analysis.opaqueBounds)
		assertEquals(3, analysis.opaquePixelCount)
		assertEquals(0.75f, analysis.boundsCoverage)
	}

	@Test
	fun defaultThresholdCountsAnyNonzeroAlpha() {
		val analysis = assertNotNull(rasterOfAlphas(0, 1).analyzeAlpha())
		assertEquals(LayerBounds(left = 1, top = 0, width = 1, height = 1), analysis.opaqueBounds)
		assertEquals(1, analysis.opaquePixelCount)
	}

	@Test
	fun customThresholdIncludesExactValueAndExcludesBelow() {
		val analysis = assertNotNull(rasterOfAlphas(127, 128, 200, 255).analyzeAlpha(alphaThreshold = 128))
		assertEquals(LayerBounds(left = 1, top = 0, width = 3, height = 1), analysis.opaqueBounds)
		assertEquals(3, analysis.opaquePixelCount)
	}

	@Test
	fun highAlphaValuesSurviveSignedByteStorage() {
		// 128..255 read negative from a Kotlin Byte without masking; all four must count.
		val analysis = assertNotNull(rasterOfAlphas(127, 128, 200, 255).analyzeAlpha())
		assertEquals(4, analysis.opaquePixelCount)
	}

	@Test
	fun sliversKeepTheirTrueBounds() {
		// A 1 px wide layer with real opaque pixels is reported, never excluded here —
		// exclusion is consumer policy (roadmap Phase B decision).
		val column = assertNotNull(rasterOfRows("#", "#", "#", "#", "#").analyzeAlpha())
		assertEquals(LayerBounds(left = 0, top = 0, width = 1, height = 5), column.opaqueBounds)
		assertEquals(1, column.contours.size)
		assertTrue(column.contours[0].points.size >= 6, "sliver contour still has at least 3 points")

		val row = assertNotNull(rasterOfRows("#####").analyzeAlpha())
		assertEquals(LayerBounds(left = 0, top = 0, width = 5, height = 1), row.opaqueBounds)
		assertEquals(5, row.opaquePixelCount)
	}

	@Test
	fun rejectsMalformedArguments() {
		assertFailsWith<IllegalArgumentException> { analyzeAlpha(-1, 1, ByteArray(0)) }
		assertFailsWith<IllegalArgumentException> { analyzeAlpha(2, 2, ByteArray(15)) }
		assertFailsWith<IllegalArgumentException> { analyzeAlpha(1, 1, ByteArray(4), alphaThreshold = 0) }
		assertFailsWith<IllegalArgumentException> { analyzeAlpha(1, 1, ByteArray(4), alphaThreshold = 256) }
		assertFailsWith<IllegalArgumentException> { analyzeAlpha(1, 1, ByteArray(4), contourEpsilon = -1f) }
	}

	@Test
	fun extensionsAgreeWithThePositionalCore() {
		val raster = rasterOfRows("##.", ".#.")
		val fromExtension = assertNotNull(raster.analyzeAlpha())
		val fromPositional = assertNotNull(analyzeAlpha(raster.width, raster.height, raster.rgba))
		assertEquals(fromPositional.opaqueBounds, fromExtension.opaqueBounds)
		assertEquals(fromPositional.opaquePixelCount, fromExtension.opaquePixelCount)
		assertEquals(fromPositional.contours.size, fromExtension.contours.size)
		for (contourIndex in fromPositional.contours.indices) {
			assertContentEquals(
				fromPositional.contours[contourIndex].points,
				fromExtension.contours[contourIndex].points,
			)
			assertEquals(
				fromPositional.contours[contourIndex].isHole,
				fromExtension.contours[contourIndex].isHole,
			)
		}
	}

	@Test
	fun sourceLayerAnalysisIgnoresCompositingProperties() {
		val raster = rasterOfRows(".#.", "###")
		val opaqueVisible = TestSourceLayer(raster, opacity = 1f, visible = true)
		val faintHidden = TestSourceLayer(raster, opacity = 0.1f, visible = false)
		val first = assertNotNull(opaqueVisible.analyzeAlpha())
		val second = assertNotNull(faintHidden.analyzeAlpha())
		assertEquals(first.opaqueBounds, second.opaqueBounds)
		assertEquals(first.opaquePixelCount, second.opaquePixelCount)
	}

	@Test
	fun opaqueBoundsOnCanvasOffsetsByLayerPosition() {
		val analysis =
			assertNotNull(
				rasterOfRows(
					"......",
					"..###.",
					"..###.",
					"..###.",
					"......",
				).analyzeAlpha(),
			)
		assertEquals(
			LayerBounds(left = 102, top = 51, width = 3, height = 3),
			analysis.opaqueBoundsOnCanvas(LayerBounds(left = 100, top = 50, width = 6, height = 5)),
		)
	}

	/**
	 * Minimal SourceLayer over a synthetic raster, with the compositing properties the
	 * analysis must ignore as the only variables.
	 *
	 * @param LayerRaster raster The pixels under analysis.
	 * @param Float opacity Layer opacity, ignored by the analysis.
	 * @param Boolean visible Layer visibility, ignored by the analysis.
	 */
	private class TestSourceLayer(
		override val raster: LayerRaster,
		override val opacity: Float,
		override val visible: Boolean,
	) : SourceLayer {
		override val id = LayerId("test-layer")
		override val name = "test-layer"
		override val groupPath = ""
		override val order = 0
		override val bounds = LayerBounds(left = 7, top = 9, width = raster.width, height = raster.height)
		override val clipped = false
		override val blend = LayerBlend.Normal
	}
}
