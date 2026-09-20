package org.umamo.ui.document

import kotlinx.coroutines.runBlocking
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins where an artwork operation reads a listed file's art from when the file itself is out of reach.
 *
 * A CMO3 carries the layers the official editor decomposed out of the artist's file, so a relink or a
 * Match Automatically on a CMO3-origin document still has the art to work with on a machine that never
 * had the PSD.  Any other origin has nothing to fall back to, and must say so with a null rather than
 * guess.  The CMO3 case skips without the corpus sample.
 */
class SourceReadTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aMissingFileOnACmo3DocumentReadsTheDecomposedLayers() =
		runBlocking {
			val file = sample
			if (file == null) {
				println("cmo3.sample not present; skipping the decomposed source read gate")
				return@runBlocking
			}
			val load = loadDocument(file.readBytes(), file.name, file.path)
			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(load).document)
			val source = assertNotNull(document.puppet.sources.firstOrNull(), "the sample lists the artwork file it was rigged from")
			// The artist's file is recorded at a path from the machine it was rigged on; here it reads as missing.
			val probed = ArrayList<String>()

			val read =
				readSourceArt(document, source) { path ->
					probed.add(path)
					false
				}

			val fromDocument = assertNotNull(read, "the CMO3's own layers stand in for the file")
			assertTrue(fromDocument.fromCmo3)
			assertTrue(fromDocument.art.layers.isNotEmpty(), "with the layers the official editor decomposed")
			assertNull(fromDocument.contentHash, "no file was read, so there is no file hash to record")
			assertNull(fromDocument.lastModified)
			assertTrue(source.path == null || probed == listOf(source.path), "the recorded path is asked about once, and nothing else is")
		}

	@Test
	fun aMissingFileOnAnyOtherDocumentReadsNothing() =
		runBlocking {
			val document = newBlankDocument()
			val source = ArtSource(ArtSourceId("hero"), name = "hero.psd", path = "/art/hero.psd", format = "psd")

			val read = readSourceArt(document, source) { false }

			assertNull(read, "only a CMO3 holds a copy of its artwork's layers")
		}
}