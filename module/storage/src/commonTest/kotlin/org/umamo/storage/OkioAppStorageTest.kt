package org.umamo.storage

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-tests [OkioAppStorage] over okio's in-memory FakeFileSystem - the persistence foundation
 * settings/keymaps/layout all write through, exercised with no real disk.
 */
class OkioAppStorageTest {
	private fun storage(fileSystem: FakeFileSystem): OkioAppStorage =
		OkioAppStorage(
			fileSystem = fileSystem,
			configDirectory = "/config".toPath(),
			dataDirectory = "/data".toPath(),
		)

	@Test
	fun writeThenReadRoundTripsUtf8Text() {
		val storage = storage(FakeFileSystem())
		val path = storage.configDirectory / "settings.json"

		storage.writeText(path, "{\"theme\":\"dark\", \"言語\":\"日本語\"}")

		assertEquals("{\"theme\":\"dark\", \"言語\":\"日本語\"}", storage.readText(path))
	}

	@Test
	fun writeCreatesMissingParentDirectories() {
		val fileSystem = FakeFileSystem()
		val storage = storage(fileSystem)
		val nested = storage.configDirectory / "keymaps" / "presets" / "custom.json"

		storage.writeText(nested, "{}")

		assertTrue(fileSystem.exists(storage.configDirectory / "keymaps" / "presets"))
		assertEquals("{}", storage.readText(nested))
	}

	@Test
	fun readReturnsNullForAMissingFile() {
		val storage = storage(FakeFileSystem())

		assertNull(storage.readText(storage.configDirectory / "absent.json"))
	}

	@Test
	fun existsTracksTheWrittenFile() {
		val storage = storage(FakeFileSystem())
		val path = storage.dataDirectory / "recent.json"

		assertFalse(storage.exists(path))
		storage.writeText(path, "[]")
		assertTrue(storage.exists(path))
	}

	@Test
	fun rewritingAFileReplacesItsContents() {
		val storage = storage(FakeFileSystem())
		val path = storage.configDirectory / "settings.json"

		storage.writeText(path, "first")
		storage.writeText(path, "second")

		assertEquals("second", storage.readText(path))
	}
}
