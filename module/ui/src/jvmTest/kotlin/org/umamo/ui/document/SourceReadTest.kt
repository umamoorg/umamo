package org.umamo.ui.document

import kotlinx.coroutines.runBlocking
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.render.DecodedImage
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.SourceLayerRef
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins where an artwork operation reads a listed file's art from when the file itself is out of reach.
 *
 * A CMO3 carries the layers the official editor decomposed out of the artist's file, so a relink or a Match
 * Automatically on a CMO3-origin document still has the art to work with on a machine that never had the
 * PSD.  Any other document falls back to the art it holds: a layer a tile binds reads as that tile, a layer
 * no tile binds has no pixels, and the record's rows are what the operation keeps.  A file that is there is
 * placed by its record's offset.  The CMO3 case skips without the corpus sample.
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
				readSourceArt(document, document.puppet, source) { path ->
					probed.add(path)
					false
				}

			val fromDocument = assertNotNull(read, "the CMO3's own layers stand in for the file")
			assertEquals(SourceReadOrigin.Cmo3Layers, fromDocument.origin)
			assertNull(fromDocument.inventory, "the decomposed layers carry the inventory of their own")
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
				val placed = ArtSource(ArtSourceId("art-1"), name = "patch.png", path = file.path, format = "png", offsetX = 30, offsetZ = 5)

				val blank = newBlankDocument()
				val read = assertNotNull(readSourceArt(blank, blank.puppet, placed))
				assertEquals(SourceReadOrigin.File, read.origin)
				assertEquals(LayerBounds(30, -5, 4, 3), read.art.layers.single().bounds, "the flat raster's one layer sits at the record's offset, 5 px UP being canvas y -5")
				assertNotNull(read.contentHash, "a disk read records its hash")

				val unplaced = assertNotNull(readSourceArt(blank, blank.puppet, placed.copy(offsetX = 0, offsetZ = 0)))
				assertEquals(LayerBounds(0, 0, 4, 3), unplaced.art.layers.single().bounds)
			} finally {
				folder.deleteRecursively()
			}
		}

	/**
	 * A missing file on a document with no CMO3 to fall back to reads as the art the document holds: each
	 * present row a tile binds takes that tile's pixels at the row's position, which already carries the
	 * record's offset; a row no tile binds, and a row the file erased, has none; a row the file lost is left
	 * out; and the operation keeps the record's present rows as its inventory.
	 */
	@Test
	fun aMissingFileOnAnyOtherDocumentReadsTheArtTheDocumentHolds() =
		runBlocking {
			val document = newBlankDocument()
			val sourceId = ArtSourceId("art-0")

			fun row(key: String, left: Int, present: Boolean = true, empty: Boolean = false): ArtSourceLayer = ArtSourceLayer(key, key, "", left, 7, 3, 2, true, present = present, empty = empty)
			val rows = listOf(row("lyid:1", 40), row("lyid:2", 50), row("lyid:3", 60, present = false), row("lyid:4", 70, empty = true))
			val source = ArtSource(sourceId, name = "hero.psd", path = "/art/hero.psd", format = "psd", layers = rows, offsetX = 30)

			fun tile(key: String): AtlasTile = AtlasTile(AtlasTileId("art-0/$key"), key, 3, 2, source = SourceLayerRef(sourceId, key, true))
			val model = document.puppet.copy(atlas = PuppetAtlas(pages = emptyList(), tiles = listOf(tile("lyid:1"), tile("lyid:3"), tile("lyid:4"))), sources = listOf(source))
			val pixels = ByteArray(3 * 2 * 4) { index -> index.toByte() }
			document.artRasters.addDecoded(mapOf(AtlasTileId("art-0/lyid:1") to DecodedImage(pixels, 3, 2)))

			val read = assertNotNull(readSourceArt(document, model, source) { false }, "the document's tiles stand in for the file")

			assertEquals(SourceReadOrigin.DocumentTiles, read.origin)
			assertEquals(listOf("lyid:1", "lyid:2", "lyid:4"), read.art.layers.map { layer -> layer.id.raw }, "the lost row is left out")
			assertEquals(listOf(SourceLayerKind.Raster, SourceLayerKind.Unknown, SourceLayerKind.Unknown), read.art.layers.map { layer -> layer.kind }, "only the bound, unerased row has pixels")
			val bound = read.art.layers.first()
			assertEquals(LayerBounds(40, 7, 3, 2), bound.bounds, "at the row's position, not placed by the offset again")
			assertContentEquals(pixels, bound.raster.rgba, "with its tile's pixels")
			assertEquals(rows.filter { row -> row.present }, read.inventory, "the record's present rows are the inventory the operation keeps")
			assertNull(read.contentHash, "no file was read")

			val nothingHeld = model.copy(sources = listOf(source.copy(layers = rows.map { row -> row.copy(present = false) })))
			assertNull(readSourceArt(document, nothingHeld, nothingHeld.sources.single()) { false }, "a file with no present row has nothing to read")
		}
}