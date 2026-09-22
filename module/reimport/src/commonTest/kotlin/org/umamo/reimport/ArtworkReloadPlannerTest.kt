package org.umamo.reimport

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.CanvasOffset
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.art.SourceArtImportNotice
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.interop.art.placedBy
import org.umamo.interop.art.placedFor
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.OrgInsertion
import org.umamo.runtime.model.OrgSlot
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The reload planner over a hand-built model and in-memory art: what a changed, unchanged, moved,
 * added, and removed layer each turn into, and what a relink pulls.
 */
class ArtworkReloadPlannerTest {
	private val source = ArtSourceId("art-0")
	private val options = SourceArtImportOptions(alphaThreshold = 1, birthMeshMargin = 2)
	private val ref1 = SourceLayerRef(source, "lyid:1", true)
	private val ref2 = SourceLayerRef(source, "lyid:2", true)
	private val tile1 = AtlasTileId("art-0/lyid:1")
	private val tile2 = AtlasTileId("art-0/lyid:2")

	/** Layer 1 at (10, 20), 4x4, and layer 2 at (30, 40), 4x4, as the document last read them. */
	private val layer1 = TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 4, 4), solidRaster(4, 4, 1))
	private val layer2 = TestLayer("lyid:2", "Two", 1, LayerBounds(30, 40, 4, 4), solidRaster(4, 4, 2))
	private val oldRasters = mapOf(tile1 to layer1.raster, tile2 to layer2.raster)
	private val oldRasterOf: (AtlasTileId) -> LayerRaster? = { tileId -> oldRasters[tileId] }

	/** The mesh an untouched drawable over layer 1 carries: its birth quad. */
	private val birthQuad1: DrawableMesh = SourceArtImport.birthMeshFor(layer1, options.alphaThreshold, options.birthMeshMargin)!!

	/** An edited mesh over layer 2: a triangle covering only the layer's top-left pixel. */
	private val editedMesh2 = DrawableMesh(floatArrayOf(30f, 40f, 31f, 40f, 30f, 41f), floatArrayOf(0f, 0f, 0.25f, 0f, 0f, 0.25f), intArrayOf(0, 1, 2))

	private fun drawable(id: String, tileId: AtlasTileId, mesh: DrawableMesh): Drawable =
		Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(), mesh, null, atlasTileId = tileId)

	private fun model(): PuppetModel {
		val drawables = listOf(drawable("d1", tile1, birthQuad1), drawable("d2", tile2, editedMesh2))
		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			atlas =
				PuppetAtlas(
					tiles =
						listOf(
							AtlasTile(tile1, "One", 4, 4, source = ref1, pinned = true),
							AtlasTile(tile2, "Two", 4, 4, source = ref2),
						),
				),
			sources = listOf(ArtSource(source, "a.psd", "/a.psd", "psd", SourceArtImport.inventoryOf(TestArt(listOf(layer1, layer2))))),
		)
	}

	@Test
	fun anUnchangedFilePlansNothing() {
		assertNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(layer1, layer2)), options, oldRasterOf))
	}

	@Test
	fun changedPixelsMintAReplacementAndRebirthAnUntouchedQuad() {
		val repainted = TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 6, 6), solidRaster(6, 6, 9))
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(repainted, layer2)), options, oldRasterOf))
		val replaced = plan.reload.replacedTiles.single()
		assertEquals(tile1, replaced.oldId)
		assertEquals(AtlasTileId("art-0/lyid:1~1"), replaced.tile.id)
		assertEquals(6, replaced.tile.width)
		assertEquals(6, replaced.tile.height)
		assertNull(replaced.tile.placement, "the replacement is unplaced until the pack")
		assertTrue(replaced.tile.pinned, "the pin is carried")
		assertEquals(tile1, replaced.tile.replaces)
		assertEquals(ref1, replaced.tile.source)
		assertSame(repainted.raster, plan.rasterByTile.getValue(replaced.tile.id))
		val reborn = plan.reload.drawableMeshes.getValue(DrawableId("d1"))
		val expected = SourceArtImport.birthMeshFor(repainted, options.alphaThreshold, options.birthMeshMargin)!!
		assertContentEquals(expected.positions, reborn.positions, "an untouched birth quad is re-born over the new art")
		assertContentEquals(expected.uvs, reborn.uvs)
		assertTrue(plan.reload.outgrown.isEmpty())
		assertNull(plan.reload.additions)
		assertEquals(listOf(6, 6), plan.reload.source.layers.first { layer -> layer.key == "lyid:1" }.let { layer -> listOf(layer.width, layer.height) }, "the inventory is refreshed")
	}

	@Test
	fun aMovedLayerUnderAnEditedMeshKeepsThePositionsAndCarriesTheCoordinates() {
		// Layer 2 moved right by 3 and down by 1 with the same pixels: the mesh stays on the canvas and
		// each vertex keeps sampling the canvas pixel under it, which is 3 art pixels further left now.
		val moved = TestLayer("lyid:2", "Two", 1, LayerBounds(33, 41, 4, 4), layer2.raster)
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(layer1, moved)), options, oldRasterOf))
		assertEquals(listOf(tile2), plan.reload.replacedTiles.map { replaced -> replaced.oldId })
		val carried = plan.reload.drawableMeshes.getValue(DrawableId("d2"))
		assertSame(editedMesh2.positions, carried.positions, "positions pass through by reference")
		assertContentEquals(floatArrayOf(-0.75f, -0.25f, -0.5f, -0.25f, -0.75f, 0f), carried.uvs)
		assertEquals(listOf(DrawableId("d2")), plan.reload.outgrown, "the moved art now reaches past the small mesh")
	}

	@Test
	fun addedAndRemovedLayersLandUnderTheSameFile() {
		val third = TestLayer("lyid:3", "Three", 2, LayerBounds(50, 50, 2, 2), solidRaster(2, 2, 3))
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(layer1, third)), options, oldRasterOf))
		assertTrue(plan.reload.replacedTiles.isEmpty(), "neither kept layer changed")
		val additions = assertNotNull(plan.reload.additions)
		assertEquals(listOf(AtlasTileId("art-0/lyid:3")), additions.tiles.map { tile -> tile.id })
		assertEquals(SourceLayerRef(source, "lyid:3", true), additions.tiles.single().source, "the added tile binds under the existing file")
		assertEquals(listOf("ArtMesh1"), additions.drawables.map { drawable -> drawable.id.raw }, "ids mint past the model's")
		assertEquals(listOf("lyid:1", "lyid:3", "lyid:2"), plan.reload.source.layers.map { layer -> layer.key }, "the removed layer's row is kept after the fresh ones while a tile binds it")
		assertEquals(listOf(true, true, false), plan.reload.source.layers.map { layer -> layer.present })
		assertTrue(plan.reload.source.layers.none { layer -> layer.replaced }, "a layer that left the file is not a replacement's loss")
		assertEquals(listOf(ReconcileResult.NeedsReview(ref2, ReviewReason.LayerMissing)), plan.report.needsReview)
		assertTrue(plan.rasterByTile.containsKey(AtlasTileId("art-0/lyid:3")))
	}

	/**
	 * A reload that finds a new layer inside a folder the document already holds as a part (the case
	 * of every CMO3-origin document, whose parts are the PSD's folders) lands it in that part and mints
	 * no part for any folder it added nothing to.
	 */
	@Test
	fun anAddedLayerJoinsTheExistingPartOfItsFolderAndUntouchedFoldersMintNothing() {
		val headLayer1 = TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 4, 4), solidRaster(4, 4, 1), groupPath = "Head")
		val bodyLayer2 = TestLayer("lyid:2", "Two", 1, LayerBounds(30, 40, 4, 4), solidRaster(4, 4, 2), groupPath = "Body")
		val base = model()
		val headPart = PartId("Part1")
		val bodyPart = PartId("Part2")
		val model =
			base.copy(
				parts =
					listOf(
						Part(headPart, "Head", listOf(OrgChild.Drawable(DrawableId("d1")))),
						Part(bodyPart, "Body", listOf(OrgChild.Drawable(DrawableId("d2")))),
					),
				rootChildren = listOf(OrgChild.Part(headPart), OrgChild.Part(bodyPart)),
				sources = listOf(ArtSource(source, "a.psd", "/a.psd", "psd", SourceArtImport.inventoryOf(TestArt(listOf(headLayer1, bodyLayer2))))),
			)
		val brow = TestLayer("lyid:3", "Brow", 2, LayerBounds(50, 50, 2, 2), solidRaster(2, 2, 3), groupPath = "Head")
		val art = TestArt(listOf(headLayer1, bodyLayer2, brow), groups = listOf(TestGroup("Head"), TestGroup("Body"), TestGroup("Legs")))
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model, source, art, options, oldRasterOf))
		val additions = assertNotNull(plan.reload.additions)
		assertTrue(additions.parts.isEmpty(), "no part is minted: Head exists, and Body and Legs gained nothing")
		assertTrue(additions.rootChildren.isEmpty())
		assertEquals(
			listOf(OrgInsertion(headPart, OrgChild.Drawable(DrawableId("ArtMesh1")), OrgSlot.After(OrgChild.Drawable(DrawableId("d1"))))),
			additions.insertions,
			"the brow joins the Head part, right after the layer above it in the file",
		)
	}

	@Test
	fun aRelinkPullsTheTargetLayer() {
		val art = TestArt(listOf(layer1, layer2))
		val plan = assertNotNull(ArtworkReloadPlanner.planRelink(model(), tile1, ref2, art, options, oldRasterOf))
		val replaced = plan.reload.replacedTiles.single()
		assertEquals(tile1, replaced.oldId)
		assertEquals(ref2, replaced.tile.source, "the replacement carries the new binding")
		assertEquals("Two", replaced.tile.name)
		assertSame(layer2.raster, plan.rasterByTile.getValue(replaced.tile.id))
		assertNull(ArtworkReloadPlanner.planRelink(model(), tile1, ref1, art, options, oldRasterOf), "the same binding over the same art pulls nothing")
		assertNull(ArtworkReloadPlanner.planRelink(model(), tile1, SourceLayerRef(source, "lyid:9", true), art, options, oldRasterOf), "a layer the file lacks pulls nothing")
	}

	@Test
	fun aRelinkAcrossFilesReadsTheOldRowFromTheTilesOwnFile() {
		// Tile 2 (file a, layer at (30, 40)) moves to file b's layer at (50, 60).  The edited mesh stays on
		// the canvas, so its coordinates carry by the delta between the OLD row in file a and the new
		// layer: read the old row from file b instead and the carry would be zero.
		val other = ArtSourceId("art-1")
		val layer3 = TestLayer("lyid:3", "Three", 0, LayerBounds(50, 60, 4, 4), solidRaster(4, 4, 3))
		val otherArt = TestArt(listOf(layer3))
		val model = model().let { base -> base.copy(sources = base.sources + ArtSource(other, "b.psd", "/b.psd", "psd", SourceArtImport.inventoryOf(otherArt))) }
		val target = SourceLayerRef(other, "lyid:3", true)
		val plan = assertNotNull(ArtworkReloadPlanner.planMatches(model, other, otherArt, listOf(tile2 to "lyid:3"), options, oldRasterOf))
		val replaced = plan.reload.replacedTiles.single()
		assertEquals(tile2, replaced.oldId)
		assertEquals(target, replaced.tile.source)
		val carried = plan.reload.drawableMeshes.getValue(DrawableId("d2"))
		assertContentEquals(floatArrayOf(-5f, -5f, -4.75f, -5f, -5f, -4.75f), carried.uvs, "carried by the old row's origin in file a")
		assertEquals(other, plan.reload.source.id, "the target file's record is the one refreshed")
		assertEquals(listOf("lyid:3"), plan.reload.source.layers.map { layer -> layer.key })
		assertTrue(plan.reload.source.layers.single().present, "the new binding's row is present, not lost")
	}

	/**
	 * A file placed on the document canvas (its record carries an offset) reloads in the document frame:
	 * the read is placed by the record before the planner sees it, so an unchanged file plans nothing, a
	 * repainted layer's untouched quad is re-born at the PLACED position, and the refreshed record keeps
	 * the offset.
	 */
	@Test
	fun aPlacedFileReloadsInTheDocumentFrameAndKeepsItsOffset() {
		val offset = CanvasOffset(100, 200)
		val placedArt = TestArt(listOf(layer1, layer2)).placedBy(offset)
		val placedQuad1 = SourceArtImport.birthMeshFor(placedArt.layers[0], options.alphaThreshold, options.birthMeshMargin)!!
		val base = model()
		val placedModel =
			base.copy(
				drawables = base.drawables.map { drawable -> if (drawable.id == DrawableId("d1")) drawable.copy(mesh = placedQuad1) else drawable },
				sources = listOf(ArtSource(source, "a.psd", "/a.psd", "psd", SourceArtImport.inventoryOf(placedArt), offsetX = offset.x, offsetY = offset.y)),
			)
		val record = placedModel.sources.single()
		assertEquals(110 to 220, record.layers.first().let { row -> row.left to row.top }, "the inventory is in the document frame")

		assertNull(ArtworkReloadPlanner.plan(placedModel, source, TestArt(listOf(layer1, layer2)).placedFor(record), options, oldRasterOf), "the same file, placed the same way, plans nothing")

		val repainted = TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 6, 6), solidRaster(6, 6, 9))
		val plan = assertNotNull(ArtworkReloadPlanner.plan(placedModel, source, TestArt(listOf(repainted, layer2)).placedFor(record), options, oldRasterOf))
		assertEquals(listOf(tile1), plan.reload.replacedTiles.map { replaced -> replaced.oldId })
		val reborn = plan.reload.drawableMeshes.getValue(DrawableId("d1"))
		assertContentEquals(floatArrayOf(108f, 218f, 118f, 218f, 118f, 228f, 108f, 228f), reborn.positions, "re-born over the repainted art at its placed position")
		assertEquals(offset.x to offset.y, plan.reload.source.offsetX to plan.reload.source.offsetY, "the refreshed record keeps the offset")
		assertEquals(110 to 220, plan.reload.source.layers.first { row -> row.key == "lyid:1" }.let { row -> row.left to row.top }, "and its rows stay in the document frame")
	}

	/**
	 * A relink onto a placed file carries the mesh by the OLD row in the tile's own file against the
	 * target layer's PLACED bounds, so the canvas-attached vertex keeps sampling the same canvas pixel.
	 */
	@Test
	fun aRelinkOntoAPlacedFileCarriesAgainstThePlacedLayer() {
		val other = ArtSourceId("art-1")
		val layer3 = TestLayer("lyid:3", "Three", 0, LayerBounds(50, 60, 4, 4), solidRaster(4, 4, 3))
		val otherRecord = ArtSource(other, "b.psd", "/b.psd", "psd", offsetX = -20, offsetY = -19)
		val otherArt = TestArt(listOf(layer3)).placedFor(otherRecord)
		val model = model().let { base -> base.copy(sources = base.sources + otherRecord.copy(layers = SourceArtImport.inventoryOf(otherArt))) }
		val plan = assertNotNull(ArtworkReloadPlanner.planMatches(model, other, otherArt, listOf(tile2 to "lyid:3"), options, oldRasterOf))
		val carried = plan.reload.drawableMeshes.getValue(DrawableId("d2"))
		// The target sits at (30, 41) placed: the old row's (30, 40) is one pixel above it, so every
		// coordinate moves up by a quarter of the 4 px raster.
		assertContentEquals(floatArrayOf(0f, -0.25f, 0.25f, -0.25f, 0f, 0f), carried.uvs, "carried against the placed bounds")
		assertEquals(-20 to -19, plan.reload.source.offsetX to plan.reload.source.offsetY, "the target's record keeps its offset")
	}

	/** The read's modification time rides the refreshed record like the hash, and alone plans nothing. */
	@Test
	fun theModificationTimeIsRecordedLikeTheHashAndAloneChangesNothing() {
		val art = TestArt(listOf(layer1, layer2))
		assertNull(ArtworkReloadPlanner.plan(model(), source, art, options, oldRasterOf, contentHash = "h", lastModified = 5L), "a time and hash alone plan nothing")
		val repainted = TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 4, 4), solidRaster(4, 4, 9))
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(repainted, layer2)), options, oldRasterOf, contentHash = "h", lastModified = 5L))
		assertEquals(5L, plan.reload.source.lastModified)
		assertEquals("h", plan.reload.source.contentHash)
		val matched = assertNotNull(ArtworkReloadPlanner.planMatches(model(), source, art, listOf(tile1 to "lyid:2"), options, oldRasterOf, lastModified = 7L))
		assertEquals(7L, matched.reload.source.lastModified)
		val replaced = assertNotNull(ArtworkReloadPlanner.plan(model(), source, art, options, oldRasterOf, replacement = ArtSourceDescriptor("b.psd", "/b.psd", "psd", lastModified = 9L)))
		assertEquals(9L, replaced.reload.source.lastModified, "a replacement records its own file's time")
	}

	/**
	 * A layer erased to nothing is not a deletion the reconcile can act on: the tile keeps its art, the
	 * inventory records the layer as empty, and the binding goes to review with its own reason - the
	 * artist may have deleted it this way or left it blank on purpose.
	 */
	@Test
	fun aLayerErasedToNothingLeavesItsTileAndGoesToReview() {
		val erased = TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 4, 4), LayerRaster(4, 4, ByteArray(64)))
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(erased, layer2)), options, oldRasterOf))
		assertTrue(plan.reload.replacedTiles.isEmpty())
		assertEquals(listOf(SourceArtImportNotice.EmptyLayer("One")), plan.notices)
		assertEquals(listOf(ReconcileResult.NeedsReview(ref1, ReviewReason.LayerEmptied)), plan.report.needsReview)
		val row = plan.reload.source.layers.first { layer -> layer.key == "lyid:1" }
		assertTrue(row.present && row.empty, "still in the file, recorded as empty")
		assertEquals(listOf(10, 20, 4, 4), listOf(row.left, row.top, row.width, row.height), "with the frame the art last had, not the collapse")

		// The artist undoes the erasure (the art program saves the layer collapsed to nothing meanwhile):
		// the art is back where the tile's art came from, so nothing is replaced and no coordinate moves.
		val collapsed = TestLayer("lyid:1", "One", 0, LayerBounds(0, 0, 0, 0), LayerRaster(0, 0, ByteArray(0)))
		val whileErased = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(collapsed, layer2)), options, oldRasterOf))
		val erasedModel = model().copy(sources = listOf(whileErased.reload.source))
		val restored = assertNotNull(ArtworkReloadPlanner.plan(erasedModel, source, TestArt(listOf(layer1, layer2)), options, oldRasterOf))
		assertTrue(restored.reload.replacedTiles.isEmpty(), "the same art at the same frame replaces nothing")
		assertTrue(restored.reload.drawableMeshes.isEmpty(), "and moves no coordinate")
		assertTrue(restored.report.needsReview.isEmpty(), "and the review is over")
		val back = restored.reload.source.layers.first { layer -> layer.key == "lyid:1" }
		assertTrue(back.present && !back.empty, "the row reads as art again")
	}

	@Test
	fun acceptedMatchesReplaceTheirTilesInOnePlanAndDropTheLostRows() {
		// Layer 2 was renamed under a new key and left for review by an earlier reload (its bar raised
		// past the match), so its row is kept not present and the tile still binds the old key; the
		// matcher's accepted pair moves the tile to the new layer.
		val renamed = TestLayer("lyid:5", "Two (final)", 1, LayerBounds(30, 40, 4, 4), layer2.raster)
		val previous = model().sources.single()
		val lostRows = inventoryWithMissing(previous.layers, SourceArtImport.inventoryOf(TestArt(listOf(layer1, renamed))), boundKeys = setOf("lyid:1", "lyid:2"))
		assertEquals(listOf(true, true, false), lostRows.map { layer -> layer.present })
		val model = model().copy(sources = listOf(previous.copy(layers = lostRows)))
		val plan = assertNotNull(ArtworkReloadPlanner.planMatches(model, source, TestArt(listOf(layer1, renamed)), listOf(tile2 to "lyid:5"), options, oldRasterOf))
		val replaced = plan.reload.replacedTiles.single()
		assertEquals(tile2, replaced.oldId)
		assertEquals(SourceLayerRef(source, "lyid:5", true), replaced.tile.source)
		assertEquals("Two (final)", replaced.tile.name)
		assertEquals(listOf(ReconcileResult.Matched(SourceLayerRef(source, "lyid:5", true), "lyid:5")), plan.report.results)
		assertEquals(listOf("lyid:1", "lyid:5"), plan.reload.source.layers.map { layer -> layer.key }, "the lost row leaves once nothing binds it")
		assertNull(plan.reload.additions)
		assertNull(ArtworkReloadPlanner.planMatches(model, source, TestArt(listOf(layer1, renamed)), listOf(tile2 to "lyid:9"), options, oldRasterOf), "a key the file lacks rebinds nothing")
	}

	@Test
	fun aReplacementRewritesTheRecordReboundsTheConfidentBindingsAndFlagsTheRest() {
		// The same art saved from another program: new keys for the same layers, so both bindings are
		// rebound by their pixels inside the replace step itself.
		val clipOne = TestLayer("clip:a", "One", 0, LayerBounds(10, 20, 4, 4), layer1.raster)
		val clipTwo = TestLayer("clip:b", "Two", 1, LayerBounds(30, 40, 4, 4), layer2.raster)
		val descriptor = ArtSourceDescriptor("a.clip", "/a.clip", "clip")
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(clipOne, clipTwo)), options, oldRasterOf, contentHash = "h", replacement = descriptor))
		assertEquals("a.clip", plan.reload.source.name)
		assertEquals("/a.clip", plan.reload.source.path)
		assertEquals("clip", plan.reload.source.format)
		assertEquals("h", plan.reload.source.contentHash)
		assertEquals(listOf(tile1, tile2), plan.reload.replacedTiles.map { replaced -> replaced.oldId }, "both bindings rebound")
		assertEquals(listOf(SourceLayerRef(source, "clip:a", true), SourceLayerRef(source, "clip:b", true)), plan.reload.replacedTiles.map { replaced -> replaced.tile.source })
		assertEquals(listOf(ReconcileResult.Rebound(ref1, "clip:a", 1f), ReconcileResult.Rebound(ref2, "clip:b", 1f)), plan.report.results)
		assertEquals(listOf("clip:a", "clip:b"), plan.reload.source.layers.map { layer -> layer.key }, "no lost row remains")
		assertNull(plan.reload.additions)

		// Art that resolves nothing: every binding is flagged as replaced, and the new file's layers stay
		// unbound as the candidates the review matches against.
		val strangers =
			TestArt(
				listOf(
					TestLayer("clip:y", "Why", 0, LayerBounds(200, 200, 8, 8), solidRaster(8, 8, 7)),
					TestLayer("clip:z", "Zed", 1, LayerBounds(300, 300, 8, 8), solidRaster(8, 8, 8)),
				),
			)
		val flagged = assertNotNull(ArtworkReloadPlanner.plan(model(), source, strangers, options, oldRasterOf, contentHash = "h", replacement = descriptor))
		assertEquals(listOf("clip:y", "clip:z", "lyid:1", "lyid:2"), flagged.reload.source.layers.map { layer -> layer.key })
		assertEquals(listOf(true, true, false, false), flagged.reload.source.layers.map { layer -> layer.present })
		assertEquals(listOf(false, false, true, true), flagged.reload.source.layers.map { layer -> layer.replaced }, "the rows the replace lost say so")
		assertTrue(flagged.reload.replacedTiles.isEmpty(), "no binding resolved by key or by match")
		assertNull(flagged.reload.additions, "the new file's layers stay unbound as the candidates the review matches against")
		assertEquals(
			listOf(ReconcileResult.NeedsReview(ref1, ReviewReason.SourceReplaced), ReconcileResult.NeedsReview(ref2, ReviewReason.SourceReplaced)),
			flagged.report.needsReview,
		)
		// A same-format twin resolves by key and the plan still lands, if only to rewrite the record.
		val twin = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(layer1, layer2)), options, oldRasterOf, replacement = ArtSourceDescriptor("b.psd", "/b.psd", "psd")))
		assertEquals("b.psd", twin.reload.source.name)
		assertTrue(twin.report.needsReview.isEmpty())

		// The replacement stands in for the same art, so the record's placement on the canvas is kept.
		val placedModel = model().let { base -> base.copy(sources = base.sources.map { record -> record.copy(offsetX = 5, offsetY = 6) }) }
		val placedTwin = assertNotNull(ArtworkReloadPlanner.plan(placedModel, source, TestArt(listOf(layer1, layer2)), options, oldRasterOf, replacement = ArtSourceDescriptor("b.psd", "/b.psd", "psd")))
		assertEquals(5 to 6, placedTwin.reload.source.offsetX to placedTwin.reload.source.offsetY, "the replaced record keeps its offset")
	}

	@Test
	fun aReCreatedLayerIsReboundInTheReloadInsteadOfMinted() {
		// Layer 1 deleted and re-created under a new key (a duplicate, a paste) with its pixels and place:
		// the rigged tile follows it in the same step and nothing is minted beside it.
		val recreated = TestLayer("lyid:3", "One", 0, LayerBounds(10, 20, 4, 4), layer1.raster)
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(recreated, layer2)), options, oldRasterOf))
		val replaced = plan.reload.replacedTiles.single()
		assertEquals(tile1, replaced.oldId)
		assertEquals(SourceLayerRef(source, "lyid:3", true), replaced.tile.source)
		assertEquals("One", replaced.tile.name)
		assertSame(recreated.raster, plan.rasterByTile.getValue(replaced.tile.id))
		assertNull(plan.reload.additions, "the re-created layer is not minted beside the rebound tile")
		assertTrue(plan.reload.retiredTiles.isEmpty())
		assertEquals(listOf(ReconcileResult.Rebound(ref1, "lyid:3", 1f), ReconcileResult.Matched(ref2, "lyid:2")), plan.report.results)
		assertTrue(plan.report.needsReview.isEmpty())
		assertEquals(listOf("lyid:3", "lyid:2"), plan.reload.source.layers.map { layer -> layer.key }, "no lost row is kept")
		assertTrue(plan.reload.source.layers.all { layer -> layer.present })
		assertTrue(plan.leftovers.isEmpty())
		val expected = SourceArtImport.birthMeshFor(recreated, options.alphaThreshold, options.birthMeshMargin)!!
		assertContentEquals(expected.positions, plan.reload.drawableMeshes.getValue(DrawableId("d1")).positions, "the untouched quad is re-born over the layer")
	}

	@Test
	fun belowTheBarTheLayerIsMintedAndTheRowStaysForReviewWhileALowerBarRebinds() {
		// Layer 1 deleted and an unrelated layer added: nothing near the bar, so the stranger is minted
		// and the binding waits for a person; the same read at a lower bar rebinds instead.
		val stranger = TestLayer("lyid:3", "Something else", 0, LayerBounds(200, 200, 8, 8), solidRaster(8, 8, 7))
		val art = TestArt(listOf(stranger, layer2))
		val atBar = assertNotNull(ArtworkReloadPlanner.plan(model(), source, art, options, oldRasterOf))
		assertEquals(listOf(ReconcileResult.NeedsReview(ref1, ReviewReason.LayerMissing)), atBar.report.needsReview)
		assertEquals(listOf(AtlasTileId("art-0/lyid:3")), assertNotNull(atBar.reload.additions).tiles.map { tile -> tile.id }, "the stranger is minted")
		assertTrue(atBar.reload.replacedTiles.isEmpty())
		assertEquals(listOf(true, true, false), atBar.reload.source.layers.map { layer -> layer.present })
		val leftover = assertNotNull(atBar.leftovers["lyid:1"], "the proposal under the bar is handed out, scored before the mint")
		assertEquals("lyid:3", leftover.key)
		val lowered = assertNotNull(ArtworkReloadPlanner.plan(model(), source, art, options, oldRasterOf, matchThreshold = 0f))
		assertEquals(tile1, lowered.reload.replacedTiles.single().oldId)
		assertNull(lowered.reload.additions)
		val rebound = lowered.report.results.filterIsInstance<ReconcileResult.Rebound>().single()
		assertEquals("lyid:3", rebound.layerKey)
		assertTrue(rebound.score < InventoryLayerMatcher.DEFAULT_THRESHOLD, "well under the default bar: ${rebound.score}")
		assertTrue(lowered.report.needsReview.isEmpty())
		assertTrue(lowered.leftovers.isEmpty())
	}

	@Test
	fun twoLostKeysPreferringOneAddedLayerSettleOnTheMoreConfident() {
		// Both layers deleted and one re-created with layer 1's pixels; the other lost binding hashes to
		// it too (the same pixels), so the first in binding order takes it and the other waits.
		val samePixels = TestLayer("lyid:2", "Two", 1, LayerBounds(30, 40, 4, 4), layer1.raster)
		val model = model().copy(sources = listOf(ArtSource(source, "a.psd", "/a.psd", "psd", SourceArtImport.inventoryOf(TestArt(listOf(layer1, samePixels))))))
		val rasters = mapOf(tile1 to layer1.raster, tile2 to layer1.raster)
		val twin = TestLayer("lyid:3", "One", 0, LayerBounds(10, 20, 4, 4), layer1.raster)
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model, source, TestArt(listOf(twin)), options, { tileId -> rasters[tileId] }))
		assertEquals(listOf(tile1), plan.reload.replacedTiles.map { replaced -> replaced.oldId }, "one binding takes the twin")
		assertEquals(listOf(ReconcileResult.NeedsReview(ref2, ReviewReason.LayerMissing)), plan.report.needsReview, "the other stays for review")
		assertNull(plan.reload.additions, "the twin is not minted, it was claimed")
		assertEquals(listOf("lyid:3", "lyid:2"), plan.reload.source.layers.map { layer -> layer.key })
		assertEquals(listOf(true, false), plan.reload.source.layers.map { layer -> layer.present })
	}

	@Test
	fun anAcceptedMatchRetiresTheUntouchedDrawableOverItsLayerAndKeepsARiggedOne() {
		// Layer 1 was re-created under lyid:3 and an earlier reload minted a fresh drawable for it (the
		// match scored under the bar); accepting the reload's proposal now moves the rigged tile onto the
		// layer and retires the fresh drawable with its tile - the tile the proposal named, and only
		// when it still carries no rig work: the same fresh drawable, once edited, stays, and the layer
		// is then bound twice for a person to sort out.
		val recreated = TestLayer("lyid:3", "One", 0, LayerBounds(10, 20, 4, 4), layer1.raster)
		val freshQuad = SourceArtImport.birthMeshFor(recreated, options.alphaThreshold, options.birthMeshMargin)!!
		val tile3 = AtlasTileId("art-0/lyid:3")
		val base = model()
		val freshInventory =
			inventoryWithMissing(base.sources.single().layers, SourceArtImport.inventoryOf(TestArt(listOf(recreated, layer2))), boundKeys = setOf("lyid:1", "lyid:2", "lyid:3"))
		val afterMint =
			base.copy(
				drawables = base.drawables + drawable("d3", tile3, freshQuad),
				rootChildren = base.rootChildren + OrgChild.Drawable(DrawableId("d3")),
				atlas = base.atlas.copy(tiles = base.atlas.tiles + AtlasTile(tile3, "One", 4, 4, source = SourceLayerRef(source, "lyid:3", true))),
				sources = listOf(base.sources.single().copy(layers = freshInventory)),
			)
		val rasters = oldRasters + (tile3 to recreated.raster)
		val art = TestArt(listOf(recreated, layer2))
		assertTrue(suggestionsFor(afterMint, source).isEmpty(), "on the model alone the bound layer is no candidate; the proposal is the reload's")
		val plan = assertNotNull(ArtworkReloadPlanner.planMatches(afterMint, source, art, listOf(tile1 to "lyid:3"), options, { tileId -> rasters[tileId] }, retire = setOf(tile3)))
		assertEquals(tile1, plan.reload.replacedTiles.single().oldId)
		assertEquals(listOf(tile3), plan.reload.retiredTiles, "the fresh drawable's tile goes with the move")
		assertEquals(listOf("lyid:3", "lyid:2"), plan.reload.source.layers.map { layer -> layer.key }, "the lost row leaves; the claimed layer stays bound through the rebound tile")
		val unnamed = assertNotNull(ArtworkReloadPlanner.planMatches(afterMint, source, art, listOf(tile1 to "lyid:3"), options, { tileId -> rasters[tileId] }))
		assertTrue(unnamed.reload.retiredTiles.isEmpty(), "a relink that names nothing retires nothing")

		val editedFresh = DrawableMesh(floatArrayOf(10f, 20f, 11f, 20f, 10f, 21f), floatArrayOf(0f, 0f, 0.25f, 0f, 0f, 0.25f), intArrayOf(0, 1, 2))
		val edited = afterMint.copy(drawables = afterMint.drawables.map { drawable -> if (drawable.id.raw == "d3") drawable.copy(mesh = editedFresh) else drawable })
		val kept = assertNotNull(ArtworkReloadPlanner.planMatches(edited, source, art, listOf(tile1 to "lyid:3"), options, { tileId -> rasters[tileId] }, retire = setOf(tile3)))
		assertTrue(kept.reload.retiredTiles.isEmpty(), "rig work over the layer is never removed, whatever the proposal named")
	}

	@Test
	fun anIgnoredLayerIsNeverMintedUntilTheMarkIsCleared() {
		// A third layer the rigger ignored (a sketch kept in the file): every reload finds it unbound and
		// leaves it out, the mark rides the refreshed inventory, and the matcher never proposes it; clear
		// the mark and the next reload mints it.
		val third = TestLayer("lyid:3", "Sketch", 2, LayerBounds(50, 50, 2, 2), solidRaster(2, 2, 3))
		val art = TestArt(listOf(layer1, layer2, third))
		val base = model()
		val listed = base.copy(sources = listOf(base.sources.single().copy(layers = SourceArtImport.inventoryOf(art).map { row -> if (row.key == "lyid:3") row.copy(ignored = true) else row })))
		assertNull(ArtworkReloadPlanner.plan(listed, source, art, options, oldRasterOf), "nothing to do: the ignored layer is not an addition")
		val repainted = TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 4, 4), solidRaster(4, 4, 9))
		val plan = assertNotNull(ArtworkReloadPlanner.plan(listed, source, TestArt(listOf(repainted, layer2, third)), options, oldRasterOf))
		assertNull(plan.reload.additions, "the ignored layer stays out while another layer reloads")
		assertTrue(plan.report.results.none { result -> result is ReconcileResult.Added })
		assertTrue(plan.reload.source.layers.first { layer -> layer.key == "lyid:3" }.ignored, "the mark rides the refreshed inventory")
		assertTrue(suggestionsFor(listed, source).isEmpty(), "and the matcher never proposes it")
		val cleared = listed.copy(sources = listOf(listed.sources.single().copy(layers = listed.sources.single().layers.map { row -> row.copy(ignored = false) })))
		val minted = assertNotNull(ArtworkReloadPlanner.plan(cleared, source, art, options, oldRasterOf))
		assertEquals(listOf(AtlasTileId("art-0/lyid:3")), assertNotNull(minted.reload.additions).tiles.map { tile -> tile.id }, "un-ignored, the next reload mints it")
	}

	@Test
	fun aLayerEyeToggleFollowsIntoAnUntouchedDrawableAndLeavesAToggledOne() {
		// Layer 1 hidden in the file: d1 still shows the state the file last had (shown), so it follows,
		// with no tile replaced.  Hidden by the rigger first, d1 already differs from that state - its
		// toggle is rig work - so the file's toggle changes nothing.
		val hidden = TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 4, 4), layer1.raster, visible = false)
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(hidden, layer2)), options, oldRasterOf))
		assertEquals(mapOf(DrawableId("d1") to false), plan.reload.drawableVisibility)
		assertTrue(plan.reload.replacedTiles.isEmpty(), "an eye toggle replaces no tile")
		assertEquals(false, plan.reload.source.layers.first { layer -> layer.key == "lyid:1" }.visible, "the inventory records the new state")

		// d1 hidden while the inventory still says shown: the rigger's own toggle, so the file hiding the
		// layer changes nothing; once the inventory records hidden too, the file showing it again is
		// followed, since d1 then shows exactly the state the file last had.
		val riggerHid = model().let { base -> base.copy(drawables = base.drawables.map { drawable -> if (drawable.id.raw == "d1") drawable.copy(isVisible = false) else drawable }) }
		val untouched = assertNotNull(ArtworkReloadPlanner.plan(riggerHid, source, TestArt(listOf(hidden, layer2)), options, oldRasterOf))
		assertTrue(untouched.reload.drawableVisibility.isEmpty(), "a drawable the rigger already hid is left alone")
		assertEquals(false, untouched.reload.source.layers.first { layer -> layer.key == "lyid:1" }.visible, "while the inventory still records the file's state")
		val recorded = riggerHid.copy(sources = listOf(untouched.reload.source))
		assertNull(ArtworkReloadPlanner.plan(recorded, source, TestArt(listOf(hidden, layer2)), options, oldRasterOf), "recorded and unchanged: nothing to do")
		val shownAgain = assertNotNull(ArtworkReloadPlanner.plan(recorded, source, TestArt(listOf(layer1, layer2)), options, oldRasterOf))
		assertEquals(mapOf(DrawableId("d1") to true), shownAgain.reload.drawableVisibility, "shown again in the file, a hidden drawable that matches the recorded state follows back")
	}
}