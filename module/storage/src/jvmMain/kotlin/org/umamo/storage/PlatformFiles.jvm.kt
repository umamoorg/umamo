package org.umamo.storage

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

/**
 * Desktop actual: a stored path is an ordinary filesystem path.
 *
 * @param String path The stored path.
 * @return PlatformFile The reconstructed file handle.
 */
actual fun platformFileFromSavedPath(path: String): PlatformFile = PlatformFile(File(path))

/**
 * Desktop actual: a temporary sibling moved atomically over the target, off the caller's thread.
 *
 * @param ByteArray bytes The complete contents.
 */
actual suspend fun PlatformFile.writeReplacing(bytes: ByteArray) {
	val target = absolutePath().toPath()
	withContext(Dispatchers.IO) { writeReplacing(FileSystem.SYSTEM, target, bytes) }
}