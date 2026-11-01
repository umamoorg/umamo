package org.umamo.storage

import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random

/**
 * Writes [bytes] over [target] without ever leaving a half-written file at [target].
 *
 * The bytes go to a temporary sibling first, and only a complete temporary is moved over the target -
 * one rename, atomic only within one file system, hence a sibling rather than a system temp directory.
 * A process killed mid-write leaves its temporary behind; the next save of the same file sweeps any
 * such leftover, and the prefix is this function's own, so nothing else is ever touched.
 *
 * @param FileSystem fileSystem The file system holding [target]; a parameter so tests can run against
 *                              okio's in-memory one.
 * @param Path       target     The file to replace, or create.
 * @param ByteArray  bytes      The complete contents to write.
 * @throws IOException When the temporary cannot be written or the move fails.
 */
internal fun writeReplacing(fileSystem: FileSystem, target: Path, bytes: ByteArray) {
	val directory = target.parent ?: throw IOException("$target has no directory to write a temporary in")
	val temporaryPrefix = ".${target.name}.tmp-"

	for (sibling in fileSystem.listOrNull(directory).orEmpty()) {
		if (sibling.name.startsWith(temporaryPrefix)) {
			try {
				fileSystem.delete(sibling, mustExist = false)
			} catch (_: IOException) {
				// A leftover that will not delete is not a reason to fail the save that found it.
			}
		}
	}

	val temporary = directory / "$temporaryPrefix${Random.nextLong().toULong().toString(16)}"

	try {
		fileSystem.write(temporary) { write(bytes) }
		fileSystem.atomicMove(temporary, target)
	} catch (failure: IOException) {
		fileSystem.delete(temporary, mustExist = false)

		throw failure
	}
}