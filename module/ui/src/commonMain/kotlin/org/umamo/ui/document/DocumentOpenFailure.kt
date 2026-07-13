package org.umamo.ui.document

/**
 * Why a file failed to open in the editor.  A stable reason the shell maps to a localized message -
 * the document layer never carries display text.
 */
enum class DocumentOpenError {
	/** The file could not be read at all (missing, unreadable, permission revoked). */
	ReadFailed,

	/** The contents match no format Umamo recognizes. */
	Unrecognized,

	/** A recognized format the editor shell cannot open as a document (e.g. layered art, for now). */
	NotOpenable,

	/** A recognized format whose contents failed to parse or import. */
	ParseFailed,
}

/**
 * The payload of the document.openFailed command: what went wrong and which file, so the shell can
 * alert the user instead of failing silently to the log.  Lives in commonMain (unlike the JVM-bound
 * document loader) so the shell's command handler can receive it on every platform.
 *
 * @property DocumentOpenError error The failure reason.
 * @property String displayName The file's display name, the alert message's one format argument.
 */
class DocumentOpenFailure(
	val error: DocumentOpenError,
	val displayName: String,
)
