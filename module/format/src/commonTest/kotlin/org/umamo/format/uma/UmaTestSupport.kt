package org.umamo.format.uma

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.format.binary.ZipRecords
import org.umamo.format.binary.ZipWriter

/*
 * Builders for the UMA tests: hand-made archives shaped like files a newer, older, or foreign writer
 * could produce, so each compatibility rule is exercised from bytes rather than from the codec's own model.
 */

/** The writer record the tests stamp on documents they create. */
internal val TEST_WRITER: UmaWriterInfo = UmaWriterInfo("Umamo", "0.0.0-test")

/**
 * One archive entry a hand-built UMA file holds after its bootstrap entries.
 *
 * @property String    path     The entry path.
 * @property ByteArray contents The entry's bytes.
 * @property Boolean   deflated Whether the entry is compressed.
 */
internal class TestEntry(
	val path: String,
	val contents: ByteArray,
	val deflated: Boolean = true,
)

/**
 * A manifest entry record as JSON text, with any extra keys appended.
 *
 * @param String  path       The entry path.
 * @param String  kind       The entry kind.
 * @param Int     version    The declared schema version.
 * @param Int     minVersion The declared minimum schema version.
 * @param Boolean required   Whether the entry is declared required.
 * @param String  extraKeys  Extra JSON members, including a leading comma, or empty.
 * @return String The record.
 */
internal fun recordJson(path: String, kind: String, version: Int = 1, minVersion: Int = 1, required: Boolean = false, extraKeys: String = ""): String =
	"""{ "path": "$path", "kind": "$kind", "version": $version, "minVersion": $minVersion, "required": $required$extraKeys }"""

/**
 * A manifest as JSON text.
 *
 * @param List   records          The entry records' JSON.
 * @param Int    containerVersion The container version.
 * @param String format           The `format` value.
 * @param String extraKeys        Extra top-level JSON members, including a leading comma, or empty.
 * @return String The manifest.
 */
internal fun manifestJson(records: List<String>, containerVersion: Int = 1, format: String = "uma", extraKeys: String = ""): String =
	"""{ "format": "$format", "containerVersion": $containerVersion, "writer": { "app": "Future", "version": "9" }, "entries": [ ${records.joinToString(", ")} ]$extraKeys }"""

/**
 * A hand-built UMA archive: the stored mimetype first, the manifest second, then [entries] in order.
 *
 * @param String          manifest The manifest's JSON text, or null to leave it out.
 * @param List<TestEntry> entries  The entries after the bootstrap pair.
 * @param String          mimetype The mimetype entry's content, or null to leave it out.
 * @return ByteArray The archive.
 */
internal fun umaArchiveOf(manifest: String?, entries: List<TestEntry> = emptyList(), mimetype: String? = UmaContainer.MIMETYPE): ByteArray {
	val writer = ZipWriter(ZipRecords.DOS_EPOCH_DATE_TIME)
	mimetype?.let { content -> writer.addStored(UmaContainer.MIMETYPE_PATH, content.encodeToByteArray()) }
	manifest?.let { content -> writer.addDeflated(UmaContainer.MANIFEST_PATH, content.encodeToByteArray()) }
	for (entry in entries) {
		if (entry.deflated) {
			writer.addDeflated(entry.path, entry.contents)
		} else {
			writer.addStored(entry.path, entry.contents)
		}
	}
	return writer.finish()
}

/**
 * A small JSON object for a live entry's content.
 *
 * @param String marker A value that tells trees apart.
 * @return JsonObject The tree.
 */
internal fun sampleTree(marker: String): JsonObject =
	buildJsonObject {
		put("marker", JsonPrimitive(marker))
		put("count", 3)
	}