package org.umamo.storage

import io.github.vinceglb.filekit.PlatformFile

/**
 * Rebuilds a [PlatformFile] from a path string previously stored from `PlatformFile.absolutePath()`
 * (e.g. the recent-files list). FileKit's `PlatformFile(path)` constructors are per-platform API —
 * not visible from shared code — and Android must additionally route SAF `content://` strings back
 * through a Uri, hence the seam.
 *
 * 保存済みパス文字列から PlatformFile を再構築する継ぎ目。Android は content:// を Uri 経由で戻す。
 *
 * @param String path The stored path or URI string.
 * @return PlatformFile The reconstructed file handle.
 */
expect fun platformFileFromSavedPath(path: String): PlatformFile
