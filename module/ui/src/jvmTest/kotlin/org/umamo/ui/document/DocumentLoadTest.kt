package org.umamo.ui.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Failure classification of the byte-core document loader: each rejection path must report the
 * matching [DocumentOpenError] (the shell's alert message key) instead of a bare null, so a failed
 * open is always explainable to the user.  Synthetic bytes only - no corpus dependency.
 */
class DocumentLoadTest {
	@Test
	fun unrecognizedBytesFailAsUnrecognized() {
		val load = loadDocument(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), "mystery.bin", "mystery.bin")
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.Unrecognized, failed.failure.error)
		assertEquals("mystery.bin", failed.failure.displayName)
	}

	@Test
	fun recognizedNonEditorFormatFailsAsNotOpenable() {
		// MOC3 header: magic "MOC3" @ +0x00.  The BYTE-level loader keeps reporting MOC3 NotOpenable by
		// design: sidecar discovery needs a directory, so only the file-level loadDocument(PlatformFile)
		// routes a .moc3 to the sidecar loader (see Moc3DocumentLoadTest for that path).
		val bytes = ByteArray(64)
		"MOC3".encodeToByteArray().copyInto(bytes)
		val load = loadDocument(bytes, "puppet.moc3", "puppet.moc3")
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.NotOpenable, failed.failure.error)
	}

	@Test
	fun corruptCmo3FailsAsParseFailed() {
		// CAFF magic @ +0x00 makes detection pick the CMO3 codec; the garbage tail makes its read throw.
		val bytes = ByteArray(64)
		"CAFF".encodeToByteArray().copyInto(bytes)
		val load = loadDocument(bytes, "broken.cmo3", "broken.cmo3")
		val failed = assertIs<DocumentLoad.Failed>(load)
		assertEquals(DocumentOpenError.ParseFailed, failed.failure.error)
	}
}
