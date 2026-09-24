package org.umamo.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The severity of a logged line.  The stdout transport prints every level identically; the in-app console
 * colors Warn and Error distinctly, and a file sink tags each line with it.
 */
enum class LogLevel {
	Info,
	Warn,
	Error,
}

/**
 * One retained log line: its severity and its already-composed message (for an Error with a cause the
 * message is the same "message: cause" text the terminal prints, so nothing is lost off the console).
 *
 * @property LogLevel level The severity the call site named.
 * @property String message The final, fully-composed message text.
 */
data class LogEntry(val level: LogLevel, val message: String)

/**
 * One logged line as a sink receives it: the retained entry's level and composed message, plus the moment
 * it was logged and the originating failure, whose stack trace the in-memory entry does not keep.
 *
 * @property Instant    timestamp When the line was logged.
 * @property LogLevel   level     The severity the call site named.
 * @property String     message   The fully-composed message, identical to the retained entry's.
 * @property Throwable? cause     The failure an error was logged with, or null.
 */
class LogRecord(
	val timestamp: Instant,
	val level: LogLevel,
	val message: String,
	val cause: Throwable?,
)

/**
 * A destination for logged lines beyond stdout and the in-memory buffer, such as the desktop's session log
 * file.  Called synchronously on the logging thread, from any thread, so a line is as durable as the sink
 * makes it by the time the log call returns.
 */
fun interface LogSink {
	/**
	 * Takes one logged line.  Never throws: a sink that fails disables itself rather than turning a log call
	 * into a crash.
	 *
	 * @param LogRecord record The line to take.
	 */
	fun write(record: LogRecord)
}

/**
 * The app's minimal diagnostic log.  A seam, not a framework: call sites name a severity instead of
 * hardcoding the transport, so routing can change in one place.  Every line goes to stdout with the
 * established "[Umamo]" prefix, to a bounded in-memory buffer ([entries]) the editor's Logs panel collects -
 * so a user who launched without a terminal can still read the output - and to every sink attached with
 * [addSink], which is how the desktop keeps a log file per session.
 *
 * Lives in :storage because it is the bottom-most shared module every target already depends on;
 * logging is platform plumbing in the same sense as config directories and file IO.
 *
 * This is for diagnostics only.  Anything the user must see goes through a UI surface (a notice or
 * a dialog), never only through this log.
 */
object UmamoLog {
	/**
	 * The retained log lines, oldest first, capped at [MAX_LOG_ENTRIES] (oldest dropped past the cap).
	 * A StateFlow so the Logs panel recomposes as lines arrive; writes are lock-free and thread-safe,
	 * so any thread (the GL thread, a coroutine dispatcher) may log.
	 */
	val entries: StateFlow<List<LogEntry>>
		get() = mutableEntries.asStateFlow()

	private val mutableEntries = MutableStateFlow<List<LogEntry>>(emptyList())

	/** The attached sinks, replaced whole on attach and detach so a line in flight reads one consistent list. */
	private val sinks = MutableStateFlow<List<LogSink>>(emptyList())

	/**
	 * Attaches a sink that receives every line logged from now on.
	 *
	 * @param LogSink sink The sink to attach.
	 * @return Function Detaches the sink again.
	 */
	fun addSink(sink: LogSink): () -> Unit {
		sinks.update { attached -> attached + sink }
		return { sinks.update { attached -> attached - sink } }
	}

	/**
	 * Logs a routine status line (startup info, a completed save/export).
	 *
	 * @param String message The already-formatted message.
	 */
	fun info(message: String) {
		emit(LogLevel.Info, message, null)
	}

	/**
	 * Logs an unexpected-but-recovered condition (a rejected file, a refused platform call).
	 *
	 * @param String message The already-formatted message.
	 */
	fun warn(message: String) {
		emit(LogLevel.Warn, message, null)
	}

	/**
	 * Logs a failure worth investigating, with the throwable's message appended when present.
	 *
	 * @param String message The already-formatted message.
	 * @param Throwable? cause The originating failure, or null when there is none.
	 */
	fun error(message: String, cause: Throwable? = null) {
		// Compose once so the terminal and the retained buffer show the cause identically.
		val composed =
			if (cause != null) {
				"$message: ${cause.message}"
			} else {
				message
			}
		emit(LogLevel.Error, composed, cause)
	}

	/**
	 * Sends one line to every transport.  The sinks go first: a crash handler's line is on disk before
	 * anything else this call does can fail.
	 *
	 * @param LogLevel   level    The line's severity.
	 * @param String     composed The fully-composed message text.
	 * @param Throwable? cause    The failure an error was logged with, or null.
	 */
	private fun emit(level: LogLevel, composed: String, cause: Throwable?) {
		val attached = sinks.value
		if (attached.isNotEmpty()) {
			val logged = LogRecord(Clock.System.now(), level, composed, cause)
			for (sink in attached) {
				sink.write(logged)
			}
		}
		println("[Umamo] $composed")
		record(level, composed)
	}

	/**
	 * Appends one entry to the retained buffer, dropping the oldest lines past the cap.  update{} is a
	 * lock-free compare-and-set loop (safe from any thread) and its transform is pure, so a retry under
	 * contention is harmless.
	 *
	 * @param LogLevel level   The entry's severity.
	 * @param String   message The fully-composed message text.
	 */
	private fun record(level: LogLevel, message: String) {
		mutableEntries.update { existing -> (existing + LogEntry(level, message)).takeLast(MAX_LOG_ENTRIES) }
	}

	/**
	 * Empties the retained buffer.  Test-only: [UmamoLog] is an app-lifetime singleton, so tests reset it
	 * between cases to avoid order coupling.  Not a user-facing clear.
	 */
	internal fun clear() {
		mutableEntries.value = emptyList()
	}
}

/**
 * The most recent log lines kept in memory.  A whole session's diagnostics fit comfortably; the log is
 * low-traffic (startup, GL init, file-IO outcomes), so this is generous headroom, not a tight ring.
 */
private const val MAX_LOG_ENTRIES = 2000