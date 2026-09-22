package org.umamo.ui.document

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.format.art.LayerBounds
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.ArtworkAnchor
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.interop.art.placedFor
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.ui.model.AddArtworkRequest
import org.umamo.ui.model.AtlasRepackHost
import org.umamo.ui.model.ImportParameterKeys
import org.umamo.ui.model.ReloadArtworkRequest
import org.umamo.ui.model.ReloadArtworkResult
import org.umamo.ui.model.ReloadEntry
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.runAddArtwork
import org.umamo.ui.model.runReloadArtwork
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A later artwork file placed on the rig's canvas, driven the way the shell drives it over in-memory
 * art: the first file sets the canvas and carries no placement rows; a smaller second file lands
 * centered with its record keeping the offset; the strip's Align and Offset rows re-land the step;
 * a reload of the placed file finds nothing changed and re-borns a repainted layer at the placed
 * position; and undo restores the model.
 */
class PlacedArtworkFlowTest {
	private val options = SourceArtImportOptions(alphaThreshold = 1, birthMeshMargin = 2)

	/** The first file: the whole 64 x 64 canvas, one layer at (10, 10). */
	private val hostLayer = InMemoryLayer("lyid:1", "Host", 0, LayerBounds(10, 10, 8, 8), solidRaster(8, 8, 1))

	/** The second file: a 16 x 16 canvas with one 4 x 4 layer at (2, 2) in its own frame. */
	private val patchLayer = InMemoryLayer("lyid:9", "Patch", 0, LayerBounds(2, 2, 4, 4), solidRaster(4, 4, 5))
	private val patchArt = InMemoryArt(listOf(patchLayer), widthPx = 16, heightPx = 16)

	@Test
	fun aSecondFileLandsCenteredAndStaysPlacedThroughAdjustmentsAndReloads() =
		runBlocking {
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

			// The first file defines the canvas: no placement, and no Align / Offset rows on its strip.
			assertTrue(runAddArtwork(host, AddArtworkRequest(InMemoryArt(listOf(hostLayer)), ArtSourceDescriptor("host.psd", "/art/host.psd", "psd"), options), areaId = null))
			val framed = session.model.value
			assertEquals(64f to 64f, framed.canvasWidth to framed.canvasHeight)
			assertEquals(0 to 0, framed.sources.single().let { source -> source.offsetX to source.offsetY })
			val firstRecord = assertNotNull(session.adjustableOperation.value)
			assertTrue(firstRecord.parameters.none { parameter -> parameter.key == ImportParameterKeys.ALIGN }, "a first artwork has nothing to align")
			assertContentEquals(floatArrayOf(8f, 8f, 20f, 8f, 20f, 20f, 8f, 20f), assertNotNull(framed.drawables.single().mesh).positions)

			// The second file is centered: (64 - 16) / 2 = 24 along both axes.
			assertTrue(runAddArtwork(host, AddArtworkRequest(patchArt, ArtSourceDescriptor("patch.png", "/art/patch.png", "png"), options), areaId = null))
			val placed = session.model.value
			val patchSource = placed.sources.first { source -> source.name == "patch.png" }
			assertEquals(ArtSourceId("art-1"), patchSource.id)
			assertEquals(24 to 24, patchSource.offsetX to patchSource.offsetY, "the record keeps the centered offset")
			assertEquals(26 to 26, patchSource.layers.single().let { row -> row.left to row.top }, "the inventory row is in the document frame")
			val patchTile = AtlasTileId("art-1/lyid:9")
			val patchDrawable = placed.drawables.first { drawable -> drawable.atlasTileId == patchTile }
			assertContentEquals(floatArrayOf(24f, 24f, 32f, 24f, 32f, 32f, 24f, 32f), assertNotNull(patchDrawable.mesh).positions, "the birth quad sits at the placed position")
			assertEquals(64f to 64f, placed.canvasWidth to placed.canvasHeight, "the canvas is the first file's still")

			// Align to the top-left with a 3 px nudge along x and 5 px UP along Z re-lands the SAME step at
			// canvas (3, -5): the Offset Z row is world Z (up), the canvas offset it becomes is y (down).
			val record = assertNotNull(session.adjustableOperation.value, "the add registered on the strip")
			assertEquals(listOf(ImportParameterKeys.ALIGN, ImportParameterKeys.OFFSET_X, ImportParameterKeys.OFFSET_Z, ImportParameterKeys.ALPHA_THRESHOLD, ImportParameterKeys.MARGIN), record.parameters.map { parameter -> parameter.key })
			val adjusted =
				record.parameters.map { parameter ->
					when {
						parameter.key == ImportParameterKeys.ALIGN && parameter is OperatorParameter.ChoiceParameter -> parameter.copy(value = ArtworkAnchor.TopLeft.key)
						parameter.key == ImportParameterKeys.OFFSET_X && parameter is OperatorParameter.IntParameter -> parameter.copy(value = 3)
						parameter.key == ImportParameterKeys.OFFSET_Z && parameter is OperatorParameter.IntParameter -> parameter.copy(value = 5)
						else -> parameter
					}
				}
			session.adjustLastOperation(adjusted)
			withTimeout(120_000) {
				while (session.model.value.sources.first { source -> source.name == "patch.png" }.offsetX != 3) {
					yield()
				}
			}
			val realigned = session.model.value
			val realignedSource = realigned.sources.first { source -> source.name == "patch.png" }
			assertEquals(3 to -5, realignedSource.offsetX to realignedSource.offsetY, "the anchor's placement plus the nudge, Z negated into canvas y")
			assertContentEquals(floatArrayOf(3f, -5f, 11f, -5f, 11f, 3f, 3f, 3f), assertNotNull(realigned.drawables.first { drawable -> drawable.atlasTileId == patchTile }.mesh).positions)
			val shownRows = assertNotNull(session.adjustableOperation.value).parameters
			assertEquals(5, (shownRows.first { parameter -> parameter.key == ImportParameterKeys.OFFSET_Z } as OperatorParameter.IntParameter).value, "the row still reads as the Z the rigger typed")
			assertEquals(placed.drawables.size, realigned.drawables.size, "re-landed, not added twice")
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== realigned.atlas) {
					yield()
				}
			}

			// The file read again and placed by its record: nothing changed, nothing pushed.
			val reread = ReloadArtworkRequest(listOf(ReloadEntry(realignedSource.id, patchArt.placedFor(realignedSource), contentHash = "same")), options)
			assertEquals(ReloadArtworkResult.NothingChanged, runReloadArtwork(host, reread, areaId = null), "a placed read matches the placed inventory")
			assertSame(realigned, session.model.value)

			// Repainted in its own frame, it re-borns at the PLACED position and the record keeps the offset.
			val repainted = InMemoryArt(listOf(InMemoryLayer("lyid:9", "Patch", 0, LayerBounds(2, 2, 6, 6), solidRaster(6, 6, 7))), widthPx = 16, heightPx = 16)
			val reload = ReloadArtworkRequest(listOf(ReloadEntry(realignedSource.id, repainted.placedFor(realignedSource), contentHash = "v2")), options)
			assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, reload, areaId = null))
			val reloaded = session.model.value
			val reloadedSource = reloaded.sources.first { source -> source.name == "patch.png" }
			assertEquals(3 to -5, reloadedSource.offsetX to reloadedSource.offsetY, "the reload keeps the record's offset")
			assertEquals(5 to -3, reloadedSource.layers.single().let { row -> row.left to row.top })
			assertNull(reloaded.atlas.tileById[patchTile], "the changed tile is replaced")
			val reborn = reloaded.drawables.first { drawable -> drawable.atlasTileId == AtlasTileId("art-1/lyid:9~1") }
			assertContentEquals(floatArrayOf(3f, -5f, 13f, -5f, 13f, 5f, 3f, 5f), assertNotNull(reborn.mesh).positions, "re-born over the wider art, still placed")

			session.undo()
			assertSame(realigned, session.model.value, "undo restores the placed model")
			follower.cancel()
		}
}