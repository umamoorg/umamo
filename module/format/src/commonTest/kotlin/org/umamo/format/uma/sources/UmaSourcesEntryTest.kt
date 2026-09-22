package org.umamo.format.uma.sources

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.umamo.format.uma.TEST_WRITER
import org.umamo.format.uma.TestEntry
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaEntryKind
import org.umamo.format.uma.UmaFormatException
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaReadFailure
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.manifestJson
import org.umamo.format.uma.puppet.elementOf
import org.umamo.format.uma.puppet.plantedValue
import org.umamo.format.uma.puppet.withArray
import org.umamo.format.uma.puppet.withKey
import org.umamo.format.uma.recordJson
import org.umamo.format.uma.umaArchiveOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the sources entry (docs/format/UMA.md §6): every inventory field and flag round-trips, a hash is the one
 * form this reader knows (UMA §6.4), keys a newer writer planted follow their source and layer across edits, and
 * each rule a reader enforces fails with its path.
 */
class UmaSourcesEntryTest {
	private val indexPath = "source/index.json"

	private val hash = "sha256:" + "0123456789abcdef".repeat(4)

	/**
	 * Sources using every field: a read file with a hash, a time, and a placement offset, a layer with every flag
	 * set, a plain layer, and a source that was never read.
	 *
	 * @return UmaSources The sources.
	 */
	private fun sampleSources(): UmaSources =
		UmaSources(
			listOf(
				UmaSource(
					id = "art-0",
					name = "character.clip",
					format = "clip",
					path = "C:/work/character.clip",
					contentHash = hash,
					lastModified = 1_757_894_400_123L,
					layers =
						listOf(
							UmaSourceLayer("uuid-1", "Eye L", "Head/Face", -12, 180, 220, 140, visible = false, present = false, contentHash = hash, empty = true, replaced = true, ignored = true),
							UmaSourceLayer("name:Sketch#2", "Sketch", "", 0, 0, 2048, 2048, visible = true),
						),
					offsetX = 64,
					offsetY = -32,
				),
				UmaSource("guid-7", "Erica.psd", "psd"),
			),
		)

	/**
	 * Every field and flag survives a save.
	 */
	@Test
	fun everyFieldRoundTrips() {
		val reopened = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withSources(sampleSources())))
		assertEquals(sampleSources(), reopened.sources)
		val entry = reopened.entries.single { candidate -> candidate.liveKind == UmaEntryKind.Sources }
		assertEquals(false, entry.required, "the sources entry is optional")
	}

	/**
	 * A key a newer writer put in a source and in a layer stays with them through a layer rename and the deletion
	 * of the source before it.
	 */
	@Test
	fun keysSurviveByIdentity() {
		val sources = UmaSources(listOf(UmaSource("gone", "gone.psd", "psd")) + sampleSources().sources!!)
		val document = Uma.read(Uma.write(UmaModel.create(TEST_WRITER).withSources(sources)))
		val tree =
			withArray(document.liveContent(UmaEntryKind.Sources)!!, "sources") { sourceIndex, source ->
				val planted = withKey(source, "futureSource", plantedValue("source $sourceIndex"))
				if (source["layers"] == null) planted else withArray(planted, "layers") { layerIndex, layer -> withKey(layer, "futureLayer", plantedValue("layer $layerIndex")) }
			}
		val planted = Uma.read(Uma.write(document.withLiveContent(UmaEntryKind.Sources, tree)))
		val kept = planted.sources!!.sources!!.drop(1)
		val renamed = kept[0].copy(layers = kept[0].layers!!.map { layer -> if (layer.key == "uuid-1") layer.copy(name = "Eye Left") else layer })
		val saved = Uma.read(Uma.write(planted.withSources(UmaSources(listOf(renamed, kept[1])))))
		val savedTree = saved.liveContent(UmaEntryKind.Sources)!!
		val source = assertNotNull(elementOf(savedTree, "sources", "id", "art-0"))
		assertEquals(plantedValue("source 1"), source["futureSource"], "the source keeps its own key after the shift")
		val layer = assertNotNull(elementOf(source, "layers", "key", "uuid-1"))
		assertEquals(plantedValue("layer 0"), layer["futureLayer"], "the renamed layer keeps its key")
		assertEquals(2, (savedTree["sources"] as JsonArray).size)
		assertEquals(plantedValue("source 2"), ((savedTree["sources"] as JsonArray)[1] as JsonObject)["futureSource"])
	}

	/**
	 * Each rule a reader enforces fails the open as malformed, naming where; the same values refuse a save.
	 */
	@Test
	fun brokenSourcesFail() {
		/**
		 * Asserts reading a file whose sources entry is [json] fails as malformed, naming [where].
		 *
		 * @param String json  The entry's JSON text.
		 * @param String where A fragment the detail must hold.
		 */
		fun assertMalformed(json: String, where: String) {
			val file = umaArchiveOf(manifestJson(listOf(recordJson(indexPath, "sources"))), listOf(TestEntry(indexPath, json.encodeToByteArray())))
			val failure = assertFailsWith<UmaFormatException>(where) { Uma.read(file) }.failure
			val detail = assertIs<UmaReadFailure.MalformedEntry>(failure, where).detail
			assertTrue(detail.contains(where), "'$detail' names $where")
		}
		val layer = """{ "key": "k", "name": "n", "groupPath": "", "left": 0, "top": 0, "width": 1, "height": 1, "visible": true }"""
		val digest = "0123456789abcdef".repeat(4)
		assertMalformed("""{ "sources": [ { "id": "a", "name": "a", "format": "psd", "contentHash": "md5:$digest" } ] }""", "sources[0].contentHash")
		assertMalformed("""{ "sources": [ { "id": "a", "name": "a", "format": "psd", "contentHash": "sha256:${digest.uppercase()}" } ] }""", "sources[0].contentHash")
		assertMalformed("""{ "sources": [ { "id": "a", "name": "a", "format": "psd", "contentHash": "sha256:abc" } ] }""", "sources[0].contentHash")
		assertMalformed("""{ "sources": [ { "id": "a", "name": "a", "format": "psd", "layers": [ ${layer.replace("\"width\": 1", "\"width\": -1")} ] } ] }""", "sources[0].layers[0]")
		assertMalformed("""{ "sources": [ { "id": "a", "name": "a", "format": "psd" }, { "id": "a", "name": "b", "format": "psd" } ] }""", "sources[1]")
		assertMalformed("""{ "sources": [ { "id": "a", "name": "a", "format": "psd", "layers": [ $layer, $layer ] } ] }""", "sources[0].layers[1]")
		val file = umaArchiveOf(manifestJson(listOf(recordJson(indexPath, "sources"))), listOf(TestEntry(indexPath, """{ "sources": [ { "id": "a", "name": "a", "format": "psd", "layers": [ ${layer.replace(", \"visible\": true", "")} ] } ] }""".encodeToByteArray())))
		assertIs<UmaReadFailure.MalformedEntry>(assertFailsWith<UmaFormatException>("a layer without visible") { Uma.read(file) }.failure)

		assertFailsWith<UmaWriteException>("a save with a foreign hash") {
			UmaModel.create(TEST_WRITER).withSources(UmaSources(listOf(UmaSource("a", "a", "psd", contentHash = "md5:$digest"))))
		}
		assertFailsWith<UmaWriteException>("a save repeating a source id") {
			UmaModel.create(TEST_WRITER).withSources(UmaSources(listOf(UmaSource("a", "a", "psd"), UmaSource("a", "b", "psd"))))
		}
		val plainLayer = UmaSourceLayer("k", "n", "", 0, 0, 1, 1, visible = true)
		assertFailsWith<UmaWriteException>("a save repeating a layer key within a source") {
			UmaModel.create(TEST_WRITER).withSources(UmaSources(listOf(UmaSource("a", "a", "psd", layers = listOf(plainLayer, plainLayer)))))
		}
	}
}