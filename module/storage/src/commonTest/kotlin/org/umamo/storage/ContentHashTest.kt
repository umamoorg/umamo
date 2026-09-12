package org.umamo.storage

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A file hashes to the same digest as its bytes, and a missing one to null. */
class ContentHashTest {
	@Test
	fun aFileHashesToTheSameDigestAsItsBytesAndAMissingOneToNull() {
		val fileSystem = FakeFileSystem()
		val path = "/art/a.psd".toPath()
		fileSystem.createDirectories(path.parent!!)
		fileSystem.write(path) { write("abc".encodeToByteArray()) }
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", contentHashOfFile(fileSystem, path), "the published SHA-256 vector for abc")
		assertNull(contentHashOfFile(fileSystem, "/art/missing.psd".toPath()))
	}
}