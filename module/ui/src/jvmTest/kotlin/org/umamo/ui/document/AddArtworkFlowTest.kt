package org.umamo.ui.document

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.seed.ParameterTemplate
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.render.deriveAtlasTextures
import org.umamo.runtime.model.ParameterNode
import org.umamo.ui.model.AddArtworkRequest
import org.umamo.ui.model.AtlasRepackHost
import org.umamo.ui.model.ImportParameterKeys
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.runAddArtwork
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Add Artwork into an open document, driven the way the shell drives it: a second PSD read through
 * [readArtwork], appended and packed by [runAddArtwork] over a live session with its page resolver
 * following, then undone, then adjusted through the operation record.
 *
 * What only a real pair of files proves: the added art packs into the gaps without moving a single
 * existing placement, the pages the session publishes equal their derivation, the step is one undo,
 * and an adjustment of the margin re-lands the same step with wider quads.  Gated on `psd.sample`
 * with the sibling `EricaVisibilityTest.psd` as the second file; self-skips without either.
 */
class AddArtworkFlowTest {
	private val sample: File? = System.getProperty("psd.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aSecondPsdJoinsTheOpenDocumentBesideItsArt() =
		runBlocking {
			val first = sample
			val second = first?.let { file -> File(file.parentFile, "EricaVisibilityTest.psd") }?.takeIf { it.isFile }
			if (first == null || second == null) {
				println("psd.sample (and its EricaVisibilityTest.psd sibling) not present; skipping the add-artwork flow gate")
				return@runBlocking
			}
			val load = loadDocument(first.readBytes(), first.name, first.path)
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
					report = { report -> error("the add must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val read = assertNotNull(readArtwork(second.readBytes(), second.name), "the second file reads as artwork")
			val request = AddArtworkRequest(read.art, ArtSourceDescriptor(second.name, second.path, read.kind.extension), SourceArtImportOptions())
			val before = session.model.value
			val placementsBefore = before.atlas.tiles.associate { tile -> tile.id to tile.placement }

			assertTrue(runAddArtwork(host, request, areaId = null), "the artwork is added")
			val grown = session.model.value
			assertEquals(2, grown.sources.size, "the document lists both files")
			assertEquals(before.drawables.size * 2, grown.drawables.size, "the twin PSD adds one drawable per layer")
			for ((tileId, placement) in placementsBefore) {
				assertEquals(placement, grown.atlas.tileById.getValue(tileId).placement, "existing tile '${tileId.raw}' did not move")
			}
			val addedTiles = grown.atlas.tiles.filter { tile -> tile.id !in placementsBefore }
			assertTrue(addedTiles.isNotEmpty() && addedTiles.all { tile -> tile.placement != null }, "every added tile is placed")
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== grown.atlas) {
					yield()
				}
			}
			val published = sessionAtlasPages.binding.value.textures
			val derived = assertNotNull(deriveAtlasTextures(grown, document.artRasters, premultipliedAlpha = false), "the grown model derives")
			assertEquals(derived.atlases.size, published.atlases.size, "page count")
			for ((pageIndex, page) in published.atlases.withIndex()) {
				assertTrue(page.rgba.contentEquals(derived.atlases[pageIndex].rgba), "published page $pageIndex equals its derivation")
			}

			// An adjustment re-lands the SAME step: wider birth quads, same source.  It has to come before
			// any undo, which retires the record by design.
			val record = assertNotNull(session.adjustableOperation.value, "the add registered on the strip")
			val addedDrawable = grown.drawables.first { drawable -> drawable.atlasTileId in addedTiles.map { tile -> tile.id }.toSet() }
			val quadBefore = assertNotNull(addedDrawable.mesh).positions.copyOf()
			val widened = record.parameters.map { parameter -> if (parameter.key == ImportParameterKeys.MARGIN && parameter is OperatorParameter.IntParameter) parameter.copy(value = parameter.value + 6) else parameter }
			session.adjustLastOperation(widened)
			withTimeout(120_000) {
				while (session.model.value.drawables.first { drawable -> drawable.id == addedDrawable.id }.mesh?.positions?.contentEquals(quadBefore) != false) {
					yield()
				}
			}
			val quadAfter = assertNotNull(session.model.value.drawables.first { drawable -> drawable.id == addedDrawable.id }.mesh).positions
			assertEquals(quadBefore[0] - 6f, quadAfter[0], 1e-3f, "the quad's left edge moved out by the margin change")
			assertEquals(2, session.model.value.sources.size, "the adjusted step still holds one added file")

			// The whole add is one step: undo removes the file, its tiles, and its drawables together.
			session.undo()
			assertEquals(1, session.model.value.sources.size, "undo removes the added file")
			assertEquals(before.drawables.size, session.model.value.drawables.size)
			session.redo()
			assertEquals(2, session.model.value.sources.size, "redo brings it back")
			assertTrue(assertNotNull(session.model.value.drawables.first { drawable -> drawable.id == addedDrawable.id }.mesh).positions.contentEquals(quadAfter), "and the adjusted quad with it")
			follower.cancel()
			println("add-artwork gate: ${addedTiles.size} tiles added onto ${grown.atlas.pages.size} page(s)")
		}

	/**
	 * The first artwork imported into a NEW document gives the rig its frame and its axes, and a second
	 * file leaves both alone.
	 *
	 * This is the path every rig now starts on - a new document opens empty and artwork is imported into
	 * it - so what the old document-creating import used to seed has to arrive here instead: the file's
	 * canvas over the placeholder one, the world origin at its center, the parameter template, and the
	 * flat parameter tree the CMO3 export reads.
	 */
	@Test
	fun theFirstImportIntoANewDocumentSeedsItsCanvasAndParameters() =
		runBlocking {
			val first = sample
			val second = first?.let { file -> File(file.parentFile, "EricaVisibilityTest.psd") }?.takeIf { it.isFile }
			if (first == null || second == null) {
				println("psd.sample (and its EricaVisibilityTest.psd sibling) not present; skipping the new-document import gate")
				return@runBlocking
			}
			val document = newBlankDocument()
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
					report = { report -> error("the import must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val options = artworkImportOptions(ParameterTemplate.Humanoid)
			val read = assertNotNull(readArtwork(first.readBytes(), first.name), "the file reads as artwork")

			assertTrue(
				runAddArtwork(host, AddArtworkRequest(read.art, ArtSourceDescriptor(first.name, first.path, read.kind.extension), options), areaId = null),
				"the artwork is imported",
			)

			val seeded = session.model.value
			assertTrue(seeded.drawables.isNotEmpty(), "the layers landed as drawables")
			assertEquals(read.art.widthPx.toFloat(), seeded.canvasWidth, "the canvas is the file's")
			assertEquals(read.art.heightPx.toFloat(), seeded.canvasHeight, "the canvas is the file's")
			assertEquals(seeded.canvasWidth / 2f, seeded.worldOriginX, "the world origin is the canvas center")
			assertEquals(-(seeded.canvasHeight / 2f), seeded.worldOriginY, "the world origin is the canvas center")
			assertEquals(
				ParameterTemplate.Humanoid.parameters.map { parameter -> parameter.id },
				seeded.parameters.map { parameter -> parameter.id },
				"the template seeded the axes",
			)
			assertEquals(
				seeded.parameters.map { parameter -> parameter.id },
				seeded.parameterTree.map { node -> assertIs<ParameterNode.Param>(node).id },
				"and the tree is materialized flat, which the CMO3 export reads",
			)

			// A second file is placed within the frame the first one set: it redefines neither the canvas
			// nor the axes, template in hand or not.
			val secondRead = assertNotNull(readArtwork(second.readBytes(), second.name), "the second file reads as artwork")
			assertTrue(
				runAddArtwork(
					host,
					AddArtworkRequest(secondRead.art, ArtSourceDescriptor(second.name, second.path, secondRead.kind.extension), options),
					areaId = null,
				),
				"the second artwork is imported",
			)

			val grown = session.model.value
			assertEquals(seeded.canvasWidth, grown.canvasWidth, "the canvas is unchanged")
			assertEquals(seeded.canvasHeight, grown.canvasHeight, "the canvas is unchanged")
			assertEquals(seeded.worldOriginX, grown.worldOriginX, "the world origin is unchanged")
			assertEquals(
				seeded.parameters.map { parameter -> parameter.id },
				grown.parameters.map { parameter -> parameter.id },
				"the axes are neither replaced nor duplicated",
			)
			assertEquals(2, grown.sources.size, "and both files are listed")
			follower.cancel()
			println("new-document import gate: ${grown.drawables.size} drawables over ${grown.atlas.pages.size} page(s)")
		}
}