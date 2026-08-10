package org.umamo.render

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the source-layer store and the atlas-placement recovery math: the TRS round-trip across every
 * shape the corpus contains (translation, quarter turns, free angles, non-unit and mirroring scale,
 * fractional origins), the uv mapping in both the packed and unpacked cases, and the store's
 * decode-once caching including its remembered failures.
 *
 * The recovery convention this locks - forward is `atlas = R(angle) . S . layer + position` in
 * page pixels with y running down - is cross-checked against real files by
 * Cmo3LayerRecoveryCorpusTest, which composes it against the format's own atlas-to-canvas affine.
 */
class LayerTexturesTest {
	private fun placementOf(
		positionX: Float = 0f,
		positionY: Float = 0f,
		scaleX: Float = 1f,
		scaleY: Float = 1f,
		rotationDegrees: Float = 0f,
	): AtlasPlacement =
		AtlasPlacement(
			pageIndex = 0,
			positionX = positionX,
			positionY = positionY,
			scaleX = scaleX,
			scaleY = scaleY,
			rotationDegrees = rotationDegrees,
		)

	private fun assertRoundTrips(placement: AtlasPlacement, layerX: Float, layerY: Float, message: String) {
		val atlasPixel = atlasPixelOf(placement, layerX, layerY)
		val recovered = layerPixelOf(placement, atlasPixel[0], atlasPixel[1])
		assertNotNull(recovered, "$message: the inverse must resolve")
		assertEquals(layerX, recovered[0], 1e-3f, "$message: x")
		assertEquals(layerY, recovered[1], 1e-3f, "$message: y")
	}

	/** Every placement shape the corpus contains inverts back to the point it started from. */
	@Test
	fun placementInversionRoundTripsEveryShape() {
		assertRoundTrips(placementOf(), 12f, 34f, "identity")
		assertRoundTrips(placementOf(positionX = 640f, positionY = 128f), 12f, 34f, "translation")
		assertRoundTrips(placementOf(positionX = 6742.03f, positionY = 6710.03f), 12f, 34f, "fractional origin")
		assertRoundTrips(placementOf(rotationDegrees = 90f), 12f, 34f, "quarter turn")
		assertRoundTrips(placementOf(rotationDegrees = 180f, positionX = 50f), 12f, 34f, "half turn")
		assertRoundTrips(placementOf(rotationDegrees = 37.5f, positionY = 9f), 12f, 34f, "free angle")
		assertRoundTrips(placementOf(scaleX = 0.8588867f, scaleY = 0.8588867f), 12f, 34f, "uniform scale")
		assertRoundTrips(placementOf(scaleX = 2f, scaleY = 0.5f), 12f, 34f, "anisotropic scale")
		assertRoundTrips(placementOf(scaleX = -1f), 12f, 34f, "horizontal mirror")
		assertRoundTrips(placementOf(scaleY = -1f), 12f, 34f, "vertical mirror")
		assertRoundTrips(
			placementOf(positionX = 292.0f, positionY = 409.0863f, scaleX = 1.16431f, scaleY = 1.16431f, rotationDegrees = 90f),
			12f,
			34f,
			"rotated, scaled and offset together",
		)
	}

	/**
	 * The forward transform's orientation, stated explicitly so a sign flip cannot hide behind a
	 * round-trip (which passes for either convention).  A 90 degree rotation with y running DOWN
	 * carries the +x axis onto +y.
	 */
	@Test
	fun quarterTurnCarriesTheXAxisOntoY() {
		val turned = atlasPixelOf(placementOf(rotationDegrees = 90f), 10f, 0f)
		assertEquals(0f, turned[0], 1e-3f, "a quarter turn zeroes the x component")
		assertEquals(10f, turned[1], 1e-3f, "a quarter turn carries +x onto +y")
	}

	/** A degenerate placement (a collapsed axis) has no inverse and must report that, not divide by zero. */
	@Test
	fun degeneratePlacementHasNoInverse() {
		assertNull(layerPixelOf(placementOf(scaleX = 0f), 5f, 5f), "a zero x scale cannot be inverted")
		assertNull(layerPixelOf(placementOf(scaleY = 0f), 5f, 5f), "a zero y scale cannot be inverted")
	}

	/** A packed drawable's uvs scale up to page pixels, invert, and scale down by the layer's own size. */
	@Test
	fun packedUvsMapIntoTheLayerFrame() {
		// A 64x64 layer packed unrotated at (128, 256) on a 512x512 page: the layer's own corners land
		// at page (128, 256) and (192, 320), i.e. uv (0.25, 0.5) and (0.375, 0.625).
		val binding =
			DrawableLayerBinding(
				layerKey = "layer",
				placement = placementOf(positionX = 128f, positionY = 256f),
				pageWidth = 512,
				pageHeight = 512,
			)
		val layerUvs =
			layerUvsFromAtlasUvs(floatArrayOf(0.25f, 0.5f, 0.375f, 0.625f), binding, layerWidth = 64, layerHeight = 64)
		assertNotNull(layerUvs, "a well-formed binding maps")
		assertEquals(0f, layerUvs[0], 1e-4f, "the layer's own origin maps to uv 0")
		assertEquals(0f, layerUvs[1], 1e-4f, "the layer's own origin maps to uv 0")
		assertEquals(1f, layerUvs[2], 1e-4f, "the layer's far corner maps to uv 1")
		assertEquals(1f, layerUvs[3], 1e-4f, "the layer's far corner maps to uv 1")
	}

	/**
	 * An unpacked drawable's uvs already address its layer image (ingest inverted the logical-frame
	 * affine at import), so recovery must leave them alone - applying a placement would double-transform.
	 */
	@Test
	fun unpackedUvsPassThroughUntouched() {
		val binding = DrawableLayerBinding(layerKey = "layer", placement = null, pageWidth = 0, pageHeight = 0)
		val atlasUvs = floatArrayOf(0.1f, 0.2f, 0.9f, 0.8f)
		val layerUvs = layerUvsFromAtlasUvs(atlasUvs, binding, layerWidth = 64, layerHeight = 64)
		assertNotNull(layerUvs, "an unpacked binding maps")
		assertTrue(layerUvs.contentEquals(atlasUvs), "unpacked uvs must survive verbatim")
	}

	/** A layer with no usable size cannot host a mapping; report it rather than dividing by zero. */
	@Test
	fun zeroSizedLayerHasNoMapping() {
		val binding =
			DrawableLayerBinding(layerKey = "layer", placement = placementOf(), pageWidth = 512, pageHeight = 512)
		assertNull(layerUvsFromAtlasUvs(floatArrayOf(0f, 0f), binding, layerWidth = 0, layerHeight = 64), "zero width")
		assertNull(layerUvsFromAtlasUvs(floatArrayOf(0f, 0f), binding, layerWidth = 64, layerHeight = 0), "zero height")
	}

	private fun onePixelPng(): ByteArray = PngCodec.write(RasterImage(1, 1, byteArrayOf(1, 2, 3, 4)))

	/** The store decodes a layer once and serves the same instance afterwards. */
	@Test
	fun rasterDecodesOnceAndCaches() {
		var readCount = 0
		val store =
			LayerTextures(
				layers = listOf(SourceLayerEntry("a", "A", 1, 1, listOf("d0"), null)),
				bindingsByDrawableId = mapOf("d0" to DrawableLayerBinding("a", null, 0, 0)),
			) { _ ->
				readCount++
				onePixelPng()
			}
		val first = store.rasterFor("a")
		val second = store.rasterFor("a")
		assertNotNull(first, "the layer decodes")
		assertSame(first, second, "a decoded layer is cached, not re-decoded")
		assertEquals(1, readCount, "the byte supplier is consulted once")
	}

	/** A failed decode is remembered as a failure, not retried on every request. */
	@Test
	fun rasterRemembersFailures() {
		var readCount = 0
		val store =
			LayerTextures(
				layers = listOf(SourceLayerEntry("a", "A", 1, 1, emptyList(), null)),
				bindingsByDrawableId = emptyMap(),
			) { _ ->
				readCount++
				byteArrayOf(0, 1, 2)
			}
		assertNull(store.rasterFor("a"), "undecodable bytes yield no raster")
		assertNull(store.rasterFor("a"), "still no raster on a second request")
		assertEquals(1, readCount, "a remembered failure is not retried")
	}

	/** Lookups by drawable and by key resolve through the same inventory; the empty store answers nothing. */
	@Test
	fun lookupsResolveThroughTheInventory() {
		val entry = SourceLayerEntry("a", "A", 8, 16, listOf("d0", "d1"), "Layer 1")
		val store =
			LayerTextures(
				layers = listOf(entry),
				bindingsByDrawableId = mapOf("d0" to DrawableLayerBinding("a", null, 0, 0)),
			) { null }
		assertEquals(entry, store.layerFor("a"), "a known key resolves")
		assertNull(store.layerFor("missing"), "an unknown key resolves to nothing")
		assertEquals(entry, store.layerForDrawable("d0"), "a bound drawable resolves its layer")
		assertNull(store.layerForDrawable("d1"), "a drawable with no binding resolves to nothing")
		assertTrue(LayerTextures.EMPTY.isEmpty, "the empty store reports empty")
		assertNull(LayerTextures.EMPTY.rasterFor("a"), "the empty store has no rasters")
	}
}