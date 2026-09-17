package org.umamo.format.uma

import org.umamo.format.binary.ZipRecords
import org.umamo.format.binary.ZipWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Pins that every file that cannot open as UMA fails with its typed reason (docs/format/UMA.md §3.4),
 * never with an untyped crash and never by opening something wrong.
 */
class UmaReadFailureTest {
	private val puppetRecord = recordJson("model/puppet.json", "puppet", required = true)
	private val puppetEntry = TestEntry("model/puppet.json", """{ "marker": "puppet" }""".encodeToByteArray())

	/**
	 * The failure reading [bytes] raises.
	 *
	 * @param ByteArray bytes The file.
	 * @return UmaReadFailure The typed reason.
	 */
	private fun failureOf(bytes: ByteArray): UmaReadFailure = assertFailsWith<UmaFormatException> { Uma.read(bytes) }.failure

	/**
	 * Bytes that are not a ZIP, or a ZIP that is damaged, are a corrupt container.
	 */
	@Test
	fun corruptContainer() {
		assertIs<UmaReadFailure.CorruptContainer>(failureOf(ByteArray(100) { byteIndex -> byteIndex.toByte() }), "not a ZIP")
		val good = umaArchiveOf(manifestJson(listOf(puppetRecord)), listOf(puppetEntry))
		assertIs<UmaReadFailure.CorruptContainer>(failureOf(good.copyOf(good.size - 1)), "a truncated archive")
	}

	/**
	 * A ZIP without the UMA mimetype, with a different one, or with a manifest naming another format is
	 * not UMA.
	 */
	@Test
	fun notUma() {
		assertIs<UmaReadFailure.NotUma>(failureOf(umaArchiveOf(manifestJson(listOf(puppetRecord)), listOf(puppetEntry), mimetype = null)), "no mimetype")
		assertIs<UmaReadFailure.NotUma>(failureOf(umaArchiveOf(manifestJson(listOf(puppetRecord)), listOf(puppetEntry), mimetype = "application/x-krita")), "a Krita mimetype")
		assertIs<UmaReadFailure.NotUma>(failureOf(umaArchiveOf(manifestJson(listOf(puppetRecord), format = "kra"), listOf(puppetEntry))), "another format")
	}

	/**
	 * A manifest that is missing, not JSON, not an object, or breaks a record rule is malformed.
	 */
	@Test
	fun malformedManifest() {
		/**
		 * Asserts the manifest text fails as malformed.
		 *
		 * @param String manifest The manifest text, or null for none.
		 * @param String label    What is wrong with it.
		 */
		fun assertMalformed(manifest: String?, label: String) {
			assertIs<UmaReadFailure.MalformedManifest>(failureOf(umaArchiveOf(manifest, listOf(puppetEntry))), label)
		}
		assertMalformed(null, "no manifest")
		assertMalformed("{ \"format\": \"uma\", ", "not valid JSON")
		assertMalformed("[ 1, 2 ]", "not an object")
		assertMalformed("""{ "format": "uma", "entries": [] }""", "no container version")
		assertMalformed(manifestJson(listOf(puppetRecord), containerVersion = 0), "a container version below 1")
		assertMalformed("""{ "format": "uma", "containerVersion": "1", "entries": [] }""", "a container version that is a string")
		assertMalformed("""{ "format": "uma", "containerVersion": 1 }""", "no entries")
		assertMalformed("""{ "format": "uma", "containerVersion": 1, "writer": "me", "entries": [] }""", "a writer that is not an object")
		assertMalformed(manifestJson(listOf(recordJson("model/puppet.json", "puppet", version = 1, minVersion = 2))), "minVersion above version")
		assertMalformed(manifestJson(listOf(puppetRecord, puppetRecord)), "a path listed twice")
		assertMalformed(manifestJson(listOf(puppetRecord, recordJson("model/other.json", "puppet"))), "a known kind listed twice")
		assertMalformed(manifestJson(listOf(recordJson("manifest.json", "puppet"))), "a reserved path")
		assertMalformed(manifestJson(listOf(recordJson("model/", "puppet"))), "a directory path")
		assertMalformed(manifestJson(listOf("""{ "path": "model/puppet.json", "kind": "puppet", "version": 1, "minVersion": 1 }""")), "a record without required")
	}

	/**
	 * A listed path the archive does not hold is a missing entry.
	 */
	@Test
	fun missingEntry() {
		assertEquals(UmaReadFailure.MissingEntry("model/puppet.json"), failureOf(umaArchiveOf(manifestJson(listOf(puppetRecord)))))
	}

	/**
	 * A live entry that is not a JSON object fails loudly, even when the file marks it optional.
	 */
	@Test
	fun malformedEntry() {
		val notAnObject = failureOf(umaArchiveOf(manifestJson(listOf(puppetRecord)), listOf(TestEntry("model/puppet.json", "[]".encodeToByteArray()))))
		assertIs<UmaReadFailure.MalformedEntry>(notAnObject, "a required entry that is not an object")
		val optionalNotJson =
			failureOf(
				umaArchiveOf(
					manifestJson(listOf(puppetRecord, recordJson("editor/state.json", "editor"))),
					listOf(puppetEntry, TestEntry("editor/state.json", "not json".encodeToByteArray())),
				),
			)
		assertEquals("editor/state.json", assertIs<UmaReadFailure.MalformedEntry>(optionalNotJson, "an optional entry that is not JSON").path)
	}

	/**
	 * A mimetype whose bytes fail their CRC-32 is a corrupt container, not a foreign file.
	 */
	@Test
	fun corruptMimetypeIsCorruptNotForeign() {
		val writer = ZipWriter(ZipRecords.DOS_EPOCH_DATE_TIME)
		writer.addStored(UmaContainer.MIMETYPE_PATH, UmaContainer.MIMETYPE.encodeToByteArray())
		writer.addDeflated(UmaContainer.MANIFEST_PATH, manifestJson(listOf(puppetRecord)).encodeToByteArray())
		writer.addDeflated(puppetEntry.path, puppetEntry.contents)
		val bytes = writer.finish()
		// The mimetype's first content byte, at offset 38: changing it breaks the recorded CRC-32.
		bytes[38] = 'A'.code.toByte()
		assertIs<UmaReadFailure.CorruptContainer>(failureOf(bytes))
	}
}