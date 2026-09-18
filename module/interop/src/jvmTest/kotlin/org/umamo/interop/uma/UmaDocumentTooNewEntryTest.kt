package org.umamo.interop.uma

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.UmaWriterInfo
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaRenderPagePixels
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins how a save treats an optional entry too new for this reader (docs/format/UMA.md §3.3): the document stays
 * editable, a save carries the entry byte for byte while the model has nothing for its kind, and a save with content
 * for the kind is refused rather than dropping what a newer writer put there.
 *
 * The fixtures re-zip a saved document with a newer manifest record, which only a foreign ZIP writer can build, so
 * the test sits in jvmTest beside java.util.zip.
 */
class UmaDocumentTooNewEntryTest {
	private val writer = UmaWriterInfo("Umamo", "test")

	private val tilePng: ByteArray = PngCodec.write(RasterImage(2, 2, ByteArray(16) { 40 }))

	/**
	 * A model with [atlas] and [sources] and nothing else.
	 *
	 * @param PuppetAtlas     atlas   The atlas.
	 * @param List<ArtSource> sources The linked source art.
	 * @return PuppetModel The model.
	 */
	private fun modelOf(atlas: PuppetAtlas, sources: List<ArtSource>): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			atlas = atlas,
			sources = sources,
		).withDerivedRenderRoot()

	/** A model holding one tile on one page and one linked source. */
	private val fullModel: PuppetModel =
		modelOf(
			PuppetAtlas(pages = listOf(AtlasPage(4, 4)), tiles = listOf(AtlasTile(AtlasTileId("T"), "Tile", 2, 2))),
			listOf(ArtSource(ArtSourceId("art-0"), "art.psd", null, "psd")),
		)

	/** A model with no atlas and no linked source art. */
	private val emptyModel: PuppetModel = modelOf(PuppetAtlas.Empty, emptyList())

	/**
	 * The pixels a save of [fullModel] needs.
	 *
	 * @param UmaRenderPagePixels renderPages What the drawables sample.
	 * @return UmaPixelSource The pixels.
	 */
	private fun pixels(renderPages: UmaRenderPagePixels = UmaRenderPagePixels.Derived): UmaPixelSource = UmaPixelSource({ tilePng }, renderPages, null)

	/**
	 * [bytes] re-zipped with the manifest record of [kind] declared too new for this reader and optional.
	 *
	 * @param ByteArray    bytes A UMA archive.
	 * @param UmaEntryKind kind  The kind whose record to age forward.
	 * @return ByteArray The archive.
	 */
	private fun withTooNewRecord(bytes: ByteArray, kind: UmaEntryKind): ByteArray {
		val output = ByteArrayOutputStream()
		ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
			ZipOutputStream(output).use { zip ->
				while (true) {
					val entry = input.nextEntry ?: break
					var contents = input.readBytes()
					if (entry.name == "manifest.json") {
						val manifest = Json.parseToJsonElement(contents.decodeToString()).jsonObject
						val records =
							manifest.getValue("entries").jsonArray.map { record ->
								if (record.jsonObject["kind"] == JsonPrimitive(kind.wireName)) {
									JsonObject(record.jsonObject + mapOf("version" to JsonPrimitive(2), "minVersion" to JsonPrimitive(2), "required" to JsonPrimitive(false)))
								} else {
									record
								}
							}
						contents = Json.encodeToString(JsonObject.serializer(), JsonObject(manifest + ("entries" to JsonArray(records)))).encodeToByteArray()
					}
					zip.putNextEntry(ZipEntry(entry.name))
					zip.write(contents)
					zip.closeEntry()
				}
			}
		}
		return output.toByteArray()
	}

	/**
	 * Each archive entry's uncompressed bytes, by name.
	 *
	 * @param ByteArray bytes A ZIP archive.
	 * @return Map The contents.
	 */
	private fun contentsOf(bytes: ByteArray): Map<String, ByteArray> {
		val contents = LinkedHashMap<String, ByteArray>()
		ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
			while (true) {
				val entry = input.nextEntry ?: break
				contents[entry.name] = input.readBytes()
			}
		}
		return contents
	}

	/**
	 * A too-new sources entry is carried through a save of a model that links no source art, and refuses a save of
	 * one that does.
	 */
	@Test
	fun tooNewSourcesEntryIsCarriedUntilTheModelLinksArt() {
		val saved = Uma.write(UmaDocumentBridge.documentOf(UmaModel.create(writer), fullModel, pixels()))
		val aged = withTooNewRecord(saved, UmaEntryKind.Sources)
		val document = Uma.read(aged)
		assertFalse(document.isReadOnly, "an optional entry leaves the document editable")
		assertTrue(document.holdsTooNewEntry(UmaEntryKind.Sources))
		val reopened = UmaDocumentBridge.modelOf(document)
		assertTrue(reopened.sources.isEmpty(), "the carried entry links nothing")

		val resaved = Uma.write(UmaDocumentBridge.documentOf(document, reopened, pixels()))
		assertContentEquals(contentsOf(aged).getValue("source/index.json"), contentsOf(resaved).getValue("source/index.json"), "the entry survives the save")
		assertTrue(Uma.read(resaved).holdsTooNewEntry(UmaEntryKind.Sources), "and is still carried")

		val failure = assertFailsWith<UmaWriteException> { UmaDocumentBridge.documentOf(document, fullModel, pixels()) }
		assertEquals("source/index.json", failure.path, "the refusal names the carried entry")
	}

	/**
	 * A too-new textures entry is carried, pixel entries and all, through a save of a model with no atlas, and refuses
	 * a save with an atlas or stored render pages.
	 */
	@Test
	fun tooNewTexturesEntryIsCarriedUntilTheModelHasAnAtlas() {
		val saved = Uma.write(UmaDocumentBridge.documentOf(UmaModel.create(writer), fullModel, pixels()))
		val aged = withTooNewRecord(saved, UmaEntryKind.Textures)
		val document = Uma.read(aged)
		assertFalse(document.isReadOnly, "an optional entry leaves the document editable")
		assertTrue(document.holdsTooNewEntry(UmaEntryKind.Textures))

		val resaved = Uma.write(UmaDocumentBridge.documentOf(document, emptyModel, pixels()))
		val agedContents = contentsOf(aged)
		val resavedContents = contentsOf(resaved)
		assertContentEquals(agedContents.getValue("textures/index.json"), resavedContents.getValue("textures/index.json"), "the index survives the save")
		assertContentEquals(agedContents.getValue("textures/tile-0.png"), resavedContents.getValue("textures/tile-0.png"), "and so do the pixels it names")

		assertFailsWith<UmaWriteException>("a model with an atlas") { UmaDocumentBridge.documentOf(document, fullModel.copy(sources = emptyList()), pixels()) }
		assertFailsWith<UmaWriteException>("stored render pages") {
			UmaDocumentBridge.documentOf(document, emptyModel, pixels(UmaRenderPagePixels.Stored(listOf(tilePng), emptyMap())))
		}
	}
}