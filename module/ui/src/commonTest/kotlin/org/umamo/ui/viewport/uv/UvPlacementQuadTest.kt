package org.umamo.ui.viewport.uv

import org.umamo.format.art.LayerBounds
import org.umamo.runtime.model.AtlasPlacement
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the two affines a placement crop is drawn through: the trim's quad on the page (its unit corners
 * carried through the placement and the display flip) and the sample affine into the whole tile, which must
 * agree, corner for corner, on which tile pixel each point of the quad shows.
 */
class UvPlacementQuadTest {
	private val pageHeight = 256
	private val trim = LayerBounds(2, 3, 10, 6)

	@Test
	fun theQuadPutsTheTrimWhereThePlacementPutsTheTile() {
		val placement = AtlasPlacement(pageIndex = 0, positionX = 100f, positionY = 136f, scaleX = 1f, scaleY = 1f, rotationDegrees = 0f)
		val quad = trimQuadToDisplay(placement, trim, pageHeight)

		// The quad's top-left unit corner (0, 1) is the trim's top-left pixel: page (102, 139), display y 256 - 139.
		assertPoint(102f, 117f, apply(quad, 0f, 1f), "top-left")
		// Its bottom-right unit corner (1, 0) is the trim's bottom-right: page (112, 145).
		assertPoint(112f, 111f, apply(quad, 1f, 0f), "bottom-right")
	}

	@Test
	fun theSampleAffineWalksTheTrimInTheTile() {
		val sample = trimSampleAffine(trim, 20, 20)

		assertPoint(0.1f, 0.15f, apply(sample, 0f, 0f), "the trim's top-left in the tile's texture frame")
		assertPoint(0.6f, 0.45f, apply(sample, 1f, 1f), "its bottom-right")
	}

	@Test
	fun eachPointOfTheQuadShowsTheTilePixelTheSampleReads() {
		val turned = AtlasPlacement(pageIndex = 0, positionX = 60f, positionY = 40f, scaleX = 1.5f, scaleY = 0.75f, rotationDegrees = 30f)
		val quad = trimQuadToDisplay(turned, trim, pageHeight)
		val sample = trimSampleAffine(trim, 20, 20)
		val tileToDisplay = tileToDisplayAffine(turned, pageHeight)

		for ((unitX, unitY) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, 0.25f to 0.6f)) {
			// The quad's fragment at unit (x, y) samples at the V-flipped (x, 1 - y).
			val texture = apply(sample, unitX, 1f - unitY)
			val shown = apply(tileToDisplay, texture.first * 20f, texture.second * 20f)
			assertPoint(shown.first, shown.second, apply(quad, unitX, unitY), "unit ($unitX, $unitY)")
		}
	}

	/**
	 * A 2x3 affine applied to a point.
	 *
	 * @param FloatArray affine The affine, rows first.
	 * @param Float x The point's x.
	 * @param Float y The point's y.
	 * @return Pair<Float, Float> The mapped point.
	 */
	private fun apply(affine: FloatArray, x: Float, y: Float): Pair<Float, Float> =
		Pair(affine[0] * x + affine[1] * y + affine[2], affine[3] * x + affine[4] * y + affine[5])

	/**
	 * Asserts a point is where it should be, within float rounding.
	 *
	 * @param Float x The expected x.
	 * @param Float y The expected y.
	 * @param Pair<Float, Float> actual The point.
	 * @param String label What the point is.
	 */
	private fun assertPoint(x: Float, y: Float, actual: Pair<Float, Float>, label: String) {
		assertTrue(abs(actual.first - x) < 1e-3f && abs(actual.second - y) < 1e-3f, "$label: expected ($x, $y), got $actual")
	}
}