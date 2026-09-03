package org.umamo.ui.document

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.render.encodeAtlasPng
import org.umamo.ui.model.AtlasRepackHost
import org.umamo.ui.model.AtlasRepackReport
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.repackPageSizeOf
import org.umamo.ui.model.runAtlasRepack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The live repack-then-export gate, driven the way the shell drives it: the session's repack command,
 * the page resolver following the model, the export policy reading the resolver's pages, the bytes
 * written and re-read.  Every unit gate below this exercises one stage in isolation; this is the one
 * that proves the stages agree, so an exported file carries the arrangement the viewport shows.
 *
 * Skips without the corpus sample.
 */
class LiveRepackExportGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aLiveRepackExportsTheRepackedArrangement() =
		runBlocking {
			val file = sample
			if (file == null) {
				println("cmo3.sample not present; skipping the live repack export gate")
				return@runBlocking
			}
			val load = loadDocument(file.readBytes(), file.name, file.path)
			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			var refusal: AtlasRepackReport? = null
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = document.artRasters,
					sessionAtlasPages = sessionAtlasPages,
					premultipliedAlpha = document.textures.premultipliedAlpha,
					scope = this,
					report = { report -> refusal = report },
					rememberOptions = {},
				)
			runAtlasRepack(host, AtlasPackOptions(maxPageSize = repackPageSizeOf(document.puppet)), areaId = null)
			assertNull(refusal?.refusals?.joinToString { "${it.tileName}: ${it.reason}" }, "the repack refused")
			val repacked = session.model.value
			assertNotSame(document.puppet.atlas, repacked.atlas, "the repack committed a new atlas")
			withTimeout(10_000) {
				while (sessionAtlasPages.binding.value.atlas !== repacked.atlas) {
					yield()
				}
			}
			val effective = sessionAtlasPages.binding.value.textures
			assertNotSame(document.textures, effective, "the resolver published the repack's own pages")
			val prepared =
				prepareCmo3Export(
					document = document,
					edited = exportedModelFor(document, session),
					effectiveTextures = effective,
					modelName = "gate",
					nowMillis = 0L,
					obfuscateKey = 0,
				)
			follower.cancel()
			println("export report: ${prepared.report}")
			val reread = Cmo3.read(Cmo3.write(prepared.model))
			val reimported = Cmo3Import.fromModelSource(reread.root as CModelSource)
			assertEquals(repacked.atlas.pages, reimported.atlas.pages, "page inventory")
			var mismatches = 0
			for (tile in repacked.atlas.tiles) {
				val reread = reimported.atlas.tileById[tile.id]?.placement
				if (reread != tile.placement) {
					mismatches++
					if (mismatches <= 5) {
						println("placement mismatch '${tile.name}': session=${tile.placement} file=$reread")
					}
				}
			}
			assertEquals(0, mismatches, "tiles whose exported placement differs from the session's")
			val editedUvs = repacked.drawables.associate { it.id to it.mesh?.uvs }
			var uvMismatches = 0
			for (drawable in reimported.drawables) {
				val expected = editedUvs[drawable.id] ?: continue
				if (!expected.contentEquals(drawable.mesh?.uvs)) {
					uvMismatches++
				}
			}
			assertEquals(0, uvMismatches, "drawables whose exported uvs differ from the session's")
			// The page BYTES the file carries must be the resolver's pages, not the imported originals.
			val exportedPages = cmo3AtlasPages(reread.root as CModelSource) { resource -> reread.extractLayerPng(resource) }.pageBytes
			val expectedPages = effective.atlases.map { page -> encodeAtlasPng(page) }
			assertEquals(expectedPages.size, exportedPages.size, "exported page count")
			for ((pageIndex, bytes) in exportedPages.withIndex()) {
				assertTrue(expectedPages.any { expected -> expected.contentEquals(bytes) }, "exported page $pageIndex is not one of the repack's pages")
			}
		}
}