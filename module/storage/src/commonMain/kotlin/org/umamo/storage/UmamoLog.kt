package org.umamo.storage

/**
 * The app's minimal diagnostic log.  A seam, not a framework: call sites name a severity instead of
 * hardcoding the transport, so routing (a file via AppStorage, logcat, an in-app console) can change
 * in one place later.  The transport today is stdout with the established "[Umamo]" prefix.
 *
 * Lives in :storage because it is the bottom-most shared module every target already depends on;
 * logging is platform plumbing in the same sense as config directories and file IO.
 *
 * This is for diagnostics only.  Anything the user must see goes through a UI surface (a notice or
 * a dialog), never only through this log.
 */
object UmamoLog {
	/**
	 * Logs a routine status line (startup info, a completed save/export).
	 *
	 * @param String message The already-formatted message.
	 */
	fun info(message: String) {
		println("[Umamo] $message")
	}

	/**
	 * Logs an unexpected-but-recovered condition (a rejected file, a refused platform call).
	 *
	 * @param String message The already-formatted message.
	 */
	fun warn(message: String) {
		println("[Umamo] $message")
	}

	/**
	 * Logs a failure worth investigating, with the throwable's message appended when present.
	 *
	 * @param String message The already-formatted message.
	 * @param Throwable? cause The originating failure, or null when there is none.
	 */
	fun error(message: String, cause: Throwable? = null) {
		if (cause != null) {
			println("[Umamo] $message: ${cause.message}")
		} else {
			println("[Umamo] $message")
		}
	}
}
