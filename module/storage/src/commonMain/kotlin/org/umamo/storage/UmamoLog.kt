package org.umamo.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The severity of a logged line.  Nominal today (the stdout transport prints every level identically),
 * but retained on each entry so an in-app console can colour Warn and Error distinctly.
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
 * The app's minimal diagnostic log.  A seam, not a framework: call sites name a severity instead of
 * hardcoding the transport, so routing (a file via AppStorage, logcat, an in-app console) can change
 * in one place later.  The transport today is stdout with the established "[Umamo]" prefix, plus a
 * bounded in-memory buffer ([entries]) the editor's Logs panel collects - so a user who launched
 * without a terminal can still read the output.
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

	/**
	 * Logs a routine status line (startup info, a completed save/export).
	 *
	 * @param String message The already-formatted message.
	 */
	fun info(message: String) {
		println("[Umamo] $message")
		record(LogLevel.Info, message)
	}

	/**
	 * Logs an unexpected-but-recovered condition (a rejected file, a refused platform call).
	 *
	 * @param String message The already-formatted message.
	 */
	fun warn(message: String) {
		println("[Umamo] $message")
		record(LogLevel.Warn, message)
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
		println("[Umamo] $composed")
		record(LogLevel.Error, composed)
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
