package org.umamo.storage

import android.net.Uri
import io.github.vinceglb.filekit.PlatformFile
import java.io.File

/**
 * Android actual: a Storage Access Framework pick stores a `content://` URI string (there is no real
 * filesystem path behind it), so it routes back through a Uri; anything else is a plain file path.
 * Reopening a content URI across process restarts may still be refused until persistable URI
 * permissions are taken — callers treat a failed open as a soft miss.
 *
 * @param String path The stored path or URI string.
 * @return PlatformFile The reconstructed file handle.
 */
actual fun platformFileFromSavedPath(path: String): PlatformFile =
	if (path.startsWith("content://")) {
		PlatformFile(Uri.parse(path))
	} else {
		PlatformFile(File(path))
	}
