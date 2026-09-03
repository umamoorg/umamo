package org.umamo.ui.model

import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.withAtlasPins
import org.umamo.edit.withAtlasPlacements
import org.umamo.edit.withParameter
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.packAtlas
import org.umamo.render.DecodedImage
import org.umamo.render.SourceArtRasters
import org.umamo.render.atlasCompositionOf
import org.umamo.render.atlasPlacementFromPack
import org.umamo.render.deriveAtlasTextures
import org.umamo.render.encodeAtlasPng
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the repack as the operation settings strip's first client: the first run registers itself
 * with its options as rows, an adjustment re-packs the same decoded input under the edited options
 * from the model the repack ran on and rewrites the repack's own history step (the stack does not
 * grow, one undo returns to the base), the result equals a fresh pack under those options and
 * derives byte-identically under the composition it recorded, a refused adjustment leaves the
 * previous result standing with the record still live, and a pinned tile keeps its placement
 * through a repack until the Keep Pinned Tiles row is turned off.
 */
class AtlasRepackAdjustTest {
	private val tileIds = listOf(AtlasTileId("t0"), AtlasTileId("t1"), AtlasTileId("t2"))

	private fun tileRaster(width: Int, height: Int, margin: Int, red: Int): DecodedImage {
		val rgba = ByteArray(width * height * 4)
		for (rowIndex in margin until height - margin) {
			for (columnIndex in margin until width - margin) {
				val offset = (rowIndex * width + columnIndex) * 4
				rgba[offset] = red.toByte()
				rgba[offset + 1] = (rowIndex * 16 + columnIndex).toByte()
				rgba[offset + 2] = 0x40
				rgba[offset + 3] = 0xFF.toByte()
			}
		}
		return DecodedImage(rgba, width, height)
	}

	private class Fixture(val model: PuppetModel, val store: SourceArtRasters)

	/**
	 * Three unpacked tiles, each sampled by one quad-meshed drawable over its whole art, with a store
	 * serving their pixels - a document the repack packs from scratch.
	 *
	 * @return Fixture The model and its store.
	 */
	private fun unpackedFixture(): Fixture {
		val rasters =
			listOf(
				tileRaster(12, 10, 2, 0x10),
				tileRaster(8, 16, 1, 0x20),
				tileRaster(10, 10, 0, 0x30),
			)
		val tiles = tileIds.mapIndexed { tileIndex, tileId -> AtlasTile(tileId, tileId.raw, rasters[tileIndex].width, rasters[tileIndex].height) }
		val drawables =
			tileIds.mapIndexed { tileIndex, tileId ->
				Drawable(
					id = DrawableId("d$tileIndex"),
					name = "d$tileIndex",
					parentDeformerId = null,
					blendMode = BlendMode.Normal,
					maskedBy = emptyList(),
					mesh =
						DrawableMesh(
							floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
							floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
							intArrayOf(0, 1, 2, 1, 3, 2),
						),
					geometryGrid = null,
					atlasTileId = tileId,
				)
			}
		val model =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = drawables,
				rootChildren = emptyList(),
				rootPartId = null,
				canvasWidth = 100f,
				canvasHeight = 100f,
				worldOriginX = 50f,
				worldOriginY = 50f,
				atlas = PuppetAtlas(pages = emptyList(), tiles = tiles),
			)
		val pngByTile = tileIds.withIndex().associate { (tileIndex, tileId) -> tileId to encodeAtlasPng(rasters[tileIndex]) }
		return Fixture(model, SourceArtRasters { tileId -> pngByTile[tileId] })
	}

	@Test
	fun anAdjustmentRepacksFromTheBaseAndRewritesTheRepackStep() =
		runBlocking {
			val fixture = unpackedFixture()
			val session = EditorSession(fixture.model)
			var remembered: AtlasPackOptions? = null
			var reports = 0
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = fixture.store,
					sessionAtlasPages = null,
					premultipliedAlpha = false,
					scope = this,
					report = { reports++ },
					rememberOptions = { options, _ -> remembered = options },
				)
			val initial = AtlasPackOptions(maxPageSize = 64)

			runAtlasRepack(host, initial, areaId = "uv-1")

			val record = assertNotNull(session.adjustableOperation.value, "the repack registers itself as adjustable")
			assertEquals("uv-1", record.areaId)
			assertEquals(initial, remembered, "the options the pack ran with are remembered")
			assertSame(fixture.model, record.baseSnapshot.model, "the base is the model the repack ran on")
			val stepsAfterRepack = session.historyView.value.steps.size
			assertEquals(2, stepsAfterRepack, "seed plus one repack step")

			val adjustedParameters =
				record.parameters
					.withParameter(RepackParameterKeys.GUTTER, OperatorParameter.IntParameter(RepackParameterKeys.GUTTER, RepackParameterKeys.GUTTER, 8, 0, REPACK_MAX_GUTTER))
					.withParameter(RepackParameterKeys.ALLOW_ROTATION, OperatorParameter.BooleanParameter(RepackParameterKeys.ALLOW_ROTATION, RepackParameterKeys.ALLOW_ROTATION, true))
			session.adjustLastOperation(adjustedParameters)
			coroutineContext.job.children.toList().joinAll()

			val expectedOptions = initial.copy(gutter = 8, allowRotation = true)
			assertEquals(expectedOptions, remembered, "the adjusted options are what the re-pack ran with")
			assertEquals(0, reports, "nothing refused")
			assertEquals(stepsAfterRepack, session.historyView.value.steps.size, "the adjustment rewrote the step rather than adding one")
			assertNotNull(session.adjustableOperation.value, "the record stays live for the next adjustment")

			// The result is exactly a fresh pack of the same input under those options, from the base.
			val packInput = buildRepackPackInput(fixture.model) { tileId -> fixture.store.decodeRaster(tileId) }
			val fresh = packAtlas(packInput.items, expectedOptions)
			val freshByKey = fresh.placements.associateBy { placement -> placement.key }
			val adjustedAtlas = session.model.value.atlas
			assertEquals(fresh.pages.map { page -> AtlasPage(page.width, page.height) }, adjustedAtlas.pages)
			for (tile in adjustedAtlas.tiles) {
				assertEquals(freshByKey[tile.id.raw]?.let(::atlasPlacementFromPack), tile.placement, "tile ${tile.id.raw} sits where the fresh pack put it")
			}
			assertEquals(atlasCompositionOf(expectedOptions), adjustedAtlas.composition, "the composition follows the adjusted options")
			val derived = assertNotNull(deriveAtlasTextures(session.model.value, fixture.store, false))
			for (pageIndex in fresh.pages.indices) {
				assertContentEquals(fresh.pages[pageIndex].rgba, derived.atlases[pageIndex].rgba, "page $pageIndex derives byte-identically under the recorded composition")
			}

			session.undo()
			assertSame(fixture.model, session.model.value, "one undo returns to the model the repack ran on")
			assertNull(session.adjustableOperation.value, "undo ends the adjustable operation")
		}

	@Test
	fun aPinnedTileKeepsItsPlacementThroughARepackAndMovesWhenTheRowIsOff() =
		runBlocking {
			val fixture = unpackedFixture()
			// Tile 1 hand-placed through the placement edit - turned, reduced, at (30, 30) on a 64 page, its
			// coordinates re-mapped so its mesh still reaches its own art - and pinned; the others unpacked.
			val kept = AtlasPlacement(pageIndex = 0, positionX = 30f, positionY = 30f, scaleX = 0.75f, scaleY = 0.75f, rotationDegrees = 30f)
			val pinnedTileId = tileIds[1]
			val pinnedModel =
				fixture.model
					.copy(atlas = fixture.model.atlas.copy(pages = listOf(AtlasPage(64, 64))))
					.withAtlasPlacements(mapOf(pinnedTileId to kept))
					.withAtlasPins(listOf(pinnedTileId), pinned = true)
			assertTrue(pinnedModel.atlas.tileById.getValue(pinnedTileId).pinned, "the fixture pinned its tile")
			val session = EditorSession(pinnedModel)
			var rememberedKeepPinned: Boolean? = null
			val reports = ArrayList<AtlasRepackReport>()
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = fixture.store,
					sessionAtlasPages = null,
					premultipliedAlpha = false,
					scope = this,
					report = { report -> reports.add(report) },
					rememberOptions = { _, keepPinned -> rememberedKeepPinned = keepPinned },
				)
			val options = AtlasPackOptions(maxPageSize = 64)
			val uvsBefore = pinnedModel.drawables.first { drawable -> drawable.atlasTileId == pinnedTileId }.mesh!!.uvs

			runAtlasRepack(host, options, areaId = null)

			val refusalText = reports.flatMap { report -> report.refusals }.joinToString { refusal -> "${refusal.tileName}: ${refusal.reason}" }
			val record = assertNotNull(session.adjustableOperation.value, "the repack registered (refusals: $refusalText)")
			assertEquals(true, rememberedKeepPinned)
			assertTrue(repackKeepPinnedOf(record.parameters), "the row shows the pins kept")
			val repacked = session.model.value
			val keptTile = repacked.atlas.tileById.getValue(pinnedTileId)
			assertEquals(kept, keptTile.placement, "the pinned tile stayed exactly where it was")
			assertTrue(keptTile.pinned, "and stays pinned")
			assertSame(uvsBefore, repacked.drawables.first { drawable -> drawable.atlasTileId == pinnedTileId }.mesh!!.uvs, "its coordinates did not move")
			for (tile in repacked.atlas.tiles) {
				if (tile.id != pinnedTileId) {
					assertNotNull(tile.placement, "tile ${tile.id.raw} packed around the pin")
				}
			}
			// The pages the pack composed (what the resolver is pre-warmed with) are what the model derives.
			val packInput = buildRepackPackInput(pinnedModel) { tileId -> fixture.store.decodeRaster(tileId) }
			val fresh = packAtlas(packInput.itemsFor(keepPinned = true), options)
			assertEquals(pinnedTileId.raw, fresh.fixed.single().key)
			val derived = assertNotNull(deriveAtlasTextures(repacked, fixture.store, false))
			for (pageIndex in fresh.pages.indices) {
				assertContentEquals(fresh.pages[pageIndex].rgba, derived.atlases[pageIndex].rgba, "page $pageIndex derives byte-identically, pinned tile included")
			}
			val stepsAfterRepack = session.historyView.value.steps.size

			// Keep Pinned Tiles off: the same decoded input re-packs with the pinned tile free.
			session.adjustLastOperation(
				record.parameters.withParameter(RepackParameterKeys.KEEP_PINNED, OperatorParameter.BooleanParameter(RepackParameterKeys.KEEP_PINNED, RepackParameterKeys.KEEP_PINNED, false)),
			)
			coroutineContext.job.children.toList().joinAll()

			assertEquals(false, rememberedKeepPinned)
			assertEquals(stepsAfterRepack, session.historyView.value.steps.size, "the adjustment rewrote the step")
			val freed = session.model.value.atlas.tileById.getValue(pinnedTileId)
			val freedPlacement = assertNotNull(freed.placement)
			assertTrue(freedPlacement != kept && freedPlacement.rotationDegrees == 0f && freedPlacement.scaleX == 1f, "the tile packed like any other")
			assertTrue(freed.pinned, "the pin itself survives the pack that ignored it")
		}

	@Test
	fun aRefusedAdjustmentLeavesThePreviousResultStanding() =
		runBlocking {
			val fixture = unpackedFixture()
			val session = EditorSession(fixture.model)
			var reports = 0
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = fixture.store,
					sessionAtlasPages = null,
					premultipliedAlpha = false,
					scope = this,
					report = { reports++ },
					rememberOptions = { _, _ -> },
				)
			runAtlasRepack(host, AtlasPackOptions(maxPageSize = 64), areaId = null)
			val record = assertNotNull(session.adjustableOperation.value)
			val repacked = session.model.value

			// A page too small for any tile: every bound tile refuses.
			val tooSmall = record.parameters.withParameter(RepackParameterKeys.PAGE_SIZE, OperatorParameter.ChoiceParameter(RepackParameterKeys.PAGE_SIZE, RepackParameterKeys.PAGE_SIZE, "4", emptyList()))
			session.adjustLastOperation(tooSmall)
			coroutineContext.job.children.toList().joinAll()

			assertEquals(1, reports, "the refusal reports through the same surface as a first run's")
			assertSame(repacked, session.model.value, "the previous result stands")
			assertTrue(session.adjustableOperation.value != null, "the record stays live so the rigger can pick another size")
		}
}