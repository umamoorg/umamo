package org.umamo.format.cmo3.caff

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * JVM/Android DEFLATE bridge using java.util.zip, matching the editor's framing.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Compression</a>
 */
internal actual object CaffZip {
	private const val ENTRY_NAME = "contents"

	actual fun unzipSingle(zipStream: ByteArray): ByteArray {
		ZipInputStream(ByteArrayInputStream(zipStream)).use { input ->
			input.nextEntry ?: throw IllegalStateException("empty CAFF zip stream")
			return input.readBytes()
		}
	}

	actual fun zipSingle(contents: ByteArray, level: Int): ByteArray {
		val sink = ByteArrayOutputStream(contents.size)
		ZipOutputStream(sink).use { output ->
			output.setLevel(level)
			// CMO3: the compressed blob is a single-entry ("contents") zip stream.
			output.putNextEntry(ZipEntry(ENTRY_NAME))
			output.write(contents)
			output.closeEntry()
		}
		return sink.toByteArray()
	}
}
