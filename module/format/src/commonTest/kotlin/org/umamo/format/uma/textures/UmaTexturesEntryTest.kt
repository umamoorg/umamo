package org.umamo.format.uma.textures

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.format.binary.Crc32
import org.umamo.format.binary.ZipArchive
import org.umamo.format.binary.ZipEntry
import org.umamo.format.binary.ZipRecords
import org.umamo.format.binary.ZipWriter
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.uma.TEST_WRITER
import org.umamo.format.uma.TestEntry
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaContainer
import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.manifestJson
import org.umamo.format.uma.puppet.plantedValue
import org.umamo.format.uma.puppet.withArray
import org.umamo.format.uma.puppet.withKey
import org.umamo.format.uma.recordJson
import org.umamo.format.uma.umaArchiveOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the textures entry (docs/format/UMA.md §5): every field round-trips, the layout assigns and keeps pixel
 * paths, pixel entries the index stops naming leave the file while ones it never named stay (UMA §5.7), the render
 * pages follow their mode (UMA §5.5), and each rule a reader enforces fails with its path.
 */
class UmaTexturesEntryTest {
	private val indexPath = "textures/index.json"

	/**
	 * A PNG of one flat color.
	 *
	 * @param Int width  The width.
	 * @param Int height The height.
	 * @param Int value  The byte every channel takes, so images tell apart.
	 * @return ByteArray The PNG.
	 */
	private fun png(width: Int, height: Int, value: Int): ByteArray = PngCodec.write(RasterImage(width, height, ByteArray(width * height * 4) { value.toByte() }))

	/**
	 * An index using every field: a composition off the defaults, layer-addressed coordinates, a page, a placed
	 * and pinned tile with a binding, an unplaced tile with a lineage, and a bare tile.
	 *
	 * @return UmaTextures The index, without paths.
	 */
	private fun sampleTextures(): UmaTextures =
		UmaTextures(
			storedUvsAddressPages = false,
			composition = UmaComposition(alphaThreshold = 8, extrude = 0),
			pages = listOf(UmaPage(16, 16)),
			tiles =
				listOf(
					UmaTile("art-0/lyid:1", "Eye", 4, 3, placement = UmaPlacement(0, 1.5f, -0.0f, -1f, 2f, 90f), source = UmaSourceRef("art-0", "lyid:1", true), pinned = true),
					UmaTile("art-0/name:Mouth#2~1", "Mouth", 2, 2, source = UmaSourceRef("art-0", "name:Mouth#2", false), replaces = "art-0/name:Mouth#2"),
					UmaTile("guid-3", "Hair", 5, 1),
				),
		)

	/** The PNG per tile of [sampleTextures], by id. */
	private val samplePngs: Map<String, ByteArray> =
		mapOf("art-0/lyid:1" to png(4, 3, 10), "art-0/name:Mouth#2~1" to png(2, 2, 20), "guid-3" to png(5, 1, 30))

	/**
	 * A pixel source over [pngs].
	 *
	 * @param Map                 pngs        The PNG per tile id.
	 * @param UmaRenderPagePixels renderPages The render pages' mode.
	 * @param ByteArray?          thumbnail   The thumbnail's PNG.
	 * @return UmaPixelSource The source.
	 */
	private fun pixelsOf(pngs: Map<String, ByteArray>, renderPages: UmaRenderPagePixels, thumbnail: ByteArray? = null): UmaPixelSource = UmaPixelSource({ id -> pngs[id] }, renderPages, thumbnail)

	/**
	 * A document holding [textures] saved and read back.
	 *
	 * @param UmaTextures    textures The index.
	 * @param UmaPixelSource pixels   The pixels.
	 * @return UmaModel The reopened document.
	 */
	private fun savedDocument(textures: UmaTextures, pixels: UmaPixelSource): UmaModel = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withTextures(textures, pixels)))

	/**
	 * [textures] without what the layout assigns - tile paths, render pages, and the thumbnail - for comparing
	 * against what a writer handed in.
	 *
	 * @param UmaTextures textures The index.
	 * @return UmaTextures The index as a writer hands it in.
	 */
	private fun withoutLayout(textures: UmaTextures): UmaTextures = textures.copy(tiles = textures.tiles?.map { tile -> tile.copy(path = null) }, renderPages = null, thumbnail = null)

	/**
	 * Every field survives a save, each pixel entry is stored and holds the bytes it was given, and an unedited
	 * reopen writes the same bytes.
	 */
	@Test
	fun everyFieldRoundTrips() {
		val pagePng = png(16, 16, 40)
		val thumbnailPng = png(8, 6, 50)
		val renderPages = UmaRenderPagePixels.Stored(listOf(pagePng), mapOf("ArtMesh1" to 0, "ArtMesh2" to 0))
		val bytes = Uma.write(UmaModel.create(TEST_WRITER).withTextures(sampleTextures(), pixelsOf(samplePngs, renderPages, thumbnailPng)))
		val reopened = Uma.read(bytes)
		val textures = assertNotNull(reopened.textures)
		assertEquals(sampleTextures(), withoutLayout(textures))
		assertEquals(UmaThumbnail(8, 6, "thumbnail.png"), textures.thumbnail, "the thumbnail's size comes from its PNG")
		assertEquals(UmaRenderPages(listOf(UmaRenderPage(16, 16, "textures/page-0.png")), mapOf("ArtMesh1" to 0, "ArtMesh2" to 0)), textures.renderPages)
		assertEquals(listOf("textures/tile-0.png", "textures/tile-1.png", "textures/tile-2.png"), textures.tiles!!.map { tile -> tile.path })
		for (tile in textures.tiles) {
			assertContentEquals(samplePngs.getValue(tile.id), reopened.payloadBytes(tile.path!!), "${tile.id}'s pixels")
		}
		assertContentEquals(pagePng, reopened.payloadBytes("textures/page-0.png"))
		assertContentEquals(thumbnailPng, reopened.payloadBytes("thumbnail.png"))

		val archive = ZipArchive.read(bytes)
		assertEquals(
			listOf("mimetype", "manifest.json", indexPath, "textures/tile-0.png", "textures/tile-1.png", "textures/tile-2.png", "textures/page-0.png", "thumbnail.png"),
			archive.entries.map { entry -> entry.name },
			"new pixel entries follow the index: tiles, render pages, thumbnail",
		)
		for (entry in archive.entries.filter { entry -> entry.name.endsWith(".png") }) {
			assertEquals(ZipRecords.METHOD_STORED, entry.method, "${entry.name} is stored")
		}
		assertContentEquals(bytes, Uma.write(reopened), "an unedited reopen rewrites byte for byte")
	}

	/**
	 * A tile the file holds keeps its entry and is never asked for pixels; a new tile is minted a path past every
	 * one in use; a tile the index drops leaves the file; a payload no record ever named stays, and a minted path
	 * never lands on it.
	 */
	@Test
	fun theLayoutKeepsMintsAndDrops() {
		val index = """{ "tiles": [ { "id": "A", "name": "A", "width": 4, "height": 3, "path": "textures/tile-0.png" }, { "id": "B", "name": "B", "width": 2, "height": 2, "path": "textures/tile-1.png" } ] }"""
		val foreign = png(1, 1, 99)
		val file =
			umaArchiveOf(
				manifestJson(listOf(recordJson(indexPath, "textures", required = true))),
				listOf(
					TestEntry(indexPath, index.encodeToByteArray()),
					TestEntry("textures/tile-0.png", png(4, 3, 1), deflated = false),
					TestEntry("textures/tile-1.png", png(2, 2, 2), deflated = false),
					TestEntry("textures/tile-5.png", foreign, deflated = false),
				),
			)
		val document = Uma.read(file)
		val kept = document.textures!!.tiles!![1]
		val newPng = png(3, 3, 3)
		val pixels =
			UmaPixelSource(
				{ id ->
					if (id != "C") {
						fail("the pixels of $id, which the file holds, were asked for")
					}
					newPng
				},
				UmaRenderPagePixels.Derived,
				null,
			)
		val saved = Uma.read(Uma.write(document.withTextures(UmaTextures(tiles = listOf(kept, UmaTile("C", "C", 3, 3))), pixels)))
		val tiles = saved.textures!!.tiles!!
		assertEquals(listOf("textures/tile-1.png", "textures/tile-6.png"), tiles.map { tile -> tile.path }, "B keeps its path; C is minted past the unnamed tile-5")
		assertContentEquals(png(2, 2, 2), saved.payloadBytes("textures/tile-1.png"), "B's bytes are copied")
		assertContentEquals(newPng, saved.payloadBytes("textures/tile-6.png"))
		assertNull(saved.payloadBytes("textures/tile-0.png"), "A left the index, so its entry left the file")
		assertContentEquals(foreign, saved.payloadBytes("textures/tile-5.png"), "a payload no record named stays")
	}

	/**
	 * The render pages follow their mode: stored images are written with their map, kept ones keep their entries,
	 * derived pages store nothing and drop the entries a stored set left, and an image that is a tile this save
	 * writes shares the tile's entry.
	 */
	@Test
	fun renderPagesFollowTheirMode() {
		val pagePng = png(16, 16, 40)
		val stored = savedDocument(UmaTextures(pages = listOf(UmaPage(16, 16))), pixelsOf(emptyMap(), UmaRenderPagePixels.Stored(listOf(pagePng), mapOf("D" to 0))))
		assertEquals(UmaRenderPages(listOf(UmaRenderPage(16, 16, "textures/page-0.png")), mapOf("D" to 0)), stored.textures!!.renderPages)

		val retained = Uma.read(Uma.write(stored.withTextures(UmaTextures(pages = listOf(UmaPage(16, 16))), pixelsOf(emptyMap(), UmaRenderPagePixels.Retained))))
		assertEquals(stored.textures!!.renderPages, retained.textures!!.renderPages, "kept render pages keep their entries and map")
		assertContentEquals(pagePng, retained.payloadBytes("textures/page-0.png"))
		assertFailsWith<UmaWriteException>("keeping render pages the file does not hold") {
			UmaModel.create(TEST_WRITER).withTextures(UmaTextures(), pixelsOf(emptyMap(), UmaRenderPagePixels.Retained))
		}

		val derived = Uma.read(Uma.write(stored.withTextures(UmaTextures(pages = listOf(UmaPage(32, 32))), pixelsOf(emptyMap(), UmaRenderPagePixels.Derived))))
		assertNull(derived.textures!!.renderPages, "derived pages store nothing")
		assertEquals(listOf(UmaPage(32, 32)), derived.textures!!.pages, "and the atlas's page sizes are still recorded")
		assertNull(derived.payloadBytes("textures/page-0.png"), "and the stored image's entry left the file")

		val shared = savedDocument(sampleTextures(), pixelsOf(samplePngs, UmaRenderPagePixels.Stored(listOf(samplePngs.getValue("guid-3"), pagePng), mapOf("D" to 0, "E" to 1))))
		val renderPages = shared.textures!!.renderPages!!
		assertEquals("textures/tile-2.png", renderPages.pages[0].path, "an image that is a tile's PNG shares the tile's entry")
		assertEquals("textures/page-0.png", renderPages.pages[1].path)
	}

	/**
	 * A thumbnail is written when given and leaves with its entry when not, and a key a newer writer planted in it
	 * leaves too rather than making the file unreadable.
	 */
	@Test
	fun theThumbnailComesAndGoes() {
		val withThumbnail = savedDocument(UmaTextures(), pixelsOf(emptyMap(), UmaRenderPagePixels.Derived, png(8, 8, 1)))
		assertEquals(UmaThumbnail(8, 8, "thumbnail.png"), withThumbnail.textures!!.thumbnail)
		val tree = withThumbnail.liveContent(UmaEntryKind.Textures)!!
		val planted = Uma.read(Uma.write(withThumbnail.withLiveContent(UmaEntryKind.Textures, withKey(tree, "thumbnail", withKey(tree["thumbnail"] as JsonObject, "futureKey", plantedValue("thumbnail"))))))

		val without = Uma.read(Uma.write(planted.withTextures(UmaTextures(), pixelsOf(emptyMap(), UmaRenderPagePixels.Derived))))
		assertNull(without.textures!!.thumbnail)
		assertNull(without.liveContent(UmaEntryKind.Textures)!!["thumbnail"], "the dropped thumbnail's unknown keys leave with it")
		assertNull(without.payloadBytes("thumbnail.png"))
	}

	/**
	 * A thumbnail rewrites the entry the file's thumbnail already has, and a new one never lands on a `thumbnail.png`
	 * something else holds.
	 */
	@Test
	fun theThumbnailNeverTakesAnotherEntry() {
		val foreign = png(2, 2, 77)
		val file =
			umaArchiveOf(
				manifestJson(listOf(recordJson(indexPath, "textures", required = true))),
				listOf(TestEntry(indexPath, "{}".encodeToByteArray()), TestEntry("thumbnail.png", foreign, deflated = false)),
			)
		val saved = Uma.read(Uma.write(Uma.read(file).withTextures(UmaTextures(), pixelsOf(emptyMap(), UmaRenderPagePixels.Derived, png(8, 8, 1)))))
		assertEquals(UmaThumbnail(8, 8, "thumbnail-0.png"), saved.textures!!.thumbnail, "the new thumbnail takes a free path")
		assertContentEquals(foreign, saved.payloadBytes("thumbnail.png"), "the payload already there is untouched")

		val newThumbnail = png(4, 4, 2)
		val resaved = Uma.read(Uma.write(saved.withTextures(UmaTextures(), pixelsOf(emptyMap(), UmaRenderPagePixels.Derived, newThumbnail))))
		assertEquals(UmaThumbnail(4, 4, "thumbnail-0.png"), resaved.textures!!.thumbnail, "a later thumbnail rewrites its own entry")
		assertContentEquals(newThumbnail, resaved.payloadBytes("thumbnail-0.png"))
		assertContentEquals(foreign, resaved.payloadBytes("thumbnail.png"))
	}

	/**
	 * Stored render pages whose images the file already holds name those entries, so saving the same pages again writes
	 * the same file; a changed image takes a new entry and the one it replaced leaves.
	 */
	@Test
	fun storedRenderPagesReuseTheirEntries() {
		val pagePngs = listOf(png(16, 16, 40), png(8, 8, 41))
		val stored = UmaRenderPagePixels.Stored(pagePngs, mapOf("D" to 0, "E" to 1))
		val first = Uma.write(UmaModel.create(TEST_WRITER).withTextures(UmaTextures(), pixelsOf(emptyMap(), stored)))
		val reopened = Uma.read(first)
		assertContentEquals(first, Uma.write(reopened.withTextures(UmaTextures(), pixelsOf(emptyMap(), stored))), "the same pages write the same file")

		val swapped = UmaRenderPagePixels.Stored(pagePngs.reversed(), mapOf("D" to 1, "E" to 0))
		val swappedPages = Uma.read(Uma.write(reopened.withTextures(UmaTextures(), pixelsOf(emptyMap(), swapped)))).textures!!.renderPages!!
		assertEquals(listOf("textures/page-1.png", "textures/page-0.png"), swappedPages.pages.map { page -> page.path }, "reordered images keep their entries")

		val changedPng = png(8, 8, 42)
		val changed = Uma.read(Uma.write(reopened.withTextures(UmaTextures(), pixelsOf(emptyMap(), UmaRenderPagePixels.Stored(listOf(pagePngs[0], changedPng), mapOf("D" to 0, "E" to 1))))))
		assertEquals(listOf("textures/page-0.png", "textures/page-2.png"), changed.textures!!.renderPages!!.pages.map { page -> page.path })
		assertContentEquals(changedPng, changed.payloadBytes("textures/page-2.png"))
		assertNull(changed.payloadBytes("textures/page-1.png"), "the replaced image's entry left the file")
	}

	/**
	 * A document saved twice before it is written lays its new pixel entries out in index order - tiles, render pages,
	 * thumbnail - exactly as one save of the second index does, not in the order the saves wrote them.
	 */
	@Test
	fun chainedSavesKeepIndexOrder() {
		val first = sampleTextures().copy(tiles = sampleTextures().tiles!!.take(2))
		val thumbnailPng = png(8, 6, 50)
		val once = UmaModel.create(TEST_WRITER).withTextures(first, pixelsOf(samplePngs, UmaRenderPagePixels.Derived, thumbnailPng))
		val twice = once.withTextures(sampleTextures(), pixelsOf(samplePngs, UmaRenderPagePixels.Derived, thumbnailPng))
		val names = ZipArchive.read(Uma.write(twice)).entries.map { entry -> entry.name }
		assertEquals(listOf("mimetype", "manifest.json", indexPath, "textures/tile-0.png", "textures/tile-1.png", "textures/tile-2.png", "thumbnail.png"), names)
	}

	/**
	 * A key a newer writer put in a tile stays with that tile through a rename and through the deletion of the tile
	 * before it.
	 */
	@Test
	fun tileKeysSurviveByIdentity() {
		val document = savedDocument(sampleTextures(), pixelsOf(samplePngs, UmaRenderPagePixels.Derived))
		val tree = withArray(document.liveContent(UmaEntryKind.Textures)!!, "tiles") { tileIndex, tile -> withKey(tile, "futureTile", plantedValue("tile $tileIndex")) }
		val planted = Uma.read(Uma.write(document.withLiveContent(UmaEntryKind.Textures, tree)))
		val tiles = planted.textures!!.tiles!!
		val edited = planted.textures!!.copy(tiles = listOf(tiles[1].copy(name = "Mouth renamed"), tiles[2]))
		val saved = Uma.read(Uma.write(planted.withTextures(edited, pixelsOf(emptyMap(), UmaRenderPagePixels.Derived))))
		val savedTiles = (saved.liveContent(UmaEntryKind.Textures)!!["tiles"] as JsonArray).map { tile -> tile as JsonObject }
		assertEquals(JsonPrimitive("Mouth renamed"), savedTiles[0]["name"])
		assertEquals(plantedValue("tile 1"), savedTiles[0]["futureTile"], "the renamed tile keeps its own key after the shift")
		assertEquals(plantedValue("tile 2"), savedTiles[1]["futureTile"])
	}

	/**
	 * A pixel entry another writer deflated reads by its header alone at open, and its whole PNG comes back verified; a
	 * deflated one whose header disagrees with its record still fails the open.
	 */
	@Test
	fun deflatedPixelEntriesRead() {
		val tilePng = png(4, 3, 1)
		val index = """{ "tiles": [ { "id": "A", "name": "A", "width": 4, "height": 3, "path": "textures/tile-0.png" } ] }"""

		/**
		 * A file whose one tile's PNG is [png], deflated.
		 *
		 * @param ByteArray png The PNG.
		 * @return ByteArray The file.
		 */
		fun fileWith(png: ByteArray): ByteArray =
			umaArchiveOf(
				manifestJson(listOf(recordJson(indexPath, "textures", required = true))),
				listOf(TestEntry(indexPath, index.encodeToByteArray()), TestEntry("textures/tile-0.png", png, deflated = true)),
			)
		val document = Uma.read(fileWith(tilePng))
		assertEquals(4, document.textures!!.tiles!!.single().width)
		assertContentEquals(tilePng, document.payloadBytes("textures/tile-0.png"))
		assertIs<UmaReadFailure.MalformedEntry>(assertFailsWith<UmaFormatException> { Uma.read(fileWith(png(3, 4, 1))) }.failure)
	}

	/**
	 * A deflated pixel entry is probed from its first bytes alone, so a page-sized PNG is not copied through
	 * the heap to read its header - and a stream too sparse for that probe still opens, through the full
	 * inflate the probe falls back to.
	 */
	@Test
	fun aSparseDeflatedPixelEntryStillReads() {
		val tilePng = png(4, 3, 1)
		val index = """{ "tiles": [ { "id": "A", "name": "A", "width": 4, "height": 3, "path": "textures/tile-0.png" } ] }"""
		// A raw deflate stream (RFC 1951 §3.2.4): a thousand empty stored blocks of five bytes each - well past
		// any bound a header probe hands the inflater - then one final stored block holding the PNG.  Legal,
		// and nothing the stream's first bytes can yield.
		val emptyBlock = byteArrayOf(0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
		val length = tilePng.size
		val lengthComplement = length xor 0xFFFF
		val finalHeader = byteArrayOf(0x01, (length and 0xFF).toByte(), (length ushr 8).toByte(), (lengthComplement and 0xFF).toByte(), (lengthComplement ushr 8).toByte())
		val stream = ByteArray(1000 * emptyBlock.size) { byteIndex -> emptyBlock[byteIndex % emptyBlock.size] } + finalHeader + tilePng
		val writer = ZipWriter(ZipRecords.DOS_EPOCH_DATE_TIME)
		writer.addStored(UmaContainer.MIMETYPE_PATH, UmaContainer.MIMETYPE.encodeToByteArray())
		writer.addDeflated(UmaContainer.MANIFEST_PATH, manifestJson(listOf(recordJson(indexPath, "textures", required = true))).encodeToByteArray())
		writer.addDeflated(indexPath, index.encodeToByteArray())
		writer.addRaw(
			ZipEntry(
				name = "textures/tile-0.png",
				method = ZipRecords.METHOD_DEFLATED,
				flags = 0,
				crc32 = Crc32().also { checksum -> checksum.update(tilePng) }.value,
				compressedSize = stream.size,
				uncompressedSize = tilePng.size,
				dosDateTime = ZipRecords.DOS_EPOCH_DATE_TIME,
				payloadOffset = 0,
				versionNeeded = ZipRecords.VERSION_DEFLATED,
			),
			stream,
		)

		val document = Uma.read(writer.finish())

		assertEquals(4, document.textures!!.tiles!!.single().width, "the probe fell back to the full inflate and read the header")
		assertContentEquals(tilePng, document.payloadBytes("textures/tile-0.png"))
	}

	/**
	 * Each rule a reader enforces fails the open with a typed reason naming where.
	 */
	@Test
	fun brokenIndexesFail() {
		/**
		 * The failure reading a file with [index] and [pixels] produces.
		 *
		 * @param String index  The index's JSON text.
		 * @param Map    pixels The pixel entries by path.
		 * @return UmaReadFailure The failure.
		 */
		fun failureOf(index: String, pixels: Map<String, ByteArray>): UmaReadFailure {
			val file =
				umaArchiveOf(
					manifestJson(listOf(recordJson(indexPath, "textures", required = true))),
					listOf(TestEntry(indexPath, index.encodeToByteArray())) + pixels.map { (path, bytes) -> TestEntry(path, bytes, deflated = false) },
				)
			return assertFailsWith<UmaFormatException> { Uma.read(file) }.failure
		}

		/**
		 * Asserts a malformed failure whose detail names [where].
		 *
		 * @param UmaReadFailure failure The failure.
		 * @param String         where   A fragment the detail must hold.
		 */
		fun assertMalformedAt(failure: UmaReadFailure, where: String) {
			val detail = assertIs<UmaReadFailure.MalformedEntry>(failure, where).detail
			assertTrue(detail.contains(where), "'$detail' names $where")
		}
		val tile = png(4, 3, 1)

		/**
		 * A 4 x 3 tile record's JSON text.
		 *
		 * @param String extra Further members, including a leading comma, or empty.
		 * @param String path  The pixel entry's path.
		 * @return String The record.
		 */
		fun tileJson(extra: String = "", path: String = "textures/tile-0.png"): String = """{ "id": "A", "name": "A", "width": 4, "height": 3, "path": "$path"$extra }"""

		assertMalformedAt(failureOf("""{ "tiles": [ ${tileJson(""", "placement": { "page": 0, "positionX": 0, "positionY": 0, "scaleX": 1, "scaleY": 1, "rotationDegrees": 0 }""")} ] }""", mapOf("textures/tile-0.png" to tile)), "tiles[0].placement.page")
		assertMalformedAt(failureOf("""{ "tiles": [ ${tileJson()} ] }""", mapOf("textures/tile-0.png" to png(3, 4, 1))), "tiles[0]")
		assertMalformedAt(failureOf("""{ "tiles": [ ${tileJson()} ] }""", mapOf("textures/tile-0.png" to ByteArray(40))), "tiles[0]")
		assertEquals(UmaReadFailure.MissingEntry("textures/tile-9.png"), failureOf("""{ "tiles": [ ${tileJson(path = "textures/tile-9.png")} ] }""", emptyMap()))
		assertMalformedAt(failureOf("""{ "tiles": [ { "id": "A", "name": "A", "width": 4, "height": 3 } ] }""", emptyMap()), "tiles[0]")
		assertMalformedAt(failureOf("""{ "tiles": [ ${tileJson()}, ${tileJson()} ] }""", mapOf("textures/tile-0.png" to tile)), "tiles[1]")
		assertMalformedAt(failureOf("""{ "composition": { "alphaThreshold": 0 } }""", emptyMap()), "composition.alphaThreshold")
		assertMalformedAt(failureOf("""{ "composition": { "extrude": -1 } }""", emptyMap()), "composition.extrude")
		val renderPage = """{ "width": 16, "height": 16, "path": "textures/page-0.png" }"""
		assertMalformedAt(failureOf("""{ "renderPages": { "pages": [ $renderPage ], "drawablePages": { "D": 1 } } }""", mapOf("textures/page-0.png" to png(16, 16, 1))), "renderPages.drawablePages.D")
		assertMalformedAt(failureOf("""{ "renderPages": { "pages": [ $renderPage ] } }""", mapOf("textures/page-0.png" to png(8, 16, 1))), "renderPages.pages[0]")
		assertIs<UmaReadFailure.MalformedEntry>(failureOf("""{ "renderPages": { "pages": [ { "width": 16, "height": 16 } ] } }""", emptyMap()), "a render page without a path")
		assertIs<UmaReadFailure.MalformedEntry>(failureOf("""{ "pages": [ { "width": 1.5, "height": 16 } ] }""", emptyMap()), "a fractional size")
		assertIs<UmaReadFailure.MalformedEntry>(failureOf("""{ "tiles": [ { "id": "A", "width": 4, "height": 3, "path": "textures/tile-0.png" } ] }""", mapOf("textures/tile-0.png" to tile)), "a tile without a name")
	}

	/**
	 * A save refuses a new tile it has no pixels for or whose PNG is another size, a thumbnail that is not a PNG, a
	 * render page that is not one, and a repeated tile id.
	 */
	@Test
	fun unwritableIndexesAreRefused() {
		assertFailsWith<UmaWriteException>("a tile without pixels") {
			UmaModel.create(TEST_WRITER).withTextures(UmaTextures(tiles = listOf(UmaTile("A", "A", 4, 3))), pixelsOf(emptyMap(), UmaRenderPagePixels.Derived))
		}
		assertFailsWith<UmaWriteException>("a tile whose PNG is another size") {
			UmaModel.create(TEST_WRITER).withTextures(UmaTextures(tiles = listOf(UmaTile("A", "A", 4, 3))), pixelsOf(mapOf("A" to png(3, 3, 1)), UmaRenderPagePixels.Derived))
		}
		assertFailsWith<UmaWriteException>("a thumbnail that is not a PNG") {
			UmaModel.create(TEST_WRITER).withTextures(UmaTextures(), pixelsOf(emptyMap(), UmaRenderPagePixels.Derived, ByteArray(10)))
		}
		assertFailsWith<UmaWriteException>("a render page that is not a PNG") {
			UmaModel.create(TEST_WRITER).withTextures(UmaTextures(), pixelsOf(emptyMap(), UmaRenderPagePixels.Stored(listOf(ByteArray(10)), emptyMap())))
		}
		assertFailsWith<UmaWriteException>("a repeated tile id") {
			UmaModel.create(TEST_WRITER).withTextures(UmaTextures(tiles = listOf(UmaTile("A", "A", 4, 3), UmaTile("A", "Again", 4, 3))), pixelsOf(mapOf("A" to png(4, 3, 1)), UmaRenderPagePixels.Derived))
		}
	}
}