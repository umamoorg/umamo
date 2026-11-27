package org.umamo.editor.desktop

import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import org.umamo.storage.SessionLogFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The second process [SessionLogLockTest] runs: marks the session log its one argument names, says whether it
 * could on stdout, and keeps the mark until its stdin closes.
 */
object SessionLogLockHolder {
	/**
	 * Marks the log and waits.
	 *
	 * @param Array<String> args The session log's path.
	 */
	@JvmStatic
	fun main(args: Array<String>) {
		val release = FileChannelSessionLogLocks.holdForSession(args.single().toPath())
		println(
			if (release == null) {
				"not held"
			} else {
				"held"
			},
		)
		System.out.flush()
		System.`in`.readBytes()
		release?.invoke()
	}
}

/**
 * Pins the session-log mark across two real processes, which is the only way to prove it: the lock belongs to
 * a process, so within one JVM a second attempt fails differently than another process's would.  A log another
 * process marks is reported held and survives pruning, the mark keeps no one from reading or appending to the
 * file, and the mark ends with the process that took it.
 */
class SessionLogLockTest {
	@Test
	fun aLogAnotherProcessMarksSurvivesPruningUntilThatProcessEnds() {
		val directory = Files.createTempDirectory("umamo-session-log-lock")

		try {
			val heldLog = directory.resolve("session-2026-09-24T01-00-00Z-0001.log")
			Files.writeString(heldLog, "# a running session's log\n")
			val javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
			val holder =
				ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), SessionLogLockHolder::class.java.name, heldLog.toString())
					.redirectErrorStream(true)
					.start()

			try {
				assertEquals("held", holder.inputStream.bufferedReader().readLine(), "the second process marks the log")
				assertTrue(FileChannelSessionLogLocks.isHeldElsewhere(heldLog.toOkioPath()))
				// The marked byte lies past the content, so reading and appending are untouched.
				assertTrue(Files.readString(heldLog).startsWith("# a running session's log"))
				Files.writeString(heldLog, "appended\n", StandardOpenOption.APPEND)

				val sessionLog = SessionLogFile.open(FileSystem.SYSTEM, directory.toOkioPath(), Instant.parse("2026-09-24T03:12:45Z"), keptSessions = 1, locks = FileChannelSessionLogLocks)
				sessionLog.close()

				assertTrue(Files.exists(heldLog), "pruning leaves the log another running session marks")
			} finally {
				holder.outputStream.close()

				if (!holder.waitFor(30, TimeUnit.SECONDS)) {
					holder.destroyForcibly()
				}
			}

			assertFalse(FileChannelSessionLogLocks.isHeldElsewhere(heldLog.toOkioPath()), "the mark ends with the process")
		} finally {
			directory.toFile().deleteRecursively()
		}
	}
}