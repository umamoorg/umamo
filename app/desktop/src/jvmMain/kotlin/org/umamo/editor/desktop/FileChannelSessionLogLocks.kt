package org.umamo.editor.desktop

import okio.Path
import org.umamo.storage.SessionLogLocks
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/**
 * Marks a running session's log with an operating-system lock on one byte far past anything the log will hold.
 *
 * The locked byte lies past the content on purpose.  Windows locks are mandatory for the range they cover, so a
 * lock over the text would fail the session's own writes (they go through a different handle) and block a
 * text editor opened from Help > Open Log Folder; a byte no one reads or writes blocks nothing.  The lock goes
 * away with the process however it ends, so a crashed session leaves no mark behind and no file to clean up.
 *
 * On Linux and macOS the lock is a POSIX record lock: it belongs to the process, and closing ANY descriptor the
 * process has on the file drops it.  So nothing else in the editor may open a running session's own log - the
 * pruning probe runs before the session's file exists, and exporting the log writes from the in-memory buffer.
 */
internal object FileChannelSessionLogLocks : SessionLogLocks {
	/** The locked byte's offset: 1 TiB, far past the session log's 3 MiB hard cap. */
	private const val MARK_OFFSET = 1L shl 40

	/**
	 * Locks [path]'s mark byte for this session.  The channel stays open until the release runs; the returned
	 * release is held by the session log for the whole session, so the channel is never collected early.
	 *
	 * @param Path path This session's log.
	 * @return Function? The release, or null when the lock could not be taken.
	 */
	override fun holdForSession(path: Path): (() -> Unit)? {
		val channel =
			try {
				FileChannel.open(path.toNioPath(), StandardOpenOption.WRITE)
			} catch (_: IOException) {
				return null
			}
		val mark =
			try {
				channel.tryLock(MARK_OFFSET, 1, false)
			} catch (_: IOException) {
				null
			} catch (_: OverlappingFileLockException) {
				null
			}

		if (mark == null) {
			closeQuietly(channel)

			return null
		}

		return {
			try {
				mark.release()
			} catch (_: IOException) {
				// Closing the channel below drops the lock regardless.
			}

			closeQuietly(channel)
		}
	}

	/**
	 * Whether another process holds [path]'s mark byte.  The probe takes the lock and gives it straight back when
	 * it is free, so it never keeps another session from marking its own log.
	 *
	 * @param Path path Another session's log.
	 * @return Boolean True when the mark is held.
	 */
	override fun isHeldElsewhere(path: Path): Boolean {
		val channel =
			try {
				FileChannel.open(path.toNioPath(), StandardOpenOption.WRITE)
			} catch (_: IOException) {
				return false
			}

		return try {
			val probe = channel.tryLock(MARK_OFFSET, 1, false)

			if (probe == null) {
				true
			} else {
				probe.release()
				false
			}
		} catch (_: OverlappingFileLockException) {
			// Held by this same process: only a test runs two sessions in one JVM.
			true
		} catch (_: IOException) {
			false
		} finally {
			closeQuietly(channel)
		}
	}

	/**
	 * Closes [channel], ignoring a failure: nothing is left to do about it.
	 *
	 * @param FileChannel channel The channel to close.
	 */
	private fun closeQuietly(channel: FileChannel) {
		try {
			channel.close()
		} catch (_: IOException) {
			// Nothing to recover.
		}
	}
}