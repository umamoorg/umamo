package org.umamo.storage

import io.github.vinceglb.filekit.PlatformFile
import java.io.File

/**
 * Desktop actual: a stored path is an ordinary filesystem path.
 *
 * @param String path The stored path.
 * @return PlatformFile The reconstructed file handle.
 */
actual fun platformFileFromSavedPath(path: String): PlatformFile = PlatformFile(File(path))
