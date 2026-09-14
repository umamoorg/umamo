package org.umamo.ui.document

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.DocumentChange
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.commitArtworkReloaded
import org.umamo.edit.withAtlasRepack
import org.umamo.format.FileKind
import org.umamo.format.art.LayerBounds
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.render.deriveAtlasTextures
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.model.AtlasRepackHost
import org.umamo.ui.model.ImportParameterKeys
import org.umamo.ui.model.MatchParameterKeys
import org.umamo.ui.model.RelinkArtworkRequest
import org.umamo.ui.model.ReloadArtworkRequest
import org.umamo.ui.model.ReloadArtworkResult
import org.umamo.ui.model.ReloadEntry
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.runRelinkArtwork
import org.umamo.ui.model.runReloadArtwork
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Reload and relink driven the way the shell drives them, over a document built from in-memory art
 * so no corpus is needed: a repainted and grown layer, an unchanged one, and a new one land as one
 * step; the pages the session publishes equal their derivation; undo shows the old pixels again by
 * pure snapshot; an adjustment re-lands the step; a reload with nothing changed pushes nothing; a
 * relink pulls the target layer's art when its file is read and changes the binding alone when not;
 * and a layer re-created under a new key is rebound in the reload at the strip's bar.
 */
class ReloadArtworkFlowTest {
	private val options = SourceArtImportOptions(alphaThreshold = 1, birthMeshMargin = 2)
	private val sourceId = ArtSourceId("art-0")

	private val layerA = InMemoryLayer("lyid:1", "A", 0, LayerBounds(10, 10, 8, 8), solidRaster(8, 8, 1))
	private val layerB = InMemoryLayer("lyid:2", "B", 1, LayerBounds(40, 40, 8, 8), solidRaster(8, 8, 2))
	private val layerARepainted = InMemoryLayer("lyid:1", "A", 0, LayerBounds(10, 10, 12, 12), solidRaster(12, 12, 9))

	/** Inserted at the TOP of the file (above A and B), the way an artist adds a layer in Photoshop. */
	private val layerC = InMemoryLayer("lyid:3", "C", -1, LayerBounds(5, 50, 6, 6), solidRaster(6, 6, 3))

	/**
	 * A layer the file lost leaves its tile exactly as it was: same placement, same pages, same
	 * coordinates - a reload with nothing to pack touches no page.  Exercised with the added layer on a
	 * SECOND page, the way a full first page forces it, since that is where a re-pack that dropped or
	 * re-indexed pages would strand the tile.
	 */
	@Test
	fun aRemovedLayerLeavesItsTileAndThePagesUntouched() =
		runBlocking {
			// Two layers that fill the first page's width, so a third that fits a page but not the gaps
			// takes a second page.
			val wide = InMemoryLayer("lyid:1", "Wide", 0, LayerBounds(0, 0, 110, 110), solidRaster(110, 110, 1))
			val corner = InMemoryLayer("lyid:2", "Corner", 1, LayerBounds(114, 0, 8, 8), solidRaster(8, 8, 2))
			val load = buildArtDocument(InMemoryArt(listOf(wide, corner)), FileKind.Psd, "a.psd", "/art/a.psd", options)
			val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = document.artRasters,
					sessionAtlasPages = sessionAtlasPages,
					premultipliedAlpha = document.textures.premultipliedAlpha,
					scope = this,
					report = { report -> error("the reload must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val big = InMemoryLayer("lyid:9", "Big", 2, LayerBounds(0, 120, 30, 30), solidRaster(30, 30, 7))
			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(wide, corner, big)), contentHash = "v2")), options), areaId = null))
			val packedOnce = session.model.value
			val bigTile = packedOnce.atlas.tiles.first { tile -> tile.source?.layerKey == "lyid:9" }
			assertNotNull(bigTile.placement, "the added layer was packed")
			// A page that shrinks to a power of two always leaves a hole, so the second page is made by
			// hand: the tile moved there through the repack edit (its coordinates re-derived with it).
			val movedPlacement = AtlasPlacement(1, 2f, 2f, 1f, 1f, 0f)
			val placements = packedOnce.atlas.tiles.associate { tile -> tile.id to (if (tile.id == bigTile.id) movedPlacement else tile.placement) }
			val withBig = session.commitArtworkReloaded(DocumentChange.ReloadArtwork(1, 0, 0, 0, 0), packedOnce.withAtlasRepack(packedOnce.atlas.pages + AtlasPage(256, 256), placements, packedOnce.atlas.composition))
			val bigPlacement = assertNotNull(withBig.atlas.tileById.getValue(bigTile.id).placement)
			assertEquals(2, withBig.atlas.pages.size, "onto a second page")
			assertEquals(1, bigPlacement.pageIndex)
			val bigDrawable = withBig.drawables.first { drawable -> drawable.atlasTileId == bigTile.id }
			val uvsBefore = assertNotNull(bigDrawable.mesh).uvs.copyOf()
			val uvWidth = (0 until uvsBefore.size / 2).let { vertices -> vertices.maxOf { vertex -> uvsBefore[vertex * 2] } - vertices.minOf { vertex -> uvsBefore[vertex * 2] } }
			assertEquals(34f / 256f, uvWidth, 1e-3f, "the coordinates span the birth quad (the tile plus its 2 px margin) on its 256 page")

			// The layer is deleted in the file: its tile, page, and coordinates must all stand.
			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(wide, corner)), contentHash = "v3")), options), areaId = null))
			val withoutBig = session.model.value
			assertEquals(withBig.atlas.pages, withoutBig.atlas.pages, "no page appeared, vanished, or resized")
			val keptTile = assertNotNull(withoutBig.atlas.tileById[bigTile.id], "the tile is kept, not replaced")
			assertEquals(bigPlacement, keptTile.placement, "and stays where it was")
			assertEquals(withBig.atlas.tiles.map { tile -> tile.id to tile.placement }, withoutBig.atlas.tiles.map { tile -> tile.id to tile.placement }, "every placement stands")
			assertContentEquals(uvsBefore, assertNotNull(withoutBig.drawables.first { drawable -> drawable.atlasTileId == bigTile.id }.mesh).uvs, "the coordinates stand")
			assertEquals(false, withoutBig.sources.single().layers.first { layer -> layer.key == "lyid:9" }.present, "the row is kept for review")
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== withoutBig.atlas) {
					yield()
				}
			}
			val published = sessionAtlasPages.binding.value.textures
			val derived = assertNotNull(deriveAtlasTextures(withoutBig, document.artRasters, premultipliedAlpha = false))
			assertEquals(2, published.atlases.size)
			for ((pageIndex, page) in published.atlases.withIndex()) {
				assertTrue(page.rgba.contentEquals(derived.atlases[pageIndex].rgba), "published page $pageIndex equals its derivation")
			}
			follower.cancel()
		}

	@Test
	fun aReloadLandsTheChangedLayersAsOneStepAndUndoShowsTheOldArt() =
		runBlocking {
			val load = buildArtDocument(InMemoryArt(listOf(layerA, layerB)), FileKind.Psd, "a.psd", "/art/a.psd", options)
			val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = document.artRasters,
					sessionAtlasPages = sessionAtlasPages,
					premultipliedAlpha = document.textures.premultipliedAlpha,
					scope = this,
					report = { report -> error("the reload must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val before = session.model.value
			val tileA = AtlasTileId("art-0/lyid:1")
			val tileB = AtlasTileId("art-0/lyid:2")
			val placementB = assertNotNull(before.atlas.tileById.getValue(tileB).placement)
			val drawableA = before.drawables.first { drawable -> drawable.atlasTileId == tileA }

			val request = ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(layerARepainted, layerB, layerC)), contentHash = "hash-v2")), options)
			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, request, areaId = null), "the reload lands")
			val reloaded = session.model.value
			val tileA1 = AtlasTileId("art-0/lyid:1~1")
			assertEquals(listOf(tileB, tileA1, AtlasTileId("art-0/lyid:3")), reloaded.atlas.tiles.map { tile -> tile.id }, "the changed tile is replaced, the new layer appended")
			assertEquals(placementB, reloaded.atlas.tileById.getValue(tileB).placement, "the unchanged tile did not move")
			assertNotNull(reloaded.atlas.tileById.getValue(tileA1).placement, "the replacement is placed")
			assertEquals(tileA, reloaded.atlas.tileById.getValue(tileA1).replaces)
			assertEquals(12, reloaded.atlas.tileById.getValue(tileA1).width)
			val carriedA = reloaded.drawables.first { drawable -> drawable.id == drawableA.id }
			assertEquals(tileA1, carriedA.atlasTileId, "the drawable moved onto the replacement")
			assertEquals(3, reloaded.drawables.size, "the new layer became a drawable")
			assertEquals(OrgChild.Drawable(DrawableId("ArtMesh3")), reloaded.rootChildren.first(), "placed first, where the file put it, so it draws in front")
			assertEquals(listOf("lyid:3", "lyid:1", "lyid:2"), reloaded.sources.single().layers.map { layer -> layer.key }, "the inventory reads in the file's order, the new top layer first")
			assertEquals("hash-v2", reloaded.sources.single().contentHash, "the record carries the hash the reload read")
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== reloaded.atlas) {
					yield()
				}
			}
			val published = sessionAtlasPages.binding.value.textures
			val derived = assertNotNull(deriveAtlasTextures(reloaded, document.artRasters, premultipliedAlpha = false), "the reloaded model derives")
			for ((pageIndex, page) in published.atlases.withIndex()) {
				assertTrue(page.rgba.contentEquals(derived.atlases[pageIndex].rgba), "published page $pageIndex equals its derivation")
			}
			assertTrue(published.atlases.any { page -> page.rgba.any { byte -> byte == 9.toByte() } }, "the pages carry the repainted pixels")

			// An adjustment re-lands the SAME step with wider re-born quads.  Before any undo, which
			// retires the record.
			val record = assertNotNull(session.adjustableOperation.value, "the reload registered on the strip")
			val quadBefore = assertNotNull(carriedA.mesh).positions.copyOf()
			val widened = record.parameters.map { parameter -> if (parameter.key == ImportParameterKeys.MARGIN && parameter is OperatorParameter.IntParameter) parameter.copy(value = parameter.value + 6) else parameter }
			session.adjustLastOperation(widened)
			withTimeout(120_000) {
				while (session.model.value.drawables.first { drawable -> drawable.id == drawableA.id }.mesh?.positions?.contentEquals(quadBefore) != false) {
					yield()
				}
			}
			val quadAfter = assertNotNull(session.model.value.drawables.first { drawable -> drawable.id == drawableA.id }.mesh).positions
			assertEquals(quadBefore[0] - 6f, quadAfter[0], 1e-3f, "the re-born quad's left edge moved out by the margin change")

			// One step: undo restores the whole model, and the resolver shows the pages as imported again.
			session.undo()
			assertSame(before, session.model.value, "undo restores the model as it was")
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== before.atlas) {
					yield()
				}
			}
			assertSame(document.textures, sessionAtlasPages.binding.value.textures, "the old pixels come back with the baseline pages")
			session.redo()
			assertEquals(3, session.model.value.drawables.size, "redo brings the reload back")

			// Nothing changed since: no step, the model untouched.
			val settled = session.model.value
			assertEquals(
				ReloadArtworkResult.NothingChanged,
				runReloadArtwork(host, ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(layerARepainted, layerB, layerC)), contentHash = "hash-v3")), options), areaId = null),
				"a save that changed bytes but no layer plans nothing",
			)
			assertSame(settled, session.model.value)
			assertEquals("hash-v2", session.model.value.sources.single().contentHash, "and the record keeps the hash of the reload that landed")

			// A relink to another layer of the read file pulls that layer's art; without the file it
			// changes the binding alone.
			val tileC = AtlasTileId("art-0/lyid:3")
			val refB = SourceLayerRef(sourceId, "lyid:2", true)
			assertTrue(runRelinkArtwork(host, RelinkArtworkRequest(tileC, refB, InMemoryArt(listOf(layerARepainted, layerB, layerC)), options), areaId = null))
			val relinked = session.model.value
			assertNull(relinked.atlas.tileById[tileC], "the relinked tile was replaced")
			val pulled = relinked.atlas.tiles.first { tile -> tile.replaces == tileC }
			assertEquals(refB, pulled.source)
			assertEquals(8, pulled.width, "with the target layer's art")
			val refA = SourceLayerRef(sourceId, "lyid:1", true)
			assertFalse(runRelinkArtwork(host, RelinkArtworkRequest(pulled.id, refA, art = null, options), areaId = null), "no file, no art")
			assertEquals(refA, session.model.value.atlas.tileById.getValue(pulled.id).source, "but the binding changed")
			follower.cancel()
		}

	/**
	 * A layer's eye toggle in the file follows into the drawable over it while that drawable still shows
	 * the state the file last had - a step of its own, with no tile replaced and nothing packed - and
	 * follows back when the layer is shown again.
	 */
	@Test
	fun aLayerEyeToggleFollowsIntoTheDrawableAndBack() =
		runBlocking {
			val load = buildArtDocument(InMemoryArt(listOf(layerA, layerB)), FileKind.Psd, "a.psd", "/art/a.psd", options)
			val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = document.artRasters,
					sessionAtlasPages = null,
					premultipliedAlpha = document.textures.premultipliedAlpha,
					scope = this,
					report = { report -> error("the reload must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val before = session.model.value
			val tileB = AtlasTileId("art-0/lyid:2")
			val drawableB = before.drawables.first { drawable -> drawable.atlasTileId == tileB }
			assertTrue(drawableB.isVisible)

			val layerBHidden = InMemoryLayer("lyid:2", "B", 1, LayerBounds(40, 40, 8, 8), solidRaster(8, 8, 2), visible = false)
			val hide = ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(layerA, layerBHidden)), contentHash = "hash-hidden")), options)
			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, hide, areaId = null), "an eye toggle alone is a step")
			val hidden = session.model.value
			assertFalse(hidden.drawables.first { drawable -> drawable.id == drawableB.id }.isVisible, "the untouched drawable followed the file")
			assertEquals(before.atlas.tiles.map { tile -> tile.id }, hidden.atlas.tiles.map { tile -> tile.id }, "no tile changed")
			assertEquals(false, hidden.sources.single().layers.first { layer -> layer.key == "lyid:2" }.visible, "the inventory records the new state")

			val show = ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(layerA, layerB)), contentHash = "hash-shown")), options)
			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, show, areaId = null))
			assertTrue(session.model.value.drawables.first { drawable -> drawable.id == drawableB.id }.isVisible, "shown again in the file, it follows back")
			session.undo()
			session.undo()
			assertSame(before, session.model.value, "two steps, both undone")
		}

	@Test
	fun aReloadAfterAnUndoReusesTheReplacementIdAndTheStoreServesTheNewPixels() =
		runBlocking {
			// The replacement id is minted past the ids the model holds, so reload, undo, reload mints
			// `~1` twice.  The raster store is document-lifetime and its cached read (the UV editor's pick
			// surface) may have answered `~1` after the first reload; it must answer the second's pixels now.
			val load = buildArtDocument(InMemoryArt(listOf(layerA, layerB)), FileKind.Psd, "a.psd", "/art/a.psd", options)
			val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = document.artRasters,
					sessionAtlasPages = sessionAtlasPages,
					premultipliedAlpha = document.textures.premultipliedAlpha,
					scope = this,
					report = { report -> error("the reload must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val before = session.model.value
			val tileA1 = AtlasTileId("art-0/lyid:1~1")

			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(layerARepainted, layerB)), "hash-v2")), options), areaId = null))
			assertTrue(session.model.value.atlas.tileById.containsKey(tileA1))
			val firstPixels = assertNotNull(document.artRasters.rasterFor(tileA1), "the cached read answers the first reload")
			assertEquals(9.toByte(), firstPixels.rgba[0])
			session.undo()
			assertSame(before, session.model.value)

			val repaintedAgain = InMemoryLayer("lyid:1", "A", 0, LayerBounds(10, 10, 10, 10), solidRaster(10, 10, 7))
			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(repaintedAgain, layerB)), "hash-v3")), options), areaId = null))
			assertTrue(session.model.value.atlas.tileById.containsKey(tileA1), "the replacement id is minted again")
			assertEquals(10, session.model.value.atlas.tileById.getValue(tileA1).width)
			val secondPixels = assertNotNull(document.artRasters.rasterFor(tileA1), "the cached read answers the second reload")
			assertEquals(7.toByte(), secondPixels.rgba[0], "with the second reload's pixels, not the cached first")
			assertEquals(10, secondPixels.width)
			assertSame(secondPixels, document.artRasters.decodeRaster(tileA1), "one instance from both reads")
			follower.cancel()
		}

	@Test
	fun tilesBoundToOneLostKeyRelinkTogetherAsOneStep() =
		runBlocking {
			// Two tiles under one binding, the shape a review row's Accept acts on: relinked in one request
			// they land as one step (a request per tile would race, each superseding the next), with the
			// file and without it.
			val load = buildArtDocument(InMemoryArt(listOf(layerA, layerB)), FileKind.Psd, "a.psd", "/art/a.psd", options)
			val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = document.artRasters,
					sessionAtlasPages = sessionAtlasPages,
					premultipliedAlpha = document.textures.premultipliedAlpha,
					scope = this,
					report = { report -> error("the relink must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val tileA = AtlasTileId("art-0/lyid:1")
			val tileB = AtlasTileId("art-0/lyid:2")
			val refC = SourceLayerRef(sourceId, "lyid:3", true)
			val before = session.model.value

			assertTrue(runRelinkArtwork(host, RelinkArtworkRequest(listOf(tileA, tileB), refC, InMemoryArt(listOf(layerA, layerB, layerC)), options), areaId = null))
			val relinked = session.model.value
			assertEquals(listOf(tileA, tileB), relinked.atlas.tiles.mapNotNull { tile -> tile.replaces }, "both tiles were replaced")
			assertTrue(relinked.atlas.tiles.all { tile -> tile.source == refC }, "both carry the new binding")
			assertTrue(relinked.atlas.tiles.all { tile -> tile.width == 6 }, "both took the target layer's art")
			session.undo()
			assertSame(before, session.model.value, "one step for both")
			assertFalse(session.canUndo.value)

			// Without the file only the bindings change, still as one step.
			assertFalse(runRelinkArtwork(host, RelinkArtworkRequest(listOf(tileA, tileB), refC, art = null, options), areaId = null))
			assertTrue(session.model.value.atlas.tiles.all { tile -> tile.source == refC })
			session.undo()
			assertSame(before, session.model.value)
			assertFalse(session.canUndo.value)
			follower.cancel()
		}

	/**
	 * A layer deleted and re-created under a new key with its pixels and place - Photoshop's duplicate,
	 * delete the original, rename - reloads as one step that rebinds the rigged tile instead of minting
	 * a fresh drawable beside it, and the strip leads with the Match Threshold row that decided it.
	 */
	@Test
	fun aReCreatedLayerReloadsAsARebindingWithTheThresholdRowFirst() =
		runBlocking {
			val load = buildArtDocument(InMemoryArt(listOf(layerA, layerB)), FileKind.Psd, "a.psd", "/art/a.psd", options)
			val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = document.artRasters,
					sessionAtlasPages = sessionAtlasPages,
					premultipliedAlpha = document.textures.premultipliedAlpha,
					scope = this,
					report = { report -> error("the reload must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val tileA = AtlasTileId("art-0/lyid:1")
			val recreatedA = InMemoryLayer("lyid:3", "A", 0, LayerBounds(10, 10, 8, 8), layerA.raster)
			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(recreatedA, layerB)), contentHash = "v2")), options), areaId = null))
			val reloaded = session.model.value
			assertEquals(2, reloaded.drawables.size, "nothing was minted")
			val rebound = reloaded.atlas.tiles.first { tile -> tile.replaces == tileA }
			assertEquals("lyid:3", rebound.source?.layerKey, "the rigged tile follows the re-created layer")
			assertNotNull(rebound.placement, "and is packed")
			assertEquals(listOf("lyid:3", "lyid:2"), reloaded.sources.single().layers.map { layer -> layer.key }, "no lost row")
			assertTrue(reloaded.sources.single().layers.all { layer -> layer.present })
			val notice = assertNotNull(session.notice.value)
			assertEquals("notice.reload.done", notice.messageKey)
			assertEquals(listOf("1", "0", "1", "0"), notice.arguments, "one updated, none added, one matched, none missing")
			val record = assertNotNull(session.adjustableOperation.value, "the reload registered on the strip")
			assertEquals(MatchParameterKeys.THRESHOLD, record.parameters.first().key, "the strip leads with the bar")
			assertEquals("change.document.reloadArtwork", session.historyView.value.let { view -> view.steps[view.cursor].labelKey })
			follower.cancel()
		}

	/**
	 * A re-created layer repainted at the same place scores under certain: raising the reload's bar to
	 * 100% re-lands the same step with the layer minted and the binding left for review, and lowering
	 * it rebinds again - the strip and the document always agree.
	 */
	@Test
	fun raisingTheReloadThresholdReLandsTheStepWithTheLayerMintedInstead() =
		runBlocking {
			val load = buildArtDocument(InMemoryArt(listOf(layerA, layerB)), FileKind.Psd, "a.psd", "/art/a.psd", options)
			val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = document.artRasters,
					sessionAtlasPages = sessionAtlasPages,
					premultipliedAlpha = document.textures.premultipliedAlpha,
					scope = this,
					report = { report -> error("the reload must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val before = session.model.value
			val tileA = AtlasTileId("art-0/lyid:1")
			val repaintedA = InMemoryLayer("lyid:3", "A", 0, LayerBounds(10, 10, 8, 8), solidRaster(8, 8, 3))
			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(repaintedA, layerB)), contentHash = "v2")), options), areaId = null))
			assertEquals(2, session.model.value.drawables.size, "rebound at the default bar")
			assertTrue(session.model.value.atlas.tiles.any { tile -> tile.replaces == tileA })
			val record = assertNotNull(session.adjustableOperation.value)

			val raised = record.parameters.map { parameter -> if (parameter.key == MatchParameterKeys.THRESHOLD && parameter is OperatorParameter.FloatParameter) parameter.copy(value = 100f) else parameter }
			session.adjustLastOperation(raised)
			withTimeout(120_000) {
				while (session.model.value.drawables.size != 3) {
					yield()
				}
			}
			val minted = session.model.value
			assertNotNull(minted.atlas.tileById[tileA], "the old tile stands, unreplaced")
			assertNotNull(minted.atlas.tileById[AtlasTileId("art-0/lyid:3")], "the layer was minted")
			assertEquals(false, minted.sources.single().layers.first { layer -> layer.key == "lyid:1" }.present, "the binding waits for review")
			assertEquals("change.document.reloadArtwork", session.historyView.value.let { view -> view.steps[view.cursor].labelKey })

			val lowered = record.parameters.map { parameter -> if (parameter.key == MatchParameterKeys.THRESHOLD && parameter is OperatorParameter.FloatParameter) parameter.copy(value = 70f) else parameter }
			session.adjustLastOperation(lowered)
			withTimeout(120_000) {
				while (session.model.value.drawables.size != 2) {
					yield()
				}
			}
			assertTrue(session.model.value.atlas.tiles.any { tile -> tile.replaces == tileA }, "rebound again")
			session.undo()
			assertSame(before, session.model.value, "one step throughout")
			follower.cancel()
		}
}