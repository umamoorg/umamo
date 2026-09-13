package org.umamo.storage

import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.buffer

/**
 * The content hash of the file at [path], streamed rather than loaded - an artwork file can run to
 * hundreds of megabytes, and the watcher hashes it on every settled save.  The same digest as
 * :format's contentHashOf (SHA-256 as lowercase hex), which is where the algorithm is named.
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