package org.umamo.render

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins which drawables end up displaying from their source artwork and which fall back to the atlas.
 *
 * The mode is best-effort per drawable rather than a clean flip, so the interesting cases are all the
 * ways a drawable can fail to resolve: no binding, a placement that will not invert, art that will not
 * decode.  Each keeps the drawable on the atlas and counts it, because a puppet drawn mostly from its
 * atlas must not look like one drawn from its artwork.
 */
class LayerRasterSetTest {
	private fun meshedDrawable(rawId: String, textureSourceId: String? = null): Drawable =
		Drawable(
			id = DrawableId(rawId),
			name = rawId,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
			geometryGrid = null,
			textureSourceId = textureSourceId?.let { source -> DrawableId(source) },
		)

	private fun bareDrawable(rawId: String): Drawable =
		Drawable(
			id = DrawableId(rawId),
			name = rawId,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
		)

	private fun modelOf(vararg drawables: Drawable): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables.toList(),
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
		)

	private fun onePixelPng(): ByteArray = PngCodec.write(RasterImage(1, 1, byteArrayOf(1, 2, 3, 4)))

	private fun storeOf(
		entries: List<SourceLayerEntry>,
		bindings: Map<String, DrawableLayerBinding>,
		decodable: Boolean = true,
	): LayerTextures =
		// Key-aware, like the real supplier: an unknown layer has no bytes at all, which is a different
		// outcome from bytes that will not decode.
		LayerTextures(entries, bindings) { layerKey ->
			when {
				entries.none { entry -> entry.key == layerKey } -> null
				decodable -> onePixelPng()
				else -> byteArrayOf(0, 1, 2)
			}
		}

	private fun unpackedBinding(layerKey: String) = DrawableLayerBinding(layerKey, placement = null, pageWidth = 0, pageHeight = 0)

	/** A drawable with usable artwork gets a draw; one image is decoded per LAYER, not per drawable. */
	@Test
	fun resolvedDrawablesShareOneDecodePerLayer() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val store =
			storeOf(
				entries = listOf(SourceLayerEntry("layer0", "Art", 8, 8, listOf("a", "b"), null)),
				bindings = mapOf("a" to unpackedBinding("layer0"), "b" to unpackedBinding("layer0")),
			)
		val set = buildLayerRasterSet(model, store)
		assertEquals(setOf("a", "b"), set.drawsByDrawableId.keys, "both drawables resolve")
		assertEquals(1, set.rastersByLayerKey.size, "shared art decodes once, not once per drawable")
		assertEquals(0, set.unresolvedDrawableCount, "nothing fell back")
	}

	/** A drawable with no binding falls back to the atlas and is counted, rather than vanishing. */
	@Test
	fun unboundDrawableFallsBackAndIsCounted() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("unbound"))
		val store =
			storeOf(
				entries = listOf(SourceLayerEntry("layer0", "Art", 8, 8, listOf("a"), null)),
				bindings = mapOf("a" to unpackedBinding("layer0")),
			)
		val set = buildLayerRasterSet(model, store)
		assertEquals(setOf("a"), set.drawsByDrawableId.keys, "only the bound drawable resolves")
		assertEquals(1, set.unresolvedDrawableCount, "the unbound one is counted, not hidden")
	}

	/** A placement that will not invert leaves its drawable on the atlas. */
	@Test
	fun degeneratePlacementFallsBack() {
		val model = modelOf(meshedDrawable("a"))
		val degenerate =
			DrawableLayerBinding("layer0", AtlasPlacement(0, 0f, 0f, scaleX = 0f, scaleY = 1f, rotationDegrees = 0f), 64, 64)
		val store =
			storeOf(entries = listOf(SourceLayerEntry("layer0", "Art", 8, 8, listOf("a"), null)), bindings = mapOf("a" to degenerate))
		val set = buildLayerRasterSet(model, store)
		assertTrue(set.drawsByDrawableId.isEmpty(), "a mapping that cannot be formed draws nothing from artwork")
		assertEquals(1, set.unresolvedDrawableCount, "and the drawable is counted")
	}

	/** Art that will not decode leaves its drawable on the atlas, and is not retried per drawable. */
	@Test
	fun undecodableArtFallsBack() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("b"))
		val store =
			storeOf(
				entries = listOf(SourceLayerEntry("layer0", "Art", 8, 8, listOf("a", "b"), null)),
				bindings = mapOf("a" to unpackedBinding("layer0"), "b" to unpackedBinding("layer0")),
				decodable = false,
			)
		val set = buildLayerRasterSet(model, store)
		assertTrue(set.drawsByDrawableId.isEmpty(), "art that will not decode displays from the atlas")
		assertEquals(2, set.unresolvedDrawableCount, "both users of that art are counted")
	}

	/** Unmeshed drawables are not counted: they draw nothing either way. */
	@Test
	fun unmeshedDrawablesAreNotCounted() {
		val set = buildLayerRasterSet(modelOf(bareDrawable("c")), storeOf(emptyList(), emptyMap()))
		assertEquals(0, set.unresolvedDrawableCount, "a drawable with no mesh is not a fallback")
	}

	/** An empty store means the atlas, with no work done at all. */
	@Test
	fun emptyStoreYieldsTheAtlas() {
		val set = buildLayerRasterSet(modelOf(meshedDrawable("a")), LayerTextures.EMPTY)
		assertTrue(set.isEmpty, "no artwork means displaying from the atlas")
	}

	/** A session-created duplicate resolves its art through the drawable it was copied from. */
	@Test
	fun duplicateResolvesThroughItsTextureSource() {
		val model = modelOf(meshedDrawable("a"), meshedDrawable("a copy", textureSourceId = "a"))
		val store =
			storeOf(
				entries = listOf(SourceLayerEntry("layer0", "Art", 8, 8, listOf("a"), null)),
				bindings = mapOf("a" to unpackedBinding("layer0")),
			)
		val set = buildLayerRasterSet(model, store)
		assertNotNull(set.drawsByDrawableId["a copy"], "the duplicate resolves through its texture source")
		assertEquals(0, set.unresolvedDrawableCount, "so it is not a fallback")
	}

	/** A rebuild reuses already-decoded art rather than decoding the document again. */
	@Test
	fun rebuildReusesDecodedArt() {
		var decodes = 0
		val store =
			LayerTextures(
				layers = listOf(SourceLayerEntry("layer0", "Art", 8, 8, listOf("a"), null)),
				bindingsByDrawableId = mapOf("a" to unpackedBinding("layer0")),
			) { _ ->
				decodes++
				onePixelPng()
			}
		val model = modelOf(meshedDrawable("a"))
		val first = buildLayerRasterSet(model, store)
		assertEquals(1, decodes, "the first build decodes")
		val second = buildLayerRasterSet(modelOf(meshedDrawable("a"), meshedDrawable("added")), store, previous = first)
		assertEquals(1, decodes, "a rebuild reuses what it already has")
		assertSame(
			first.rastersByLayerKey.getValue("layer0"),
			second.rastersByLayerKey.getValue("layer0"),
			"and hands back the same image, which the renderer's texture cache keys on",
		)
	}

	/** The uncached decode shares nothing with the caching one, so it is safe off the owning thread. */
	@Test
	fun uncachedDecodeSharesNothing() {
		val store =
			storeOf(entries = listOf(SourceLayerEntry("layer0", "Art", 1, 1, listOf("a"), null)), bindings = emptyMap())
		val cached = store.rasterFor("layer0")
		val fresh = store.decodeRaster("layer0")
		assertNotNull(cached, "the caching path decodes")
		assertNotNull(fresh, "the uncached path decodes")
		assertTrue(cached !== fresh, "the uncached path owns its result rather than sharing the cache's")
		assertNull(store.decodeRaster("missing"), "an unknown layer decodes to nothing")
	}
}