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

/*
 * Synthetic `.uma` files for the tests in this package: a document the codec wrote from an empty rig, and
 * the same file rewritten by hand where a case needs a manifest this writer would never produce.
 */

/**
 * A `.uma` the codec wrote from a new document's empty puppet.
 *
 * @return ByteArray The file.
 */
internal fun emptyDocumentBytes(): ByteArray {
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
internal fun rewritten(bytes: ByteArray, extraEntries: List<Pair<String, ByteArray>> = emptyList(), rewrite: (String) -> String): ByteArray {
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

/**
 * A `.uma` holding a REQUIRED entry of a kind this version does not know (`future/thing.json`), which is
 * what opens a document read-only.
 *
 * @return ByteArray The file.
 */
internal fun umaBytesWithUnknownRequiredEntry(): ByteArray =
	rewritten(
		emptyDocumentBytes(),
		extraEntries = listOf("future/thing.json" to "{}".encodeToByteArray()),
	) { manifest ->
		val record = """{ "path": "future/thing.json", "kind": "mystery", "version": 1, "minVersion": 1, "required": true }"""
		val patched = manifest.replaceFirst("\"entries\": [", "\"entries\": [ $record,")
		check(patched != manifest) { "the manifest's entries array was not found: $manifest" }
		patched
	}