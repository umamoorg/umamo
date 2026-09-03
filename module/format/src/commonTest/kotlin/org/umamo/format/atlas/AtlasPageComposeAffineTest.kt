package org.umamo.format.atlas

import org.umamo.format.art.LayerBounds
import org.umamo.format.raster.RasterImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the affine page composer against the packer's own blit: an exact integer translation is
 * byte-identical to [composeAtlasPages] (so a repack's pages derive unchanged), a quarter turn at an
 * integer position reproduces the packer's turned blit texel for texel, a scale covers the scaled
 * footprint, the extrusion band surrounds a rotated tile on every side, an off-page footprint clips
 * without throwing, and overlapping placements paint in list order.
 */
class AtlasPageComposeAffineTest {
	private val pageSide = 16
	private val pageWidths = intArrayOf(pageSide)
	private val pageHeights = intArrayOf(pageSide)

	/** A 5x5 tile whose opaque 3x3 block sits at (1, 1), with a translucent center texel. */
	private val item =
		packItemOfRows(
			"a",
			".....",
			".###.",
			".#4#.",
			".###.",
			".....",
		)
	private val trim = LayerBounds(1, 1, 3, 3)

	private fun translation(deltaX: Float, deltaY: Float): FloatArray = floatArrayOf(1f, 0f, deltaX, 0f, 1f, deltaY)

	private fun alphaAt(page: RasterImage, x: Int, y: Int): Int = pagePixel(page, x, y) and 0xFF

	private fun composeOne(placement: AtlasTilePlacement, extrude: Int, items: List<AtlasPackItem> = listOf(item)): RasterImage =
		composeAtlasPagesAffine(pageWidths, pageHeights, items, listOf(placement), extrude).single()

	@Test
	fun anIntegerTranslationComposesByteIdenticallyToThePacker() {
		val packed =
			composeAtlasPages(
				pageWidths,
				pageHeights,
				listOf(item),
				listOf(AtlasPackPlacement("a", 0, pageX = 6, pageY = 7, trimLeft = 1, trimTop = 1, trimWidth = 3, trimHeight = 3, quarterTurns = 0)),
				extrude = 2,
			).single()
		// Tile pixel (x, y) lands at (x + 5, y + 6), so the trim origin (1, 1) lands at (6, 7).
		val composed = composeOne(AtlasTilePlacement("a", 0, trim, translation(5f, 6f)), extrude = 2)
		assertContentEquals(packed.rgba, composed.rgba, "the exact path is the packer's blit")
	}

	@Test
	fun aQuarterTurnAtAnIntegerPositionMatchesThePackersTurnedBlit() {
		val destinationX = 4
		val destinationY = 5
		val packed =
			composeAtlasPages(
				pageWidths,
				pageHeights,
				listOf(item),
				listOf(AtlasPackPlacement("a", 0, destinationX, destinationY, trimLeft = 1, trimTop = 1, trimWidth = 3, trimHeight = 3, quarterTurns = 1)),
				extrude = 0,
			).single()
		// The packer's turn sends tile pixel (tileX, tileY) to (destinationX + tileY - top,
		// destinationY + trimWidth - 1 - (tileX - left)); over pixel centers that is the affine
		// x' = y + (destinationX - top), y' = -x + (destinationY + trimWidth + left).
		val turned = floatArrayOf(0f, 1f, (destinationX - trim.top).toFloat(), -1f, 0f, (destinationY + trim.width + trim.left).toFloat())
		val composed = composeOne(AtlasTilePlacement("a", 0, trim, turned), extrude = 0)
		assertContentEquals(packed.rgba, composed.rgba, "centers that land on texel centers copy texels verbatim")
	}

	@Test
	fun aQuarterTurnFromARotationInDegreesTakesThePackersBlitBandIncluded() {
		val destinationX = 4
		val destinationY = 5
		val packed =
			composeAtlasPages(
				pageWidths,
				pageHeights,
				listOf(item),
				listOf(AtlasPackPlacement("a", 0, destinationX, destinationY, trimLeft = 1, trimTop = 1, trimWidth = 3, trimHeight = 3, quarterTurns = 1)),
				extrude = 2,
			).single()
		// The affine a placement rotated by -90 degrees produces: its diagonal is near zero, not zero,
		// and with a band to extrude only the packer's own blit reproduces the packer's band.
		val radians = -PI / 2
		val turned =
			floatArrayOf(
				cos(radians).toFloat(),
				(-sin(radians)).toFloat(),
				(destinationX - trim.top).toFloat(),
				sin(radians).toFloat(),
				cos(radians).toFloat(),
				(destinationY + trim.width + trim.left).toFloat(),
			)
		assertTrue(turned[0] != 0f, "the diagonal is near zero rather than zero - the case the recogniser must accept")
		val composed = composeOne(AtlasTilePlacement("a", 0, trim, turned), extrude = 2)
		assertContentEquals(packed.rgba, composed.rgba, "a degree-rotated quarter turn is the packer's turned blit, band included")
	}

	@Test
	fun aDoubledScaleCoversADoubledFootprint() {
		// Tile pixel (x, y) maps to (2x + 2, 2y + 2): the 3x3 trim at (1, 1) covers page [4, 10) squared.
		val solid = opaquePackItem("s", 5, 5)
		val composed = composeOne(AtlasTilePlacement("s", 0, trim, floatArrayOf(2f, 0f, 2f, 0f, 2f, 2f)), extrude = 0, items = listOf(solid))
		for (y in 0 until pageSide) {
			for (x in 0 until pageSide) {
				val inside = x in 4 until 10 && y in 4 until 10
				assertEquals(if (inside) 255 else 0, alphaAt(composed, x, y), "alpha at ($x, $y)")
			}
		}
		// A corner pixel's taps all clamp onto the corner texel, so it comes through verbatim.
		assertEquals(itemPixel(solid, 1, 1), pagePixel(composed, 4, 4), "the scaled corner is the trim's corner texel")
		// Between texels the samples blend: at twice the size no page center lands on the translucent
		// center texel's own center, so it and its neighbors come out as mixtures rather than snapping
		// to the nearest texel.
		val blended = composeOne(AtlasTilePlacement("a", 0, trim, floatArrayOf(2f, 0f, 2f, 0f, 2f, 2f)), extrude = 0)
		for ((x, y) in listOf(6 to 6, 5 to 5, 7 to 7)) {
			val alpha = alphaAt(blended, x, y)
			assertTrue(alpha in 5..254, "($x, $y) straddles the opaque and translucent texels and blends them, was $alpha")
		}
		assertEquals(255, alphaAt(blended, 4, 4), "a pixel whose taps all clamp onto opaque texels stays opaque")
	}

	@Test
	fun theExtrusionBandSurroundsARotatedTile() {
		val opaque = opaquePackItem("r", 3, 3)
		val fullTrim = LayerBounds(0, 0, 3, 3)
		// A 45 degree turn with the tile's center carried to the page center (8, 8).
		val radians = (PI / 4).toFloat()
		val cosine = cos(radians)
		val sine = sin(radians)
		val rotated =
			floatArrayOf(
				cosine,
				-sine,
				8f - (cosine * 1.5f - sine * 1.5f),
				sine,
				cosine,
				8f - (sine * 1.5f + cosine * 1.5f),
			)
		val bare = composeOne(AtlasTilePlacement("r", 0, fullTrim, rotated), extrude = 0, items = listOf(opaque))
		val extruded = composeOne(AtlasTilePlacement("r", 0, fullTrim, rotated), extrude = 2, items = listOf(opaque))

		assertEquals(255, alphaAt(bare, 8, 8), "the center is inside the turned square")
		// The diamond's tips reach 2.12 px from the center, so these four pixels sit just outside the
		// footprint on each side and inside the two-pixel band.
		for ((x, y) in listOf(8 to 5, 8 to 10, 5 to 8, 10 to 8)) {
			assertEquals(0, alphaAt(bare, x, y), "($x, $y) is outside the bare footprint")
			assertEquals(255, alphaAt(extruded, x, y), "($x, $y) is inside the extrusion band")
		}
		for (y in 0 until pageSide) {
			for (x in 0 until pageSide) {
				assertTrue(alphaAt(extruded, x, y) >= alphaAt(bare, x, y), "extrusion only adds coverage at ($x, $y)")
			}
		}
		assertEquals(0, alphaAt(extruded, 8, 2), "three pixels past the tip is beyond the band")
	}

	@Test
	fun anOffPageFootprintClipsWithoutThrowing() {
		// The trim origin lands at (-1, -1): tile pixel (2, 2) is the page's (0, 0).
		val clipped = composeOne(AtlasTilePlacement("a", 0, trim, translation(-2f, -2f)), extrude = 2)
		assertEquals(itemPixel(item, 2, 2), pagePixel(clipped, 0, 0), "the visible part composes at its clipped spot")
		assertEquals(itemPixel(item, 3, 3), pagePixel(clipped, 1, 1))
		assertEquals(0, alphaAt(clipped, 5, 5), "nothing spills past the clipped footprint and band")

		val gone = composeOne(AtlasTilePlacement("a", 0, trim, translation(-40f, -40f)), extrude = 2)
		assertTrue(gone.rgba.all { byte -> byte == 0.toByte() }, "a footprint wholly off the page leaves it empty")
	}

	/** A 5x5 tile that is transparent except for one opaque texel at (0, 0) and a half-transparent one at (4, 4). */
	private val sparse =
		packItemOfRows(
			"b",
			"#....",
			".....",
			".....",
			".....",
			"....8",
		)
	private val fullTrim = LayerBounds(0, 0, 5, 5)

	@Test
	fun aLaterTransparentMarginNeverErasesEarlierContent() {
		val solid = opaquePackItem("s", 3, 3)
		val solidAt = translation(4f, 4f)
		// The sparse tile's rectangle covers the solid tile entirely; only its (0, 0) texel is opaque and
		// lands at (3, 3), outside the solid tile.
		val exact = composeAtlasPagesAffine(pageWidths, pageHeights, listOf(solid, sparse), listOf(AtlasTilePlacement("s", 0, LayerBounds(0, 0, 3, 3), solidAt), AtlasTilePlacement("b", 0, fullTrim, translation(3f, 3f))), extrude = 0).single()
		for (y in 4 until 7) {
			for (x in 4 until 7) {
				assertEquals(itemPixel(solid, x - 4, y - 4), pagePixel(exact, x, y), "the solid tile survives the transparent margin at ($x, $y) (exact path)")
			}
		}
		assertEquals(itemPixel(sparse, 0, 0), pagePixel(exact, 3, 3), "the sparse tile's opaque texel lands")

		// The same through the resampling path (a fractional translation).
		val resampled = composeAtlasPagesAffine(pageWidths, pageHeights, listOf(solid, sparse), listOf(AtlasTilePlacement("s", 0, LayerBounds(0, 0, 3, 3), solidAt), AtlasTilePlacement("b", 0, fullTrim, translation(3.5f, 3.5f))), extrude = 0).single()
		for (y in 4 until 7) {
			for (x in 4 until 7) {
				assertEquals(255, alphaAt(resampled, x, y), "the solid tile stays opaque under the resampled margin at ($x, $y)")
			}
		}

		// And through the packer's quarter-turn path.
		val turned =
			composeAtlasPages(
				pageWidths,
				pageHeights,
				listOf(solid, sparse),
				listOf(
					AtlasPackPlacement("s", 0, 4, 4, trimLeft = 0, trimTop = 0, trimWidth = 3, trimHeight = 3, quarterTurns = 0),
					AtlasPackPlacement("b", 0, 3, 3, trimLeft = 0, trimTop = 0, trimWidth = 5, trimHeight = 5, quarterTurns = 1),
				),
				extrude = 0,
			).single()
		for (y in 4 until 7) {
			for (x in 4 until 7) {
				assertEquals(itemPixel(solid, x - 4, y - 4), pagePixel(turned, x, y), "the solid tile survives under the turned margin at ($x, $y)")
			}
		}
	}

	@Test
	fun aLaterOpaqueTexelReplacesAndATranslucentOneComposites() {
		val solid = opaquePackItem("s", 3, 3)
		// The sparse tile placed so its opaque (0, 0) covers the solid tile's (1, 1) and its
		// half-transparent (4, 4) covers... nothing: place a second copy so (4, 4) lands on the solid.
		val page =
			composeAtlasPagesAffine(
				pageWidths,
				pageHeights,
				listOf(solid, sparse),
				listOf(
					AtlasTilePlacement("s", 0, LayerBounds(0, 0, 3, 3), translation(4f, 4f)),
					AtlasTilePlacement("b", 0, fullTrim, translation(5f, 5f)),
				),
				extrude = 0,
			).single()
		assertEquals(itemPixel(sparse, 0, 0), pagePixel(page, 5, 5), "an opaque texel replaces the earlier content")
		assertEquals(itemPixel(solid, 0, 0), pagePixel(page, 4, 4), "beside it the earlier tile is untouched")

		val translucent =
			composeAtlasPagesAffine(
				pageWidths,
				pageHeights,
				listOf(solid, sparse),
				listOf(
					AtlasTilePlacement("s", 0, LayerBounds(0, 0, 3, 3), translation(4f, 4f)),
					AtlasTilePlacement("b", 0, fullTrim, translation(1f, 1f)),
				),
				extrude = 0,
			).single()
		// The sparse (4, 4) texel, alpha 8, lands on the solid (1, 1) at page (5, 5): a source-over
		// composite stays fully opaque and leans almost entirely on the tile beneath.
		val composed = pagePixel(translucent, 5, 5)
		assertEquals(255, composed and 0xFF, "compositing over opaque content stays opaque")
		val beneathRed = (itemPixel(solid, 1, 1) ushr 24) and 0xFF
		val overRed = (itemPixel(sparse, 4, 4) ushr 24) and 0xFF
		val expectedRed = ((overRed * 8f + beneathRed * 247f) / 255f).toInt()
		assertTrue(abs(((composed ushr 24) and 0xFF) - expectedRed) <= 1, "the red channel is the source-over blend")
	}

	@Test
	fun anExtrusionBandStopsAtContent() {
		val first = opaquePackItem("s", 3, 3)
		val second = opaquePackItem("t", 3, 3)
		// Two opaque tiles two pixels apart: the first's band fills the gap, the second's band must not
		// overwrite the first's pixels or its band.
		val page =
			composeAtlasPagesAffine(
				pageWidths,
				pageHeights,
				listOf(first, second),
				listOf(
					AtlasTilePlacement("s", 0, LayerBounds(0, 0, 3, 3), translation(4f, 4f)),
					AtlasTilePlacement("t", 0, LayerBounds(0, 0, 3, 3), translation(8f, 4f)),
				),
				extrude = 2,
			).single()
		assertEquals(itemPixel(first, 2, 1), pagePixel(page, 6, 5), "the first tile's edge pixel is not overwritten by the second's band")
		assertEquals(itemPixel(first, 2, 1), pagePixel(page, 7, 5), "the first tile's own band keeps the gap")
		assertEquals(itemPixel(second, 0, 1), pagePixel(page, 8, 5), "the second tile's pixels land")
		assertEquals(itemPixel(second, 2, 1), pagePixel(page, 11, 5), "the second tile's outer band lands on empty page")
	}

	@Test
	fun overlappingPlacementsPaintInListOrder() {
		val other = opaquePackItem("b", 3, 3)
		val pages =
			composeAtlasPagesAffine(
				pageWidths,
				pageHeights,
				listOf(item, other),
				listOf(
					AtlasTilePlacement("a", 0, trim, translation(5f, 6f)),
					AtlasTilePlacement("b", 0, LayerBounds(0, 0, 3, 3), translation(7f, 8f)),
				),
				extrude = 0,
			)
		val page = pages.single()
		assertEquals(itemPixel(other, 0, 0), pagePixel(page, 7, 8), "the later placement wins the overlap")
		assertEquals(itemPixel(item, 1, 1), pagePixel(page, 6, 7), "the earlier placement keeps its uncovered pixels")
	}
}