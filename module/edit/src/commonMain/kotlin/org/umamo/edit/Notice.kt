package org.umamo.edit

/**
 * Where the shell surfaces a [Notice]: the status-bar slot, or a transient label next to the pointer
 * (Blender's near-cursor "can't do this because" style, for feedback about a blocked viewport gesture).
 */
enum class NoticePlacement {
	StatusBar,
	NearCursor,
}

/**
 * A transient user notice: a stable message key plus a monotonic [serial] that distinguishes it from an
 * identical earlier message, so the UI can re-time its dismissal even when the same notice repeats.
 * Carries a key rather than display text so this module stays presentation-free and the UI layer resolves
 * the localized string (the same pattern as [Change.labelKey]).
 *
 * @property String messageKey The stable notice key the UI layer resolves to a localized message.
 * @property Long serial The stamping order (see [EditorSession.emitNotice]); higher is newer.
 * @property NoticePlacement placement Where the shell surfaces this notice.
 * @property List<String> arguments The values the localized message formats in, in its placeholder order
 *   (a count, a file name); empty for a message with none.  Strings rather than typed values so this
 *   module stays presentation-free - the UI layer only substitutes.
 */
data class Notice(
	val messageKey: String,
	val serial: Long,
	val placement: NoticePlacement,
	val arguments: List<String> = emptyList(),
)