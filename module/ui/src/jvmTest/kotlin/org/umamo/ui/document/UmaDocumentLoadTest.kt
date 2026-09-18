package org.umamo.ui.document

import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaRenderPagePixels
import org.umamo.interop.uma.UmaDocumentBridge
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How a `.uma` opens through the same byte-level loader every other document uses: as a UmaDocument, with
 * the file kept whole for the next save; read-only when the file holds a required entry this version
 * cannot interpret; and with a typed reason when it cannot open at all.  Synthetic files throughout - a
 * document the codec wrote from an empty rig, then rewritten by hand where a case needs a manifest this
 * writer would never produce.
 */
class UmaDocumentLoadTest {
	/**
	 * A `.uma` the codec wrote from a new document's empty puppet.
	 *
	 * @return ByteArray The file.
	 */
	private fun emptyDocumentBytes(): ByteArray {
		val puppet = newBlankDocument().puppet
		val written = UmaDocumentBridge.documentOf(UmaModel.create(umamoWriterInfo()), puppet, UmaPixelSource({ null }, UmaRenderPagePixels.Derived, null))
		return Uma.write(written)
	}

	/**
	 * [bytes] re-zipped with its manifest text passed through [rewrite] and [extraEntries] appended, keeping
	 * the stored mimetype first as the format requires.
	 *
	 * @param ByteArray bytes        A UMA file.
	 * @param List      extraEntries Entries to append, path to contents.
	 * @param Function  rewrite      The manifest's new text given its old one.
	 * @return ByteArray The rewritten file.
	 */
	private fun rewritten(bytes: ByteArray, extraEntries: List<Pair<String, ByteArray>> = emptyList(), rewrite: (String) -> String): ByteArray {
		val output = ByteArrayOutputStream()
		ZipOutputStream(output).use { zip ->
			ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
				generateSequence { input.nextEntry }.forEach { entry ->
					val contents = input.readBytes()
					if (entry.name == "mimetype") {
						zip.putNextEntry(
							ZipEntry(entry.name).apply {
								method = ZipEntry.STORED
								size = contents.size.toLong()
								compressedSize = contents.size.toLong()
								crc = CRC32().apply { update(contents) }.value
							},
						)
						zip.write(contents)
					} else {
						zip.putNextEntry(ZipEntry(entry.name))
						zip.write(if (entry.name == "manifest.json") rewrite(contents.decodeToString()).encodeToByteArray() else contents)
					}
					zip.closeEntry()
				}
			}
			for ((path, contents) in extraEntries) {
				zip.putNextEntry(ZipEntry(path))
				zip.write(contents)
				zip.closeEntry()
			}
		}
		return output.toByteArray()
	}

	@Test
	fun aUmaOpensAsAUmaDocumentKeptWholeForTheNextSave() {
		val bytes = emptyDocumentBytes()

		val load = loadDocument(bytes, "rig.uma", "/rigs/rig.uma")

		val document = assertIs<UmaDocument>(assertIs<DocumentLoad.Loaded>(load).document)
		assertEquals("/rigs/rig.uma", document.path)
		assertFalse(document.isReadOnly)
		assertTrue(document.puppet.drawables.isEmpty(), "the empty rig came back empty")
		assertEquals(newBlankDocument().puppet.canvasWidth, document.puppet.canvasWidth, "with its canvas")
		assertTrue(document.storedPages == null, "a file with no render pages derives them")
		assertEquals(bytes.size, Uma.write(document.uma).size, "the file is kept whole: rewriting it costs nothing new")
	}

	@Test
	fun aRequiredEntryThisVersionCannotReadOpensReadOnly() {
		val bytes =
			rewritten(
				emptyDocumentBytes(),
				extraEntries = listOf("future/thing.json" to "{}".encodeToByteArray()),
			) { manifest ->
				val record = """{ "path": "future/thing.json", "kind": "mystery", "version": 1, "minVersion": 1, "required": true }"""
				val patched = manifest.replaceFirst("\"entries\": [", "\"entries\": [ $record,")
				check(patched != manifest) { "the manifest's entries array was not found: $manifest" }
				patched
			}

		val document = assertIs<UmaDocument>(assertIs<DocumentLoad.Loaded>(loadDocument(bytes, "rig.uma", "/rigs/rig.uma")).document)

		assertTrue(document.isReadOnly, "an unknown required entry blocks saving")
		assertEquals(listOf("future/thing.json"), document.readOnlyReasons.map { reason -> reason.path })
		assertFalse(DocumentFile(document).canSave, "and the save target says so")
	}

	@Test
	fun aNewerContainerReportsANewerFormat() {
		val bytes =
			rewritten(emptyDocumentBytes()) { manifest ->
				val patched = manifest.replaceFirst(Regex("\"containerVersion\":\\s*1"), "\"containerVersion\": 99")
				check(patched != manifest) { "the container version was not found: $manifest" }
				patched
			}

		val failure = assertIs<DocumentLoad.Failed>(loadDocument(bytes, "rig.uma", "/rigs/rig.uma")).failure

		assertEquals(DocumentOpenError.NewerFormat, failure.error)
		assertEquals("rig.uma", failure.displayName)
	}

	@Test
	fun aDamagedUmaReportsAParseFailure() {
		val bytes = emptyDocumentBytes()
		val truncated = bytes.copyOf(bytes.size - 40)

		val failure = assertIs<DocumentLoad.Failed>(loadDocument(truncated, "rig.uma", "/rigs/rig.uma")).failure

		assertEquals(DocumentOpenError.ParseFailed, failure.error)
	}
}