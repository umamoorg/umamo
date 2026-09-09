package org.umamo.ui.document

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.format.FileKind
import org.umamo.format.art.LayerBounds
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.render.deriveAtlasTextures
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.model.AtlasRepackHost
import org.umamo.ui.model.ImportParameterKeys
import org.umamo.ui.model.RelinkArtworkRequest
import org.umamo.ui.model.ReloadArtworkRequest
import org.umamo.ui.model.ReloadEntry
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.runRelinkArtwork
import org.umamo.ui.model.runReloadArtwork
import kotlin.test.Test
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
 * pure snapshot; an adjustment re-lands the step; a reload with nothing changed pushes nothing; and a
 * relink pulls the target layer's art when its file is read and changes the binding alone when not.
 */
class ReloadArtworkFlowTest {
	private val options = SourceArtImportOptions(alphaThreshold = 1, birthMeshMargin = 2)
	private val sourceId = ArtSourceId("art-0")

	private val layerA = InMemoryLayer("lyid:1", "A", 0, LayerBounds(10, 10, 8, 8), solidRaster(8, 8, 1))
	private val layerB = InMemoryLayer("lyid:2", "B", 1, LayerBounds(40, 40, 8, 8), solidRaster(8, 8, 2))
	private val layerARepainted = InMemoryLayer("lyid:1", "A", 0, LayerBounds(10, 10, 12, 12), solidRaster(12, 12, 9))
	private val layerC = InMemoryLayer("lyid:3", "C", 2, LayerBounds(5, 50, 6, 6), solidRaster(6, 6, 3))

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

			val request = ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(layerARepainted, layerB, layerC)))), options)
			assertTrue(runReloadArtwork(host, request, areaId = null), "the reload lands")
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
			assertEquals(listOf("lyid:1", "lyid:2", "lyid:3"), reloaded.sources.single().layers.map { layer -> layer.key })
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
			assertFalse(runReloadArtwork(host, ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(layerARepainted, layerB, layerC)))), options), areaId = null))
			assertSame(settled, session.model.value)

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
}