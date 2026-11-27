package org.umamo.storage

import okio.Path

/**
 * How a running session marks its log as in use, so another session's pruning leaves it alone.
 *
 * Pruning cannot tell a finished session's log from a running one's by trying to delete it: only Windows
 * refuses to delete a file another process holds open, while Linux and macOS unlink it and the running
 * session goes on writing to a file no one can find.  A host that can take an operating-system lock supplies
 * one ([SessionLogFile.open]); the default, [None], marks nothing and leaves the delete to decide.
 */
interface SessionLogLocks {
	/**
	 * Marks [path] as this session's until the returned release runs.  Never throws.
	 *
	 * @param Path path The session log this session writes.
	 * @return Function? The release, or null when no mark could be taken; the session runs on unmarked.
	 */
	fun holdForSession(path: Path): (() -> Unit)?

	/**
	 * Whether another running session holds [path].  Never throws: a log whose state cannot be read counts as
	 * not held, so the delete is attempted as it would be without a lock.
	 *
	 * @param Path path A session log another session may be writing.
	 * @return Boolean True when another session holds it.
	 */
	fun isHeldElsewhere(path: Path): Boolean

	companion object {
		/** No marks at all: pruning leaves a log only when the file system refuses to delete it. */
		val None: SessionLogLocks =
			object : SessionLogLocks {
				override fun holdForSession(path: Path): (() -> Unit)? = null

				override fun isHeldElsewhere(path: Path): Boolean = false
			}
	}
}