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
	val temporary = directory / ".${target.name}.tmp-${Random.nextLong().toULong().toString(16)}"
	try {
		fileSystem.write(temporary) { write(bytes) }
		fileSystem.atomicMove(temporary, target)
	} catch (failure: IOException) {
		fileSystem.delete(temporary, mustExist = false)
		throw failure
	}
}