package org.umamo.reimport

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.art.SourceArtImportNotice
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
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
		assertEquals(listOf(ReconcileResult.NeedsReview(ref2, ReviewReason.LayerMissing)), plan.report.needsReview)
		assertTrue(plan.rasterByTile.containsKey(AtlasTileId("art-0/lyid:3")))
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

	@Test
	fun aLayerErasedToNothingLeavesItsTile() {
		val erased = TestLayer("lyid:1", "One", 0, LayerBounds(10, 20, 4, 4), LayerRaster(4, 4, ByteArray(64)))
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(erased, layer2)), options, oldRasterOf))
		// The tile keeps the art it had; the inventory records the layer's new hash, and the note says why nothing moved.
		assertTrue(plan.reload.replacedTiles.isEmpty())
		assertEquals(listOf(SourceArtImportNotice.EmptyLayer("One")), plan.notices)
	}

	@Test
	fun acceptedMatchesReplaceTheirTilesInOnePlanAndDropTheLostRows() {
		// Layer 2 was renamed under a new key on an earlier reload, so its row is kept not present and
		// the tile still binds the old key; the matcher's accepted pair moves the tile to the new layer.
		val renamed = TestLayer("lyid:5", "Two (final)", 1, LayerBounds(30, 40, 4, 4), layer2.raster)
		val afterRename = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(layer1, renamed)), options, oldRasterOf))
		assertEquals(listOf(true, true, false), afterRename.reload.source.layers.map { layer -> layer.present })
		val model = model().copy(sources = listOf(afterRename.reload.source))
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
	fun aReplacementRewritesTheRecordAndFlagsEveryUnresolvedBinding() {
		// The same art saved from another program: new keys for the same layers.
		val clipOne = TestLayer("clip:a", "One", 0, LayerBounds(10, 20, 4, 4), layer1.raster)
		val clipTwo = TestLayer("clip:b", "Two", 1, LayerBounds(30, 40, 4, 4), layer2.raster)
		val descriptor = ArtSourceDescriptor("a.clip", "/a.clip", "clip")
		val plan = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(clipOne, clipTwo)), options, oldRasterOf, contentHash = "h", replacement = descriptor))
		assertEquals("a.clip", plan.reload.source.name)
		assertEquals("/a.clip", plan.reload.source.path)
		assertEquals("clip", plan.reload.source.format)
		assertEquals("h", plan.reload.source.contentHash)
		assertEquals(listOf("clip:a", "clip:b", "lyid:1", "lyid:2"), plan.reload.source.layers.map { layer -> layer.key })
		assertEquals(listOf(true, true, false, false), plan.reload.source.layers.map { layer -> layer.present })
		assertTrue(plan.reload.replacedTiles.isEmpty(), "no binding resolved by key")
		assertNull(plan.reload.additions, "the new file's layers stay unbound as the candidates the review matches against")
		assertEquals(
			listOf(ReconcileResult.NeedsReview(ref1, ReviewReason.SourceReplaced), ReconcileResult.NeedsReview(ref2, ReviewReason.SourceReplaced)),
			plan.report.needsReview,
		)
		// A same-format twin resolves by key and the plan still lands, if only to rewrite the record.
		val twin = assertNotNull(ArtworkReloadPlanner.plan(model(), source, TestArt(listOf(layer1, layer2)), options, oldRasterOf, replacement = ArtSourceDescriptor("b.psd", "/b.psd", "psd")))
		assertEquals("b.psd", twin.reload.source.name)
		assertTrue(twin.report.needsReview.isEmpty())
	}
}