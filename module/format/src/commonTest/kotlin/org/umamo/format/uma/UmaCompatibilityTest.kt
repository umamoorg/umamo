package org.umamo.format.uma

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.binary.ZipArchive
import org.umamo.format.binary.ZipRecords
import org.umamo.format.binary.ZipWriter
import org.umamo.format.uma.sources.UmaSources
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the compatibility model against files a newer writer could produce (docs/format/UMA.md §3.3):
 * what opens editable, what opens read-only, and that everything this reader does not own survives a
 * save byte for byte (D4).
 */
class UmaCompatibilityTest {
	private val puppetRecord = recordJson("model/puppet.json", "puppet", required = true)
	private val puppetEntry = TestEntry("model/puppet.json", """{ "marker": "puppet" }""".encodeToByteArray())

	/**
	 * The manifest [bytes] holds, parsed.
	 *
	 * @param ByteArray bytes A UMA archive.
	 * @return JsonObject The manifest.
	 */
	private fun manifestOf(bytes: ByteArray): JsonObject {
		val archive = ZipArchive.read(bytes)
		return parseJsonObject(archive.contents(archive.entry(UmaContainer.MANIFEST_PATH)!!)) { detail -> UmaReadFailure.MalformedManifest(detail) }
	}

	/**
	 * Each entry's raw payload in [bytes], by name.
	 *
	 * @param ByteArray bytes A UMA archive.
	 * @return Map The raw payloads.
	 */
	private fun rawPayloadsOf(bytes: ByteArray): Map<String, ByteArray> {
		val archive = ZipArchive.read(bytes)
		return archive.entries.associate { entry -> entry.name to archive.rawPayload(entry) }
	}

	/**
	 * A document the codec writes reads back and writes again to the same bytes, and to the same entries,
	 * when nothing was edited (D4 as a test).
	 */
	@Test
	fun unchangedDocumentRewritesByteForByte() {
		val created =
			UmaModel
				.create(TEST_WRITER)
				.withLiveContent(UmaEntryKind.Puppet, sampleTree("puppet"))
				.withLiveContent(UmaEntryKind.Sources, sampleTree("sources"))
		val first = Uma.write(created)
		val second = Uma.write(Uma.read(first))
		assertContentEquals(first, second, "an unedited round trip reproduces the archive")

		// A file carrying everything a newer writer might add, normalized by one save, then saved again.
		val future =
			umaArchiveOf(
				manifestJson(
					listOf(puppetRecord, recordJson("model/physics.json", "physics", extraKeys = """, "driver": "wind"""")),
					extraKeys = """, "savedBy": { "tool": "future" }""",
				),
				listOf(
					TestEntry("textures/tile-0.png", ByteArray(500) { byteIndex -> byteIndex.toByte() }, deflated = false),
					puppetEntry,
					TestEntry("model/physics.json", """{ "springs": [ 1, 2, 3 ] }""".encodeToByteArray()),
					TestEntry("model/buffers.bin", ByteArray(4000) { byteIndex -> (byteIndex % 5).toByte() }),
				),
			)
		val normalized = Uma.write(Uma.read(future))
		assertContentEquals(normalized, Uma.write(Uma.read(normalized)), "a saved file with unknown entries rewrites byte for byte")
	}

	/**
	 * An unknown optional kind is preserved and the document stays editable: its record, its bytes, and its
	 * place in the archive all survive a save.
	 */
	@Test
	fun unknownOptionalEntryIsPreservedAndEditable() {
		val physicsBytes = """{ "springs": [ 1, 2, 3 ], "gravity": -9.8 }""".encodeToByteArray()
		val source =
			umaArchiveOf(
				manifestJson(listOf(puppetRecord, recordJson("model/physics.json", "physics", version = 3, minVersion = 2, extraKeys = """, "driver": "wind""""))),
				listOf(puppetEntry, TestEntry("model/physics.json", physicsBytes)),
			)
		val model = Uma.read(source)
		assertFalse(model.isReadOnly, "an optional unknown entry does not block editing")
		val physics = model.entries.single { entry -> entry.kind == "physics" }
		assertNull(physics.liveKind, "the unknown entry is carried, not read")

		val saved = Uma.write(model)
		assertContentEquals(rawPayloadsOf(source)["model/physics.json"], rawPayloadsOf(saved)["model/physics.json"], "its payload is byte-identical")
		val savedRecord = manifestOf(saved)["entries"]!!.jsonArray.map { element -> element.jsonObject }.single { record -> record["kind"] == JsonPrimitive("physics") }
		val sourceRecord = manifestOf(source)["entries"]!!.jsonArray.map { element -> element.jsonObject }.single { record -> record["kind"] == JsonPrimitive("physics") }
		assertEquals(sourceRecord, savedRecord, "its manifest record is verbatim, unknown keys and versions included")
		assertEquals(listOf("mimetype", "manifest.json", "model/puppet.json", "model/physics.json"), ZipArchive.read(saved).entries.map { entry -> entry.name }, "archive order")
	}

	/**
	 * An unknown kind the file marks required opens read-only with its reason, and cannot be written.
	 */
	@Test
	fun unknownRequiredEntryMakesTheDocumentReadOnly() {
		val source =
			umaArchiveOf(
				manifestJson(listOf(puppetRecord, recordJson("model/rig2.json", "rig2", required = true))),
				listOf(puppetEntry, TestEntry("model/rig2.json", "{}".encodeToByteArray())),
			)
		val model = Uma.read(source)
		assertTrue(model.isReadOnly, "an unknown required entry blocks editing")
		assertEquals(listOf(UmaReadOnlyReason("model/rig2.json", "rig2", UmaReadOnlyCause.UnknownKind)), model.readOnlyReasons, "the reason names the entry")
		assertNotNull(model.liveContent(UmaEntryKind.Puppet), "what this reader understands still reads")
		assertFailsWith<IllegalStateException> { Uma.write(model) }
	}

	/**
	 * A known kind whose minVersion is above this reader's version is treated as unknown: preserved, and
	 * read-only only when the file marks it required.
	 */
	@Test
	fun knownKindTooNewIsTreatedAsUnknown() {
		val tooNewRequired =
			Uma.read(
				umaArchiveOf(
					manifestJson(listOf(recordJson("model/puppet.json", "puppet", version = 2, minVersion = 2, required = true))),
					listOf(puppetEntry),
				),
			)
		assertEquals(
			listOf(UmaReadOnlyReason("model/puppet.json", "puppet", UmaReadOnlyCause.UnsupportedVersion(2, 1))),
			tooNewRequired.readOnlyReasons,
			"a required entry this reader would misread blocks editing",
		)
		assertNull(tooNewRequired.liveContent(UmaEntryKind.Puppet), "and is not read")

		val sourcesBytes = """{ "sources": [] }""".encodeToByteArray()
		val tooNewOptionalSource =
			umaArchiveOf(
				manifestJson(listOf(puppetRecord, recordJson("source/index.json", "sources", version = 2, minVersion = 2))),
				listOf(puppetEntry, TestEntry("source/index.json", sourcesBytes)),
			)
		val tooNewOptional = Uma.read(tooNewOptionalSource)
		assertFalse(tooNewOptional.isReadOnly, "an optional entry this reader would misread does not block editing")
		assertNull(tooNewOptional.liveContent(UmaEntryKind.Sources), "and is not read")
		assertTrue(tooNewOptional.holdsTooNewEntry(UmaEntryKind.Sources), "the document reports the kind it cannot set")
		assertFalse(tooNewOptional.holdsTooNewEntry(UmaEntryKind.Puppet), "and only that kind")
		assertFailsWith<UmaWriteException>("the too-new entry occupies its kind") {
			tooNewOptional.withLiveContent(UmaEntryKind.Sources, sampleTree("mine"))
		}
		assertFailsWith<UmaWriteException>("a typed save over it is refused the same way") {
			tooNewOptional.withSources(UmaSources())
		}
		assertContentEquals(
			rawPayloadsOf(tooNewOptionalSource)["source/index.json"],
			rawPayloadsOf(Uma.write(tooNewOptional))["source/index.json"],
			"and survives a save byte for byte",
		)
	}

	/**
	 * A known kind at a newer version that older readers may still read opens live, keeps the keys this
	 * reader does not know, and is written back at this writer's version (D8).
	 */
	@Test
	fun additiveNewerVersionReadsLiveAndWritesCurrent() {
		val model =
			Uma.read(
				umaArchiveOf(
					manifestJson(listOf(recordJson("model/puppet.json", "puppet", version = 2, minVersion = 1, required = true))),
					listOf(TestEntry("model/puppet.json", """{ "marker": "puppet", "addedInV2": { "flag": true } }""".encodeToByteArray())),
				),
			)
		assertFalse(model.isReadOnly, "an additive newer version stays editable")
		val tree = assertNotNull(model.liveContent(UmaEntryKind.Puppet), "the entry reads live")
		assertNotNull(tree["addedInV2"], "the newer key is in the tree")

		val saved = Uma.write(model)
		val record = manifestOf(saved)["entries"]!!.jsonArray.single().jsonObject
		assertEquals(1, record["version"]!!.jsonPrimitive.int, "written at this writer's version")
		assertEquals(1, record["minVersion"]!!.jsonPrimitive.int, "with this writer's minVersion")
		assertNotNull(Uma.read(saved).liveContent(UmaEntryKind.Puppet)!!["addedInV2"], "the newer key survives the save")
	}

	/**
	 * A container version above this reader's refuses the whole file with the typed reason.
	 */
	@Test
	fun newerContainerVersionIsRefused() {
		val failure =
			assertFailsWith<UmaFormatException> {
				Uma.read(umaArchiveOf(manifestJson(listOf(puppetRecord), containerVersion = 2), listOf(puppetEntry)))
			}
		assertEquals(UmaReadFailure.UnsupportedContainerVersion(2, 1), failure.failure)
	}

	/**
	 * Keys this writer does not own, at the manifest's top level and inside its records, keep their values
	 * and their places.
	 */
	@Test
	fun unknownManifestKeysSurviveInPlace() {
		val record = recordJson("model/puppet.json", "puppet", required = true, extraKeys = """, "checksum": "abc"""")
		val manifest = """{ "format": "uma", "note": "first", "containerVersion": 1, "writer": { "app": "Future", "build": 77, "version": "9" }, "entries": [ """ + record + """ ], "trailer": [ 1, 2 ] }"""
		val source = umaArchiveOf(manifest, listOf(puppetEntry))
		val saved = manifestOf(Uma.write(Uma.read(source)))
		assertEquals(listOf("format", "note", "containerVersion", "writer", "entries", "trailer"), saved.keys.toList(), "top-level keys keep their order")
		assertEquals(JsonPrimitive(77), saved["writer"]!!.jsonObject["build"], "an unknown writer key survives")
		assertEquals(JsonPrimitive("abc"), saved["entries"]!!.jsonArray.single().jsonObject["checksum"], "an unknown record key survives")
		assertEquals(manifestOf(source)["trailer"], saved["trailer"], "an unknown top-level value survives")
	}

	/**
	 * Entries no manifest record names keep their raw bytes, their compression, and their order.
	 */
	@Test
	fun unlistedPayloadsArePreservedInOrder() {
		val stored = ByteArray(700) { byteIndex -> (byteIndex * 7).toByte() }
		val deflated = ByteArray(9000) { byteIndex -> (byteIndex % 9).toByte() }
		val source =
			umaArchiveOf(
				manifestJson(listOf(puppetRecord)),
				listOf(
					TestEntry("thumbnail.png", stored, deflated = false),
					puppetEntry,
					TestEntry("model/buffers.bin", deflated),
					TestEntry("textures/", ByteArray(0), deflated = false),
				),
			)
		val model = Uma.read(source)
		assertEquals(listOf("thumbnail.png", "model/buffers.bin", "textures/"), model.payloads.map { payload -> payload.path }, "payloads in archive order")
		val saved = Uma.write(model)
		val savedArchive = ZipArchive.read(saved)
		assertEquals(
			listOf("mimetype", "manifest.json", "thumbnail.png", "model/puppet.json", "model/buffers.bin", "textures/"),
			savedArchive.entries.map { entry -> entry.name },
			"the archive keeps its order after the bootstrap entries",
		)
		val sourcePayloads = rawPayloadsOf(source)
		val savedPayloads = rawPayloadsOf(saved)
		for (path in listOf("thumbnail.png", "model/buffers.bin", "textures/")) {
			assertContentEquals(sourcePayloads[path], savedPayloads[path], "'$path' raw bytes")
			assertEquals(ZipArchive.read(source).entry(path)!!.method, savedArchive.entry(path)!!.method, "'$path' compression")
		}
		assertContentEquals(deflated, savedArchive.contents(savedArchive.entry("model/buffers.bin")!!), "and still inflates")
	}

	/**
	 * A mimetype a re-zipping tool moved away from the front still identifies the file, and the next save
	 * puts it back first.
	 */
	@Test
	fun displacedMimetypeReadsAndIsRestoredFirst() {
		val writer = ZipWriter(ZipRecords.DOS_EPOCH_DATE_TIME)
		writer.addDeflated(UmaContainer.MANIFEST_PATH, manifestJson(listOf(puppetRecord)).encodeToByteArray())
		writer.addDeflated(puppetEntry.path, puppetEntry.contents)
		writer.addDeflated(UmaContainer.MIMETYPE_PATH, UmaContainer.MIMETYPE.encodeToByteArray())
		val displaced = writer.finish()
		assertFalse(Uma.matches(displaced), "the probe needs the mimetype first")
		val saved = Uma.write(Uma.read(displaced))
		assertTrue(Uma.matches(saved), "the save restores the identifying layout")
		assertIs<UmaEntryContent.Live>(Uma.read(saved).entries.single().content, "and the content survives")
	}
}