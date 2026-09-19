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

/**
 * Writes [bytes] as this file's complete contents, never leaving a half-written file where it can be
 * avoided.
 *
 * A real path takes the temporary-then-atomic-move route ([writeReplacing]), so the file on disk is either
 * the old one or the new one.  An Android `content://` uri cannot be renamed over, so it is written in
 * place, and only once the bytes are complete in memory - the one case where a failure mid-write can
 * leave a partial file.
 *
 * @param ByteArray bytes The complete contents.
 * @throws IOException When the write fails; the caller reports it and marks nothing saved.
 */
expect suspend fun PlatformFile.writeReplacing(bytes: ByteArray)