package org.umamo.render

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins which drawables map onto their source artwork and which fall back to the atlas, and that
 * working the mapping out costs no decoding.
 *
 * The mode is best-effort per drawable rather than a clean flip, so the interesting cases are the ways
 * a drawable can fail to map: no binding, or a placement that will not invert.  Each keeps the drawable
 * on the atlas and counts it, because a puppet drawn mostly from its atlas must not look like one drawn
 * from its artwork.  Art that will not DECODE is deliberately not one of those cases here - the plan
 * carries no pixels, so that failure surfaces later, from the batch that tried.
 */
class LayerDrawPlanTest {
	private fun meshedDrawable(rawId: String, tileId: String? = null): Drawable =
		Drawable(
			id = DrawableId(rawId),
			name = rawId,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
			geometryGrid = null,
			atlasTileId = tileId?.let { raw -> AtlasTileId(raw) },
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

	private fun modelOf(atlas: PuppetAtlas = PuppetAtlas.Empty, vararg drawables: Drawable): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables.toList(),
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			atlas = atlas,
		)

	private fun onePixelPng(): ByteArray = PngCodec.write(RasterImage(1, 1, byteArrayOf(1, 2, 3, 4)))

	/** A tile whose art was never packed, so its drawables' uvs already address it. */
	private fun unpackedTile(rawId: String, width: Int = 8, height: Int = 8): AtlasTile =
		AtlasTile(AtlasTileId(rawId), name = "Art", width = width, height = height)

	/** A drawable with usable artwork gets a draw; a layer is costed once, however many share it. */
	@Test
	fun resolvedDrawablesShareOneLayerCost() {
		val model =
			modelOf(
				PuppetAtlas(tiles = listOf(unpackedTile("layer0"))),
				meshedDrawable("a", "layer0"),
				meshedDrawable("b", "layer0"),
			)
		val plan = buildLayerDrawPlan(model)
		assertEquals(setOf("a", "b"), plan.drawsByDrawableId.keys, "both drawables map")
		assertEquals(1, plan.layerByteCostByKey.size, "shared art is costed once, not once per drawable")
		assertEquals(8L * 8L * 4L, plan.layerByteCostByKey.getValue("layer0"), "costed from the inventory size, before any decode")
		assertEquals(0, plan.unresolvedDrawableCount, "nothing fell back")
	}

	/** A drawable with no binding falls back to the atlas and is counted, rather than vanishing. */
	@Test
	fun unboundDrawableFallsBackAndIsCounted() {
		val model =
			modelOf(
				PuppetAtlas(tiles = listOf(unpackedTile("layer0"))),
				meshedDrawable("a", "layer0"),
				meshedDrawable("unbound"),
			)
		val plan = buildLayerDrawPlan(model)
		assertEquals(setOf("a"), plan.drawsByDrawableId.keys, "only the bound drawable maps")
		assertEquals(1, plan.unresolvedDrawableCount, "the unbound one is counted, not hidden")
	}

	/** A placement that will not invert leaves its drawable on the atlas. */
	@Test
	fun degeneratePlacementFallsBack() {
		val degenerate =
			unpackedTile("layer0")
				.copy(placement = AtlasPlacement(0, 0f, 0f, scaleX = 0f, scaleY = 1f, rotationDegrees = 0f))
		val model =
			modelOf(
				PuppetAtlas(pages = listOf(AtlasPage(64, 64)), tiles = listOf(degenerate)),
				meshedDrawable("a", "layer0"),
			)
		val plan = buildLayerDrawPlan(model)
		assertTrue(plan.drawsByDrawableId.isEmpty(), "a mapping that cannot be formed draws nothing from artwork")
		assertEquals(1, plan.unresolvedDrawableCount, "and the drawable is counted")
	}

	/**
	 * Building the mapping decodes nothing at all.
	 *
	 * The whole point of the split: a document referencing hundreds of layers must be able to work out
	 * what maps where without paying for the pixels, or switching display mode would cost gigabytes
	 * before anything appeared.
	 */
	@Test
	fun buildingThePlanDecodesNothing() {
		var decodes = 0
		SourceArtRasters.fromPng { _ ->
			decodes++
			onePixelPng()
		}
		val plan =
			buildLayerDrawPlan(
				modelOf(PuppetAtlas(tiles = listOf(unpackedTile("layer0"))), meshedDrawable("a", "layer0")),
			)
		assertEquals(0, decodes, "the mapping is worked out from the inventory, never from the pixels")
		assertNotNull(plan.drawsByDrawableId["a"], "and the drawable still maps")
	}

	/**
	 * Art that will not decode is attributed to every drawable over it.
	 *
	 * The plan cannot know this - it decodes nothing - so the producer discovers it from a delivery and
	 * asks the plan who was affected.  That is what keeps the reported gap counting drawables with no
	 * usable artwork, rather than layers.
	 */
	@Test
	fun undecodableLayersMapBackToTheirDrawables() {
		val model =
			modelOf(
				PuppetAtlas(tiles = listOf(unpackedTile("layer0"), unpackedTile("layer1"))),
				meshedDrawable("a", "layer0"),
				meshedDrawable("b", "layer0"),
				meshedDrawable("c", "layer1"),
			)
		val plan = buildLayerDrawPlan(model)
		assertEquals(setOf("a", "b"), plan.drawableIdsUsing(setOf("layer0")), "both users of the failed art are named")
		assertEquals(emptySet(), plan.drawableIdsUsing(emptySet()), "and nothing is named when nothing failed")
	}

	/** Unmeshed drawables are not counted: they draw nothing either way. */
	@Test
	fun unmeshedDrawablesAreNotCounted() {
		val plan = buildLayerDrawPlan(modelOf(PuppetAtlas(tiles = listOf(unpackedTile("layer0"))), bareDrawable("c")))
		assertEquals(0, plan.unresolvedDrawableCount, "a drawable with no mesh is not a fallback")
	}

	/** An empty atlas means the packed pages, with no work done at all. */
	@Test
	fun anEmptyAtlasYieldsThePackedPages() {
		val plan = buildLayerDrawPlan(modelOf(PuppetAtlas.Empty, meshedDrawable("a")))
		assertTrue(plan.isEmpty, "no artwork means displaying from the atlas")
	}

	/**
	 * A session-created duplicate finds its art natively.
	 *
	 * The tile is a field on the drawable, so a copy carries it like every other field - where the atlas
	 * page still resolves through textureSourceId, because THAT map is keyed by the source format's own
	 * drawable ids and a fresh duplicate has no entry in it.
	 */
	@Test
	fun aDuplicateCarriesItsOwnTileBinding() {
		val model =
			modelOf(
				PuppetAtlas(tiles = listOf(unpackedTile("layer0"))),
				meshedDrawable("a", "layer0"),
				meshedDrawable("a copy", "layer0").copy(textureSourceId = DrawableId("a")),
			)
		val plan = buildLayerDrawPlan(model)
		assertNotNull(plan.drawsByDrawableId["a copy"], "the duplicate resolves its own tile")
		assertEquals(0, plan.unresolvedDrawableCount, "so it is not a fallback")
	}

	/** The uncached decode shares nothing with the caching one, so it is safe off the owning thread. */
	@Test
	fun uncachedDecodeSharesNothing() {
		val tileId = AtlasTileId("layer0")
		val store = SourceArtRasters.fromPng { requested -> if (requested == tileId) onePixelPng() else null }
		val cached = store.rasterFor(tileId)
		val fresh = store.decodeRaster(tileId)
		assertNotNull(cached, "the caching path decodes")
		assertNotNull(fresh, "the uncached path decodes")
		assertTrue(cached !== fresh, "the uncached path owns its result rather than sharing the cache's")
		assertNull(store.decodeRaster(AtlasTileId("missing")), "an unknown tile decodes to nothing")
	}
}