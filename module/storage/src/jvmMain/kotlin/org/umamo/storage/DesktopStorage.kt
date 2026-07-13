package org.umamo.storage

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

/**
 * Builds the desktop [AppStorage], resolving per-OS config/data directories under [applicationName]:
 * Linux follows XDG (`~/.config`, `~/.local/share`), macOS uses `~/Library/Application Support`, Windows
 * uses `%APPDATA%` / `%LOCALAPPDATA%`. IO goes through the real filesystem (`FileSystem.SYSTEM`).
 *
 * @param String applicationName The app's directory name (e.g. "umamo").
 * @return AppStorage The desktop storage.
 */
fun desktopAppStorage(applicationName: String): AppStorage =
	OkioAppStorage(
		FileSystem.SYSTEM,
		configDirectory = desktopBaseDirectory(config = true).toOkioPath() / applicationName,
		dataDirectory = desktopBaseDirectory(config = false).toOkioPath() / applicationName,
	)

/**
 * Resolves the OS's base config or data directory (without the app name).
 *
 * @param Boolean config True for the config base, false for the data base.
 * @return File The base directory.
 */
private fun desktopBaseDirectory(config: Boolean): File {
	val home = System.getProperty("user.home") ?: "."
	val os = System.getProperty("os.name").orEmpty().lowercase()
	return when {
		os.contains("win") -> {
			val variable = if (config) "APPDATA" else "LOCALAPPDATA"
			val fallback = if (config) "$home\\AppData\\Roaming" else "$home\\AppData\\Local"
			File(System.getenv(variable) ?: fallback)
		}
		os.contains("mac") || os.contains("darwin") -> File("$home/Library/Application Support")
		else -> {
			val variable = if (config) "XDG_CONFIG_HOME" else "XDG_DATA_HOME"
			val fallback = if (config) "$home/.config" else "$home/.local/share"
			File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: fallback)
		}
	}
}

/** Converts a `java.io.File` to an okio [Path] via its absolute path. */
private fun File.toOkioPath(): Path = absolutePath.toPath()
