package org.umamo.edit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The session's notice channel: the current transient user notice, and the serial that tells a repeat of the
 * same message from the message it repeats.  Off the undo history and the change bus by design - a notice is
 * momentary feedback, never document state.  Built before the session so the tool latches can post through
 * it; [EditorSession] exposes the flow and the two operations unchanged.
 */
internal class SessionNotices {
	private val mutableNotice = MutableStateFlow<Notice?>(null)

	/** The current transient user notice, or null when none is showing (see [EditorSession.notice]). */
	val notice: StateFlow<Notice?> = mutableNotice.asStateFlow()

	// Monotonic id stamped on each notice so an identical repeated message is still a distinct event the shell
	// can re-time.  Not a clock (unavailable here) - just a counter.
	private var noticeSerial: Long = 0L

	/**
	 * Emits a transient user notice; it stays current until dismissed via [clear] or replaced by a newer one.
	 *
	 * @param String messageKey The stable notice key the UI layer resolves to a localized message.
	 * @param NoticePlacement placement Where the shell surfaces the notice.
	 * @param List<String> arguments The values the message formats in, in its placeholder order.
	 */
	fun emit(messageKey: String, placement: NoticePlacement = NoticePlacement.StatusBar, arguments: List<String> = emptyList()) {
		noticeSerial += 1
		mutableNotice.value = Notice(messageKey, noticeSerial, placement, arguments)
	}

	/**
	 * Dismisses the notice with the given [serial], but only if it is still the current one - so a dismissal
	 * timer for an older notice never clears a newer message that arrived in the meantime.
	 *
	 * @param Long serial The serial of the notice to dismiss (from [Notice.serial]).
	 */
	fun clear(serial: Long) {
		if (mutableNotice.value?.serial == serial) {
			mutableNotice.value = null
		}
	}
}