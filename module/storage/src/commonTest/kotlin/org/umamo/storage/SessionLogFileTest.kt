package org.umamo.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Pins what a session log on disk promises a bug report: one file per session with a readable line per
 * record and an error's whole stack trace, a bounded number of files and a bounded size, and no failure of
 * its own ever reaching the code that logged.
 */
class SessionLogFileTest {
	private val directory = "/data/umamo/logs".toPath()
	private val startedAt = Instant.parse("2026-09-24T03:12:45.120Z")

	/**
	 * Reads a closed session log's lines.
	 *
	 * @param FakeFileSystem fileSystem The file system holding [path].
	 * @param Path           path       The session log.
	 * @return List The file's lines, without the trailing empty one.
	 */
	private fun linesOf(fileSystem: FakeFileSystem, path: Path): List<String> = fileSystem.read(path) { readUtf8() }.removeSuffix("\n").split("\n")

	/**
	 * A record at [startedAt].
	 *
	 * @param LogLevel   level   The record's level.
	 * @param String     message The record's message.
	 * @param Throwable? cause   The record's cause.
	 * @return LogRecord The record.
	 */
	private fun recordOf(level: LogLevel, message: String, cause: Throwable? = null): LogRecord = LogRecord(startedAt, level, message, cause)

	@Test
	fun theFileIsNamedByItsStartAndOpensWithAHeader() {
		val fileSystem = FakeFileSystem()

		val sessionLog = SessionLogFile.open(fileSystem, directory, startedAt)
		sessionLog.close()

		assertTrue(Regex("""session-2026-09-24T03-12-45Z-[0-9a-f]{4}\.log""").matches(sessionLog.path.name), sessionLog.path.name)
		assertEquals(directory, sessionLog.path.parent)
		assertEquals(listOf("# Umamo session log, started 2026-09-24T03:12:45.120Z (UTC timestamps)"), linesOf(fileSystem, sessionLog.path))
		fileSystem.checkNoOpenFiles()
	}

	@Test
	fun theNameCarriesTheSecondAndTheSuffix() {
		assertEquals("session-2026-09-24T03-12-45Z-7f3a.log", sessionLogFileName(startedAt, "7f3a"))
	}

	@Test
	fun timestampsAreFixedWidthToTheMillisecond() {
		assertEquals("2026-09-24T03:12:45.000Z", formatLogTimestamp(Instant.parse("2026-09-24T03:12:45Z")))
		assertEquals("2026-09-24T03:12:45.005Z", formatLogTimestamp(Instant.parse("2026-09-24T03:12:45.005999Z")))
	}

	@Test
	fun eachRecordIsATimestampedLevelTaggedLine() {
		val fileSystem = FakeFileSystem()
		val sessionLog = SessionLogFile.open(fileSystem, directory, startedAt)

		sessionLog.write(recordOf(LogLevel.Info, "started"))
		sessionLog.write(recordOf(LogLevel.Warn, "odd"))
		sessionLog.write(recordOf(LogLevel.Error, "failed"))
		sessionLog.close()

		assertEquals(
			listOf(
				"2026-09-24T03:12:45.120Z INFO  started",
				"2026-09-24T03:12:45.120Z WARN  odd",
				"2026-09-24T03:12:45.120Z ERROR failed",
			),
			linesOf(fileSystem, sessionLog.path).drop(1),
		)
		fileSystem.checkNoOpenFiles()
	}

	@Test
	fun anErrorWritesItsWholeStackTraceWithItsCause() {
		val fileSystem = FakeFileSystem()
		val sessionLog = SessionLogFile.open(fileSystem, directory, startedAt)
		val failure = RuntimeException("outer", IllegalStateException("inner"))

		sessionLog.write(recordOf(LogLevel.Error, "could not export: outer", failure))
		sessionLog.close()

		val lines = linesOf(fileSystem, sessionLog.path)
		assertEquals("2026-09-24T03:12:45.120Z ERROR could not export: outer", lines[1])
		// The trace the in-memory entry drops is all here, down to the cause.
		assertEquals(failure.stackTraceToString().removeSuffix("\n").split("\n"), lines.drop(2))
		assertTrue(lines.any { line -> line.startsWith("Caused by:") && line.contains("inner") })
		fileSystem.checkNoOpenFiles()
	}

	@Test
	fun openingPrunesTheOldestSessionLogsAndNothingElse() {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories(directory)
		val older = (1..12).map { day -> directory / sessionLogFileName(Instant.parse("2026-08-${day.toString().padStart(2, '0')}T10:00:00Z"), "0000") }
		for (path in older) {
			fileSystem.write(path) { writeUtf8("old") }
		}
		val unrelated = listOf(directory / "notes.txt", directory / "session-notes.txt", directory / "crash.log")
		for (path in unrelated) {
			fileSystem.write(path) { writeUtf8("keep") }
		}

		val sessionLog = SessionLogFile.open(fileSystem, directory, startedAt)
		sessionLog.close()

		val remaining = fileSystem.list(directory).toSet()
		// Ten session logs in all: the nine newest older ones and this session's own.
		assertEquals(older.takeLast(9).toSet() + sessionLog.path + unrelated, remaining)
		fileSystem.checkNoOpenFiles()
	}

	@Test
	fun aSessionLogThatWillNotDeleteDoesNotStopTheNextSession() {
		// FakeFileSystem refuses to delete an open file, as Windows does for another running session's log.
		val fileSystem = FakeFileSystem()
		val running = SessionLogFile.open(fileSystem, directory, Instant.parse("2026-09-24T01:00:00Z"))

		val sessionLog = SessionLogFile.open(fileSystem, directory, startedAt, keptSessions = 1)
		sessionLog.close()
		running.close()

		assertEquals(setOf(running.path, sessionLog.path), fileSystem.list(directory).toSet())
		fileSystem.checkNoOpenFiles()
	}

	@Test
	fun twoSessionsStartedAtOneInstantWriteTwoFiles() {
		val fileSystem = FakeFileSystem()
		// Identical seeds draw the same first suffix, the collision two processes could meet by chance.
		val first = SessionLogFile.open(fileSystem, directory, startedAt, random = Random(7))
		val second = SessionLogFile.open(fileSystem, directory, startedAt, random = Random(7))
		first.write(recordOf(LogLevel.Info, "first"))
		second.write(recordOf(LogLevel.Info, "second"))
		first.close()
		second.close()

		assertNotEquals(first.path, second.path)
		assertEquals("2026-09-24T03:12:45.120Z INFO  first", linesOf(fileSystem, first.path).last())
		assertEquals("2026-09-24T03:12:45.120Z INFO  second", linesOf(fileSystem, second.path).last())
		fileSystem.checkNoOpenFiles()
	}

	@Test
	fun theSoftCapKeepsErrorsAndTheHardCapStopsEverything() {
		val fileSystem = FakeFileSystem()
		val sessionLog = SessionLogFile.open(fileSystem, directory, startedAt, softCapBytes = 300, hardCapBytes = 500)

		repeat(20) { lineIndex -> sessionLog.write(recordOf(LogLevel.Info, "routine $lineIndex")) }
		repeat(20) { lineIndex -> sessionLog.write(recordOf(LogLevel.Error, "failure $lineIndex")) }
		sessionLog.write(recordOf(LogLevel.Info, "after the hard cap"))
		sessionLog.close()

		val lines = linesOf(fileSystem, sessionLog.path)
		val softMarker = lines.indexOfFirst { line -> line.contains("only errors are written") }
		val hardMarker = lines.indexOfFirst { line -> line.contains("nothing further is written") }
		assertTrue(softMarker > 1, "some routine lines land before the soft cap")
		assertEquals(1, lines.count { line -> line.contains("only errors are written") })
		assertTrue(lines.drop(softMarker + 1).none { line -> line.contains(" INFO ") }, "no Info line after the soft cap")
		assertTrue(lines.subList(softMarker + 1, hardMarker).isNotEmpty() && lines.subList(softMarker + 1, hardMarker).all { line -> line.contains(" ERROR ") }, "errors still land between the caps")
		assertEquals(lines.lastIndex, hardMarker, "the hard cap's marker is the last line")
		assertTrue(fileSystem.metadata(sessionLog.path).size!! <= 500 + lines.last().length + 1)
		fileSystem.checkNoOpenFiles()
	}

	@Test
	fun aFailingDiskStopsTheLogWithoutThrowing() {
		val disk = FakeFileSystem()
		var diskFull = false
		val failing =
			object : ForwardingFileSystem(disk) {
				override fun sink(file: Path, mustCreate: Boolean): Sink {
					val sink = super.sink(file, mustCreate)
					return object : ForwardingSink(sink) {
						override fun write(source: Buffer, byteCount: Long) {
							if (diskFull) {
								throw IOException("no space left on device")
							}
							super.write(source, byteCount)
						}
					}
				}
			}
		val sessionLog = SessionLogFile.open(failing, directory, startedAt)

		diskFull = true
		sessionLog.write(recordOf(LogLevel.Error, "lost"))
		diskFull = false
		sessionLog.write(recordOf(LogLevel.Info, "also dropped: the log stopped at the first failure"))
		sessionLog.close()

		assertEquals(1, linesOf(disk, sessionLog.path).size, "only the header made it")
		disk.checkNoOpenFiles()
	}

	@Test
	fun concurrentWritersNeverInterleaveALine() =
		runTest {
			val fileSystem = FakeFileSystem()
			val sessionLog = SessionLogFile.open(fileSystem, directory, startedAt)

			withContext(Dispatchers.Default) {
				repeat(8) { writerIndex ->
					launch {
						repeat(200) { lineIndex -> sessionLog.write(recordOf(LogLevel.Info, "writer $writerIndex line $lineIndex")) }
					}
				}
			}
			sessionLog.close()

			val lines = linesOf(fileSystem, sessionLog.path).drop(1)
			assertEquals(8 * 200, lines.size)
			val whole = Regex("""2026-09-24T03:12:45\.120Z INFO  writer \d line \d+""")
			assertTrue(lines.all { line -> whole.matches(line) }, "every line is whole")
			assertEquals(8 * 200, lines.toSet().size, "every line landed exactly once")
			fileSystem.checkNoOpenFiles()
		}
}