package org.umamo.storage

import okio.FileSystem
import okio.Path

/**
 * The app's file foundation: where its config/data live (per-OS) and read/write over those locations.
 * The platform factories (`desktopAppStorage`, `androidAppStorage`) resolve the directories; everything
 * else is shared. Backed by okio so one API covers desktop and Android - Kotlin's stdlib has no common
 * `File` type, and `java.io.File` is JVM-only.
 */
interface AppStorage {
	/** Per-user config directory (small JSON: settings, keymaps, layout). Parents created on write. */
	val configDirectory: Path

	/** Per-user data directory (larger app data, caches). */
	val dataDirectory: Path

	/**
	 * Reads [path] as UTF-8 text.
	 *
	 * @param Path path The file to read.
	 * @return String? The contents, or null if the file does not exist.
	 */
	fun readText(path: Path): String?

	/**
	 * Writes [text] to [path] as UTF-8, creating parent directories as needed.
	 *
	 * @param Path   path The destination file.
	 * @param String text The contents to write.
	 */
	fun writeText(path: Path, text: String)

	/**
	 * @param Path path The path to test.
	 * @return Boolean Whether [path] exists.
	 */
	fun exists(path: Path): Boolean
}

/**
 * okio-backed [AppStorage]: directories come from the platform factory; IO goes through the supplied
 * [fileSystem] - the real one on a device, or okio's `FakeFileSystem` in tests (so settings logic is
 * testable with no real disk).
 *
 * @property FileSystem fileSystem      The filesystem to read/write through.
 * @property Path       configDirectory Resolved config directory.
 * @property Path       dataDirectory   Resolved data directory.
 */
class OkioAppStorage(
	private val fileSystem: FileSystem,
	override val configDirectory: Path,
	override val dataDirectory: Path,
) : AppStorage {
	override fun readText(path: Path): String? =
		if (fileSystem.exists(path)) {
			fileSystem.read(path) { readUtf8() }
		} else {
			null
		}

	override fun writeText(path: Path, text: String) {
		path.parent?.let { fileSystem.createDirectories(it) }
		fileSystem.write(path) { writeUtf8(text) }
	}

	override fun exists(path: Path): Boolean = fileSystem.exists(path)
}
