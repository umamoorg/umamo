package org.umamo.storage

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.BufferedSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.utf8Size
import kotlin.random.Random
import kotlin.time.Instant

/**
 * One session's log on disk: every line [UmamoLog] takes while the session runs, timestamped, level-tagged,
 * and with an error's full stack trace, which the in-memory buffer drops.  It is what a rigger attaches to a
 * bug report, including after a crash, so each line is written and flushed before [write] returns and the
 * file stays open for the whole session - a crash handler never has to open a file.
 *
 * Each session writes its own file and [open] prunes the oldest, so two editor processes (a second launch
 * opens a second window) never share one.  A size cap bounds a runaway session: past the soft cap only
 * errors are still written, and past the hard cap nothing is, each announced by one marker line.  The caps
 * leave room for errors because the failure after a flood of warnings is the line a report needs.
 *
 * Writes are serialized by a lock rather than handed to a writer coroutine: a line handed off would still be
 * in flight when a crash ends the process.
 *
 * @property Path path The file this session writes.
 */
class SessionLogFile private constructor(
	val path: Path,
	private var sink: BufferedSink?,
	private val softCapBytes: Long,
	private val hardCapBytes: Long,
	private var writtenBytes: Long,
) : LogSink {
	private val lock = SynchronizedObject()

	/** Whether the soft cap's marker line has been written, after which Info and Warn lines are dropped. */
	private var softCapReached = false

	/** Whether the hard cap's marker line has been written, after which every line is dropped. */
	private var hardCapReached = false

	/**
	 * Appends one line and flushes it, unless a cap has closed its level.  Formatting happens outside the
	 * lock so a long stack trace never holds up another thread's line.
	 *
	 * @param LogRecord record The line to write.
	 */
	override fun write(record: LogRecord) {
		val text = formatLogRecord(record)
		val byteCount = text.utf8Size()
		synchronized(lock) {
			val openSink = sink ?: return
			if (hardCapReached || (softCapReached && record.level != LogLevel.Error)) {
				return
			}
			val cap =
				if (record.level == LogLevel.Error) {
					hardCapBytes
				} else {
					softCapBytes
				}
			if (writtenBytes + byteCount > cap) {
				if (record.level == LogLevel.Error) {
					hardCapReached = true
					appendLocked(openSink, "# The session log reached $hardCapBytes bytes; nothing further is written.\n")
				} else {
					softCapReached = true
					appendLocked(openSink, "# The session log reached $softCapBytes bytes; only errors are written from here.\n")
				}
				return
			}
			appendLocked(openSink, text)
		}
	}

	/**
	 * Closes the file.  Lines logged afterwards are dropped.
	 */
	fun close() {
		synchronized(lock) {
			val openSink = sink ?: return
			sink = null
			try {
				openSink.close()
			} catch (_: IOException) {
				// Every line was already flushed; a failed close loses nothing.
			}
		}
	}

	/**
	 * Writes and flushes [text], counting its bytes; a failure disables the file.  The caller holds the lock.
	 *
	 * @param BufferedSink openSink The open file.
	 * @param String       text     The text to append.
	 */
	private fun appendLocked(openSink: BufferedSink, text: String) {
		try {
			openSink.writeUtf8(text)
			openSink.flush()
			writtenBytes += text.utf8Size()
		} catch (failure: IOException) {
			sink = null
			try {
				openSink.close()
			} catch (_: IOException) {
				// Already failing; the report below is the one that matters.
			}
			// Straight to stdout: reporting through UmamoLog would call back into this sink.
			println("[Umamo] the session log at $path stopped: ${failure.message}")
		}
	}

	companion object {
		/**
		 * Starts a new session log in [directory], after pruning the oldest ones so that, with this one,
		 * [keptSessions] remain.  Only files named like a session log are ever pruned; a file another process
		 * still holds open (which Windows refuses to delete) is left for a later session to prune.
		 *
		 * @param FileSystem fileSystem   The file system holding [directory].
		 * @param Path       directory    The logs directory, created if missing.
		 * @param Instant    startedAt    When the session started; it names the file.
		 * @param Int        keptSessions How many session logs to keep, this one included.
		 * @param Long       softCapBytes The size past which only errors are written.
		 * @param Long       hardCapBytes The size past which nothing is written.
		 * @param Random     random       The source of the name's suffix.
		 * @return SessionLogFile The open session log.
		 * @throws IOException When the directory or the file cannot be created.
		 */
		fun open(
			fileSystem: FileSystem,
			directory: Path,
			startedAt: Instant,
			keptSessions: Int = SESSION_LOGS_KEPT,
			softCapBytes: Long = SESSION_LOG_SOFT_CAP_BYTES,
			hardCapBytes: Long = SESSION_LOG_HARD_CAP_BYTES,
			random: Random = Random.Default,
		): SessionLogFile {
			fileSystem.createDirectories(directory)
			pruneSessionLogs(fileSystem, directory, keptSessions - 1)
			var attempt = 0
			while (true) {
				val path = directory / sessionLogFileName(startedAt, random.nextInt(SUFFIX_RANGE).toString(16).padStart(SUFFIX_DIGITS, '0'))
				val sink =
					try {
						// mustCreate: two processes started in the same second never append to one file.
						fileSystem.sink(path, mustCreate = true).buffer()
					} catch (failure: IOException) {
						attempt += 1
						if (attempt >= MAX_NAME_ATTEMPTS || !fileSystem.exists(path)) {
							throw failure
						}
						continue
					}
				val header = "# Umamo session log, started ${formatLogTimestamp(startedAt)} (UTC timestamps)\n"
				try {
					sink.writeUtf8(header)
					sink.flush()
				} catch (failure: IOException) {
					sink.close()
					throw failure
				}
				return SessionLogFile(path, sink, softCapBytes, hardCapBytes, header.utf8Size())
			}
		}
	}
}

/**
 * The file name of a session log: the start instant to the second in UTC, colons made file-name safe, then a
 * suffix that keeps two sessions started in the same second apart.  Names sort in the order the sessions
 * started, which is what pruning relies on.
 *
 * @param Instant startedAt When the session started.
 * @param String  suffix    The disambiguating suffix.
 * @return String The file name, e.g. `session-2026-09-24T03-12-45Z-7f3a.log`.
 */
internal fun sessionLogFileName(startedAt: Instant, suffix: String): String {
	val secondStamp = startedAt.toString().take(SECOND_PRECISION_LENGTH).replace(':', '-')
	return "$SESSION_LOG_PREFIX${secondStamp}Z-$suffix$SESSION_LOG_EXTENSION"
}

/**
 * One record as the session log writes it: a timestamped, level-tagged line, followed for an error with a
 * cause by the cause's full stack trace.
 *
 * @param LogRecord record The record to format.
 * @return String The text to append, ending in a line break.
 */
internal fun formatLogRecord(record: LogRecord): String =
	buildString {
		append(formatLogTimestamp(record.timestamp))
		append(' ')
		append(record.level.name.uppercase().padEnd(LEVEL_COLUMN_WIDTH))
		append(' ')
		append(record.message)
		append('\n')
		record.cause?.let { cause ->
			append(cause.stackTraceToString())
			if (!endsWith('\n')) {
				append('\n')
			}
		}
	}

/**
 * An instant as the session log prints it: ISO-8601 in UTC to the millisecond, a fixed width so the columns
 * line up.
 *
 * @param Instant instant The instant to format.
 * @return String The timestamp, e.g. `2026-09-24T03:12:45.120Z`.
 */
internal fun formatLogTimestamp(instant: Instant): String {
	val milliseconds = (instant.nanosecondsOfSecond / NANOSECONDS_PER_MILLISECOND).toString().padStart(3, '0')
	return "${instant.toString().take(SECOND_PRECISION_LENGTH)}.${milliseconds}Z"
}

/**
 * Deletes the oldest session logs in [directory] until at most [keepNewest] remain.  A file that will not
 * delete is left alone: it is most likely another running session's.
 *
 * @param FileSystem fileSystem The file system holding [directory].
 * @param Path       directory  The logs directory.
 * @param Int        keepNewest How many of the newest session logs to keep.
 */
private fun pruneSessionLogs(fileSystem: FileSystem, directory: Path, keepNewest: Int) {
	val sessionLogs =
		fileSystem.listOrNull(directory).orEmpty()
			.filter { candidate -> candidate.name.startsWith(SESSION_LOG_PREFIX) && candidate.name.endsWith(SESSION_LOG_EXTENSION) }
			.sortedByDescending { sessionLog -> sessionLog.name }
	for (expired in sessionLogs.drop(keepNewest.coerceAtLeast(0))) {
		try {
			fileSystem.delete(expired, mustExist = false)
		} catch (_: IOException) {
			// Still open in another session; a later session prunes it.
		}
	}
}

/** How many session logs are kept, the current one included. */
internal const val SESSION_LOGS_KEPT = 10

/** The size past which a session log writes only errors: generous for a low-traffic log. */
internal const val SESSION_LOG_SOFT_CAP_BYTES = 2L * 1024 * 1024

/** The size past which a session log writes nothing more. */
internal const val SESSION_LOG_HARD_CAP_BYTES = 3L * 1024 * 1024

private const val SESSION_LOG_PREFIX = "session-"
private const val SESSION_LOG_EXTENSION = ".log"

/** `YYYY-MM-DDTHH:MM:SS`, the part of an ISO-8601 instant down to the second. */
private const val SECOND_PRECISION_LENGTH = 19

/** Wide enough for the longest level name, ERROR, so messages start in one column. */
private const val LEVEL_COLUMN_WIDTH = 5

private const val NANOSECONDS_PER_MILLISECOND = 1_000_000

/** Four hex digits of suffix. */
private const val SUFFIX_RANGE = 0x10000
private const val SUFFIX_DIGITS = 4

/** How many names [SessionLogFile.open] tries before giving up on a directory where every one is taken. */
private const val MAX_NAME_ATTEMPTS = 8