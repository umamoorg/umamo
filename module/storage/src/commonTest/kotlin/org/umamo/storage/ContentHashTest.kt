package org.umamo.storage

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The content hash is SHA-256 as lowercase hex, the same whether the bytes are in hand or on disk. */
class ContentHashTest {
	@Test
	fun theDigestIsSha256Hex() {
		// The published vector for "abc".
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", contentHashOf("abc".encodeToByteArray()))
		assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", contentHashOf(ByteArray(0)), "the empty file hashes too")
	}

	@Test
	fun aFileHashesToTheSameDigestAsItsBytesAndAMissingOneToNull() {
		val fileSystem = FakeFileSystem()
		val path = "/art/a.psd".toPath()
		fileSystem.createDirectories(path.parent!!)
		fileSystem.write(path) { write("abc".encodeToByteArray()) }
		assertEquals(contentHashOf("abc".encodeToByteArray()), contentHashOfFile(fileSystem, path))
		assertNull(contentHashOfFile(fileSystem, "/art/missing.psd".toPath()))
	}
}