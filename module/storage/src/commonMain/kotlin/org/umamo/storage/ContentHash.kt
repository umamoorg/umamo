package org.umamo.storage

import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.buffer

/*
 * The content hash a document records for a linked file - the whole-file digest the re-import watcher
 * compares a save against, so a save that changed no byte costs nothing and a file edited while the
 * document was closed is noticed on open.  One algorithm, named here and nowhere else: SHA-256 as
 * lowercase hex, which is what the native format persists (docs/plan/uma-format.md D6).
 */

/**
 * The content hash of [bytes].
 *
 * @param ByteArray bytes The file's bytes.
 * @return String The digest as lowercase hex.
 */
fun contentHashOf(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

/**
 * The content hash of the file at [path], streamed rather than loaded - an artwork file can run to
 * hundreds of megabytes, and the watcher hashes it on every settled save.
 *
 * @param FileSystem fileSystem The file system to read through.
 * @param Path       path       The file.
 * @return String? The digest as lowercase hex, or null when the file cannot be read (missing, being
 *   written, or refused).
 */
fun contentHashOfFile(fileSystem: FileSystem, path: Path): String? =
	runCatching {
		HashingSource.sha256(fileSystem.source(path)).use { hashing ->
			hashing.buffer().use { source -> source.readAll(blackholeSink()) }
			hashing.hash.hex()
		}
	}.getOrNull()