package org.umamo.ui.document

import kotlinx.coroutines.runBlocking
import org.umamo.format.art.LayerBounds
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * guess.  A file that is there is placed by its record's offset.  The CMO3 case skips without the
 * corpus sample.
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

	/**
	 * A listed file read from disk is placed by its record's offset before any operation sees it, so its
	 * layers sit where the document's inventory says they do; a record at offset zero reads as-is.
	 */
	@Test
	fun aFileOnDiskIsPlacedByItsRecord() =
		runBlocking {
			val folder = createTempDirectory("umamo-source-read").toFile()
			try {
				val file = File(folder, "patch.png")
				val rgba = ByteArray(4 * 3 * 4) { index -> if (index % 4 == 3) 0xFF.toByte() else 0x40 }
				file.writeBytes(PngCodec.write(RasterImage(4, 3, rgba)))
				val placed = ArtSource(ArtSourceId("art-1"), name = "patch.png", path = file.path, format = "png", offsetX = 30, offsetY = -5)

				val read = assertNotNull(readSourceArt(newBlankDocument(), placed))
				assertTrue(!read.fromCmo3)
				assertEquals(LayerBounds(30, -5, 4, 3), read.art.layers.single().bounds, "the flat raster's one layer sits at the record's offset")
				assertNotNull(read.contentHash, "a disk read records its hash")

				val unplaced = assertNotNull(readSourceArt(newBlankDocument(), placed.copy(offsetX = 0, offsetY = 0)))
				assertEquals(LayerBounds(0, 0, 4, 3), unplaced.art.layers.single().bounds)
			} finally {
				folder.deleteRecursively()
			}
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