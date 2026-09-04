package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers how a drawable resolves to its source art: which tile, whether it reads a packed page or the
 * art directly, and which page's dimensions the mapping is built against.
 *
 * The packed-versus-direct decision is the load-bearing one.  It used to be read off CMO3 resource
 * identity at import; the model derives it instead, from whether the document's stored coordinates
 * address the packed pages ([PuppetAtlas.storedUvsAddressPages]) and whether the tile was packed at
 * all.  Getting it wrong does not fail loudly - it silently swaps a drawable's UV editor between the
 * layer frame and the atlas frame - so each arm is pinned here.
 */
class PuppetAtlasTest {
	private fun drawable(rawId: String, tileId: String? = null): Drawable =
		Drawable(
			id = DrawableId(rawId),
			name = rawId,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			atlasTileId = tileId?.let { raw -> AtlasTileId(raw) },
		)

	private fun modelOf(atlas: PuppetAtlas, fromSourceLayers: Boolean = false, vararg drawables: Drawable): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables.toList(),
			rootChildren = drawables.map { entry -> OrgChild.Drawable(entry.id) },
			rootPartId = null,
			rendersFromSourceLayers = fromSourceLayers,
			atlas = atlas,
		)

	private fun packedTile(rawId: String, pageIndex: Int = 0): AtlasTile =
		AtlasTile(
			id = AtlasTileId(rawId),
			name = "Art",
			width = 8,
			height = 16,
			placement = AtlasPlacement(pageIndex, 4f, 6f, scaleX = 1f, scaleY = 1f, rotationDegrees = 0f),
		)

	private fun unpackedTile(rawId: String): AtlasTile = AtlasTile(AtlasTileId(rawId), "Art", 8, 16)

	@Test
	fun aPackedTileInAtlasModeCarriesItsPlacementAndItsPageSize() {
		val model =
			modelOf(
				PuppetAtlas(pages = listOf(AtlasPage(64, 32)), tiles = listOf(packedTile("t0"))),
				drawables = arrayOf(drawable("a", "t0")),
			)

		val binding = assertNotNull(model.atlasBindingFor(model.drawables.single()))
		assertEquals("t0", binding.layerKey)
		assertNotNull(binding.placement, "a packed tile in atlas mode reads its page")
		assertEquals(64, binding.pageWidth)
		assertEquals(32, binding.pageHeight)
	}

	@Test
	fun aTileThatWasNeverPackedIsReadDirectly() {
		val model =
			modelOf(
				PuppetAtlas(pages = listOf(AtlasPage(64, 32)), tiles = listOf(unpackedTile("t0"))),
				drawables = arrayOf(drawable("a", "t0")),
			)

		val binding = assertNotNull(model.atlasBindingFor(model.drawables.single()))
		assertNull(binding.placement, "art that was never packed is sampled where it lies")
		assertTrue(
			layerUvAffineOf(binding, 8, 16).contentEquals(identityUvAffine()),
			"so its stored coordinates already address it and the mapping is the identity",
		)
	}

	/**
	 * Which frame the stored coordinates are in is a fact about the FILE, not about what is on screen.
	 *
	 * A document whose drawables sample the packed pages has page-space coordinates whether or not the
	 * rigger is currently looking at the artwork - so toggling the display must not reinterpret them.
	 * Deriving the frame from the display mode looked right at import, where the two agree, and broke
	 * the moment anyone flipped the switch: the layer view drew every mesh at its atlas position.
	 */
	@Test
	fun theDisplayModeDoesNotReinterpretStoredCoordinates() {
		val atlas = PuppetAtlas(pages = listOf(AtlasPage(64, 32)), tiles = listOf(packedTile("t0")))
		val showingAtlas = modelOf(atlas, fromSourceLayers = false, drawables = arrayOf(drawable("a", "t0")))
		val showingArtwork = showingAtlas.copy(rendersFromSourceLayers = true)

		assertEquals(
			showingAtlas.atlasBindingFor(showingAtlas.drawables.single()),
			showingArtwork.atlasBindingFor(showingArtwork.drawables.single()),
			"the mapping is the same either way - only which texture is sampled differs",
		)
		assertNotNull(
			showingArtwork.atlasBindingFor(showingArtwork.drawables.single())?.placement,
			"packed art keeps its placement while the artwork is displayed",
		)
	}

	/**
	 * A document saved sampling its per-layer rasters has coordinates that already address the art.
	 *
	 * The corpus case is a model saved in combined-layer display mode: a packed atlas sits beside it,
	 * but the drawables point at the layer images, so inverting a page-space placement over those
	 * coordinates would throw every mesh thousands of pixels away.
	 */
	@Test
	fun aDocumentWhoseCoordinatesAddressTheArtMapsThroughTheIdentity() {
		val model =
			modelOf(
				PuppetAtlas(
					pages = listOf(AtlasPage(64, 32)),
					tiles = listOf(packedTile("t0")),
					storedUvsAddressPages = false,
				),
				drawables = arrayOf(drawable("a", "t0")),
			)

		val binding = assertNotNull(model.atlasBindingFor(model.drawables.single()))
		assertNull(binding.placement, "the coordinates already address the art, so recovery is the identity")
	}

	@Test
	fun aDrawableWithNoTileHasNoBinding() {
		val model =
			modelOf(PuppetAtlas(tiles = listOf(unpackedTile("t0"))), drawables = arrayOf(drawable("a")))

		assertNull(model.atlasBindingFor(model.drawables.single()), "no tile means no source-art view")
	}

	@Test
	fun aPlacementNamingAMissingPageResolvesToNothingRatherThanTheIdentity() {
		// Broken wiring, not a directly-sampled tile: answering "identity" would draw the mesh in the
		// wrong frame instead of admitting the binding cannot be formed.
		val model =
			modelOf(
				PuppetAtlas(pages = emptyList(), tiles = listOf(packedTile("t0", pageIndex = 3))),
				drawables = arrayOf(drawable("a", "t0")),
			)

		assertNull(model.atlasBindingFor(model.drawables.single()))
	}

	@Test
	fun anUnknownTileIdResolvesToNothing() {
		val model = modelOf(PuppetAtlas(tiles = listOf(unpackedTile("t0"))), drawables = arrayOf(drawable("a", "gone")))

		assertNull(model.atlasBindingFor(model.drawables.single()))
		assertNull(model.atlasBindingForTile(AtlasTileId("gone")))
	}

	@Test
	fun everyDrawableOverOneTileSharesItsBinding() {
		val model =
			modelOf(
				PuppetAtlas(pages = listOf(AtlasPage(64, 32)), tiles = listOf(packedTile("t0"))),
				drawables = arrayOf(drawable("a", "t0"), drawable("b", "t0")),
			)

		val first = assertNotNull(model.atlasBindingFor(model.drawables[0]))
		val second = assertNotNull(model.atlasBindingFor(model.drawables[1]))
		assertEquals(first, second, "the placement belongs to the art, so its users cannot disagree")
		assertEquals(first, model.atlasBindingForTile(AtlasTileId("t0")), "and the tile answers the same alone")
	}

	@Test
	fun theTileInverseNamesEveryDrawableInDocumentOrder() {
		val model =
			modelOf(
				PuppetAtlas(tiles = listOf(unpackedTile("t0"), unpackedTile("t1"))),
				drawables = arrayOf(drawable("a", "t0"), drawable("b", "t1"), drawable("c", "t0"), drawable("d")),
			)

		assertEquals(
			mapOf(
				AtlasTileId("t0") to listOf(DrawableId("a"), DrawableId("c")),
				AtlasTileId("t1") to listOf(DrawableId("b")),
			),
			model.drawableIdsByAtlasTile(),
			"a drawable with no tile is absent, and the rest keep document order",
		)
	}

	@Test
	fun anEmptyAtlasReportsEmpty() {
		assertTrue(PuppetAtlas.Empty.isEmpty)
		assertTrue(PuppetAtlas(pages = listOf(AtlasPage(1, 1))).isEmpty.not(), "a page alone is still an atlas")
	}

	/**
	 * A tile's source binding is document data that rides through every tile edit: a placement change
	 * copies the tile and must keep the binding, and a model with no linked art has no sources at all.
	 */
	@Test
	fun aTileKeepsItsSourceBindingThroughAPlacementChange() {
		val source = SourceLayerRef(ArtSourceId("art-0"), layerKey = "lyid:7", stableKey = true)
		val tile = unpackedTile("t0").copy(source = source)
		val placed = tile.copy(placement = AtlasPlacement(0, 1f, 2f, scaleX = 1f, scaleY = 1f, rotationDegrees = 0f))

		assertEquals(source, placed.source, "the binding is what the tile IS, not where it packs")
		assertNull(unpackedTile("t1").source, "a tile with no retained binding has none")
		assertTrue(modelOf(PuppetAtlas.Empty).sources.isEmpty(), "a document with no linked art lists no sources")
	}
}