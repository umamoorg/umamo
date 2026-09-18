package org.umamo.storage

import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random

/**
 * Writes [bytes] over [target] without ever leaving a half-written file at [target].
 *
 * The bytes go to a temporary sibling in the target's directory first, and only a complete temporary is
 * moved over the target - one rename, which the file system makes atomic.  If anything fails before the
 * move, the target is untouched and the temporary is removed; if the move itself fails, the target is
 * still whatever it was.  A sibling rather than a system temp directory because a rename is atomic only
 * within one file system.
 *
 * A process killed mid-write (a crash, a power cut, a forced quit) cannot run that cleanup, and a
 * document-sized temporary is left beside the target.  The next save of the same file sweeps any such
 * leftover before writing its own, so it costs one save at most; the prefix is this function's own, so
 * nothing else is ever touched.
 *
 * The file system is a parameter so the rule tests against okio's in-memory file system; the platform
 * seam hands it the real one.
 *
 * @param FileSystem fileSystem The file system holding [target].
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