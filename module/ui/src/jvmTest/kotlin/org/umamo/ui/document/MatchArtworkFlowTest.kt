package org.umamo.ui.document

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.format.FileKind
import org.umamo.format.art.LayerBounds
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.ui.model.AtlasRepackHost
import org.umamo.ui.model.MatchArtworkRequest
import org.umamo.ui.model.MatchParameterKeys
import org.umamo.ui.model.ReloadEntry
import org.umamo.ui.model.ReplaceArtworkRequest
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.SourceSuggestions
import org.umamo.ui.model.runMatchArtwork
import org.umamo.ui.model.runReplaceArtwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Match Automatically and Replace Artwork driven the way the shell drives them, over a document built
 * from in-memory art: a file whose layers came back under new keys, one recognizable by its pixels
 * and one only weakly, matched at the default bar as one step with the weak one left as a suggestion;
 * the strip's threshold re-landing the step with both; undo restoring; a same-format twin replacing
 * the record by key; a cross-format twin flagging every binding with suggestions that Match then
 * resolves.
 */
class MatchArtworkFlowTest {
	/**
	 * The label key of the history step the session stands on.
	 *
	 * @param EditorSession session The session.
	 * @return String? The current step's label key, or null on the seed.
	 */
	private fun currentStepLabel(session: EditorSession): String? = session.historyView.value.let { view -> view.steps[view.cursor].labelKey }

	private val options = SourceArtImportOptions(alphaThreshold = 1, birthMeshMargin = 2)
	private val sourceId = ArtSourceId("art-0")

	private val hair = InMemoryLayer("lyid:1", "Hair Front", 0, LayerBounds(10, 10, 8, 8), solidRaster(8, 8, 1))
	private val eye = InMemoryLayer("lyid:2", "Eye L", 1, LayerBounds(40, 40, 8, 8), solidRaster(8, 8, 2))

	/** Hair under a new key with the same pixels; the eye under a new key, renamed, moved, and repainted. */
	private val hairRenamed = InMemoryLayer("lyid:5", "Hair Front 2", 0, LayerBounds(10, 10, 8, 8), hair.raster)
	private val eyeMoved = InMemoryLayer("lyid:6", "Eye", 1, LayerBounds(100, 100, 8, 8), solidRaster(8, 8, 0xF0.toByte()))

	@Test
	fun matchAppliesTheConfidentRenameAndTheThresholdRowTakesTheRest() =
		runBlocking {
			val load = buildArtDocument(InMemoryArt(listOf(hair, eye)), FileKind.Psd, "a.psd", "/art/a.psd", options)
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
					report = { report -> error("the match must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val before = session.model.value
			val tileHair = AtlasTileId("art-0/lyid:1")
			val tileEye = AtlasTileId("art-0/lyid:2")
			var published: SourceSuggestions = emptyMap()

			val renamedArt = InMemoryArt(listOf(hairRenamed, eyeMoved))
			val request = MatchArtworkRequest(listOf(ReloadEntry(sourceId, renamedArt, contentHash = "hash-v2")), threshold = 0.7f, options)
			assertTrue(runMatchArtwork(host, request, areaId = null) { suggestions -> published = suggestions }, "the confident rename lands")
			val matched = session.model.value
			val hairTile = matched.atlas.tiles.first { tile -> tile.replaces == tileHair }
			assertEquals("lyid:5", hairTile.source?.layerKey, "the hair tile moved to the layer with its pixels")
			assertEquals("lyid:2", matched.atlas.tileById.getValue(tileEye).source?.layerKey, "the eye binding is left for a person")
			assertEquals(listOf("lyid:5", "lyid:6", "lyid:2"), matched.sources.single().layers.map { layer -> layer.key })
			assertEquals(listOf(true, true, false), matched.sources.single().layers.map { layer -> layer.present })
			val eyeSuggestion = assertNotNull(published[sourceId to "lyid:2"], "the weak match is published as a suggestion")
			assertEquals("lyid:6", eyeSuggestion.key)
			assertTrue(eyeSuggestion.score < 0.7f, "below the bar: ${eyeSuggestion.score}")
			assertTrue(eyeSuggestion.score >= 0.5f, "but not hopeless: ${eyeSuggestion.score}")
			assertNotNull(eyeSuggestion.signals.pixels, "the pixels were compared")
			assertEquals("change.document.matchArtwork", currentStepLabel(session), "one step, labeled as a match")

			// The strip's threshold row: lowering the bar re-lands the same step with both.
			val record = assertNotNull(session.adjustableOperation.value, "the match registered on the strip")
			val lowered = record.parameters.map { parameter -> if (parameter.key == MatchParameterKeys.THRESHOLD && parameter is OperatorParameter.FloatParameter) parameter.copy(value = 50f) else parameter }
			session.adjustLastOperation(lowered)
			withTimeout(120_000) {
				while (session.model.value.atlas.tiles.any { tile -> tile.source?.layerKey == "lyid:2" }) {
					yield()
				}
			}
			val both = session.model.value
			assertEquals(setOf("lyid:5", "lyid:6"), both.atlas.tiles.mapNotNull { tile -> tile.source?.layerKey }.toSet(), "both bindings moved")
			assertEquals(listOf("lyid:5", "lyid:6"), both.sources.single().layers.map { layer -> layer.key }, "no lost row remains")
			assertTrue(published.isEmpty(), "nothing is left to suggest")
			assertEquals(2, both.drawables.size, "no drawable was added or lost")

			// One step: undo restores the document as imported.
			session.undo()
			assertSame(before, session.model.value)
			session.redo()
			assertEquals(setOf("lyid:5", "lyid:6"), session.model.value.atlas.tiles.mapNotNull { tile -> tile.source?.layerKey }.toSet())
			follower.cancel()
		}

	@Test
	fun raisingTheThresholdPastTheMatchAmendsTheStepBackToTheBase() =
		runBlocking {
			// A rename at the same place with repainted pixels scores just under certain, so the strip's
			// threshold can be raised past it.  Doing so must take the rebinding with it - the step amends
			// back to the base and the proposal is published - so the strip and the document agree; a
			// certain (hash-equal) match scores exactly 1 and survives any bar, which is right.
			val repainted = InMemoryLayer("lyid:5", "Hair Front", 0, LayerBounds(10, 10, 8, 8), solidRaster(8, 8, 3))
			val load = buildArtDocument(InMemoryArt(listOf(hair)), FileKind.Psd, "a.psd", "/art/a.psd", options)
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
					report = { report -> error("the match must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val before = session.model.value
			var published: SourceSuggestions = emptyMap()

			val request = MatchArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(repainted)), contentHash = "hash-v2")), threshold = 0.7f, options)
			assertTrue(runMatchArtwork(host, request, areaId = null) { suggestions -> published = suggestions })
			assertEquals(listOf("lyid:5"), session.model.value.atlas.tiles.mapNotNull { tile -> tile.source?.layerKey })
			assertTrue(published.isEmpty())
			val record = assertNotNull(session.adjustableOperation.value)

			val raised = record.parameters.map { parameter -> if (parameter.key == MatchParameterKeys.THRESHOLD && parameter is OperatorParameter.FloatParameter) parameter.copy(value = 100f) else parameter }
			session.adjustLastOperation(raised)
			withTimeout(120_000) {
				while (session.model.value !== before) {
					yield()
				}
			}
			assertEquals(listOf("lyid:1"), session.model.value.atlas.tiles.mapNotNull { tile -> tile.source?.layerKey }, "the rebinding went with the bar")
			val proposal = assertNotNull(published[sourceId to "lyid:1"], "the proposal is published for the review row")
			assertEquals("lyid:5", proposal.key)
			assertTrue(proposal.score < 1f && proposal.score >= 0.7f, "near certain, not certain: ${proposal.score}")
			assertTrue(session.canUndo.value, "the step still stands, holding the base")
			assertEquals("change.document.matchArtwork", currentStepLabel(session))

			// And back down: the same record re-lands the rebinding.
			val lowered = record.parameters.map { parameter -> if (parameter.key == MatchParameterKeys.THRESHOLD && parameter is OperatorParameter.FloatParameter) parameter.copy(value = 70f) else parameter }
			session.adjustLastOperation(lowered)
			withTimeout(120_000) {
				while (session.model.value.atlas.tiles.any { tile -> tile.source?.layerKey == "lyid:1" }) {
					yield()
				}
			}
			assertTrue(published.isEmpty())
			session.undo()
			assertSame(before, session.model.value)
			follower.cancel()
		}

	@Test
	fun twoLostLayersPreferringOneCandidateSettleOnTheMoreConfidentAndKeepTheOther() =
		runBlocking {
			// Two layers with the same pixels come back as ONE layer under a new key: both hash-match it.
			// One takes it; the other must stay a suggestion, counted as remaining, not vanish because its
			// candidate was spoken for.
			val front = InMemoryLayer("lyid:1", "Hair Front", 0, LayerBounds(10, 10, 8, 8), solidRaster(8, 8, 1))
			val back = InMemoryLayer("lyid:2", "Hair Back", 1, LayerBounds(10, 30, 8, 8), front.raster)
			val merged = InMemoryLayer("lyid:5", "Hair", 0, LayerBounds(10, 10, 8, 8), front.raster)
			val load = buildArtDocument(InMemoryArt(listOf(front, back)), FileKind.Psd, "a.psd", "/art/a.psd", options)
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
					report = { report -> error("the match must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			var published: SourceSuggestions = emptyMap()

			val request = MatchArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(merged)), contentHash = "hash-v2")), threshold = 0.7f, options)
			assertTrue(runMatchArtwork(host, request, areaId = null) { suggestions -> published = suggestions })
			val matched = session.model.value
			assertEquals(setOf("lyid:5", "lyid:2"), matched.atlas.tiles.mapNotNull { tile -> tile.source?.layerKey }.toSet(), "one moved, one stayed")
			val notice = assertNotNull(session.notice.value)
			assertEquals("notice.match.done", notice.messageKey)
			assertEquals(listOf("1", "1"), notice.arguments, "one matched, one remaining")
			val leftover = assertNotNull(published[sourceId to "lyid:2"], "the loser keeps its proposal")
			assertEquals("lyid:5", leftover.key)
			assertTrue(leftover.signals.hashEqual)
			follower.cancel()
		}

	@Test
	fun replaceReloadsBykeyForATwinAndFlagsEverythingForACrossFormatOne() =
		runBlocking {
			val load = buildArtDocument(InMemoryArt(listOf(hair, eye)), FileKind.Psd, "a.psd", "/art/a.psd", options)
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
					report = { report -> error("the replace must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			var published: SourceSuggestions = emptyMap()

			// A same-format twin: the keys resolve, the record is repointed, no tile changes.
			val twin = ArtSourceDescriptor("b.psd", "/art/b.psd", "psd", "hash-b")
			val twinRequest = ReplaceArtworkRequest(sourceId, InMemoryArt(listOf(hair, eye)), twin, contentHash = "hash-b", options)
			assertTrue(runReplaceArtwork(host, twinRequest, areaId = null) { suggestions -> published = suggestions })
			val repointed = session.model.value
			assertEquals("b.psd", repointed.sources.single().name)
			assertEquals("/art/b.psd", repointed.sources.single().path)
			assertEquals("hash-b", repointed.sources.single().contentHash)
			assertEquals(listOf(AtlasTileId("art-0/lyid:1"), AtlasTileId("art-0/lyid:2")), repointed.atlas.tiles.map { tile -> tile.id }, "nothing replaced")
			assertEquals("change.document.replaceArtwork", currentStepLabel(session))
			assertTrue(published.isEmpty())

			// A cross-format twin: the same art under other keys.  Every binding is flagged, none reloaded,
			// nothing added, and the pixel-scored suggestions arrive with the step.
			val clipHair = InMemoryLayer("clip:a", "Hair Front", 0, LayerBounds(10, 10, 8, 8), hair.raster)
			val clipEye = InMemoryLayer("clip:b", "Eye L", 1, LayerBounds(40, 40, 8, 8), eye.raster)
			val clip = ArtSourceDescriptor("a.clip", "/art/a.clip", "clip", "hash-c")
			val clipArt = InMemoryArt(listOf(clipHair, clipEye))
			assertTrue(runReplaceArtwork(host, ReplaceArtworkRequest(sourceId, clipArt, clip, contentHash = "hash-c", options), areaId = null) { suggestions -> published = suggestions })
			val replaced = session.model.value
			assertEquals("clip", replaced.sources.single().format)
			assertEquals(listOf("clip:a", "clip:b", "lyid:1", "lyid:2"), replaced.sources.single().layers.map { layer -> layer.key })
			assertEquals(listOf(true, true, false, false), replaced.sources.single().layers.map { layer -> layer.present })
			assertEquals(2, replaced.atlas.tiles.size, "the new file's layers were not minted as tiles")
			assertEquals("change.document.replaceArtwork", currentStepLabel(session))
			assertEquals(setOf(sourceId to "lyid:1", sourceId to "lyid:2"), published.keys)
			assertEquals("clip:a", published.getValue(sourceId to "lyid:1").key)
			assertEquals("clip:b", published.getValue(sourceId to "lyid:2").key)
			assertEquals(1f, published.getValue(sourceId to "lyid:1").score, "the same pixels are a certain match")

			// Match Automatically then resolves every binding, and the lost rows leave the inventory.
			val matchRequest = MatchArtworkRequest(listOf(ReloadEntry(sourceId, clipArt, contentHash = "hash-c")), threshold = 0.7f, options)
			assertTrue(runMatchArtwork(host, matchRequest, areaId = null) { suggestions -> published = suggestions })
			val resolved = session.model.value
			assertEquals(setOf("clip:a", "clip:b"), resolved.atlas.tiles.mapNotNull { tile -> tile.source?.layerKey }.toSet())
			assertEquals(listOf("clip:a", "clip:b"), resolved.sources.single().layers.map { layer -> layer.key })
			assertEquals("change.document.matchArtwork", currentStepLabel(session))
			assertTrue(published.isEmpty())

			// Nothing left to match: no step, the model untouched.
			assertFalse(runMatchArtwork(host, matchRequest, areaId = null) { suggestions -> published = suggestions })
			assertSame(resolved, session.model.value)
			follower.cancel()
		}
}