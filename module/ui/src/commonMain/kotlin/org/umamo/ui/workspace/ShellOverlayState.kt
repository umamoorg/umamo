package org.umamo.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.StringResource
import org.umamo.interop.ExportReport
import org.umamo.ui.document.DocumentOpenFailure
import org.umamo.ui.model.AtlasRepackReport
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.dialog_cancel
import org.umamo.ui.resources.dialog_confirm

/**
 * A confirmation's third choice beside Cancel and Confirm - "Don't Save" beside "Save".
 *
 * @property StringResource label    The button's label.
 * @property Function       onSelect The action to run when picked.
 */
internal data class ConfirmAlternative(
	val label: StringResource,
	val onSelect: () -> Unit,
)

/**
 * A pending confirmation: the localized prompt to show, the buttons it names, and the action to run if the
 * user confirms.  The shell holds at most one of these (like its palette-visible flag) and renders a
 * ConfirmDialog for it, so a destructive command (reset, import-overwrite, export-overwrite) sets one instead
 * of acting immediately.
 *
 * @property StringResource      message      The localized prompt shown in the dialog.
 * @property List                arguments    The prompt's format arguments, in placeholder order; empty for
 *                                            an argument-free prompt.  Plain values (counts, file names) -
 *                                            document data is never translated.
 * @property StringResource      confirmLabel The confirm button's label, naming the action it takes.
 * @property StringResource      cancelLabel  The cancel button's label.
 * @property ConfirmAlternative? alternative  A third choice, or null for a two-button dialog.
 * @property Function            onConfirm    The action to run when confirmed.
 */
internal data class ConfirmRequest(
	val message: StringResource,
	val arguments: List<Any> = emptyList(),
	val confirmLabel: StringResource = Res.string.dialog_confirm,
	val cancelLabel: StringResource = Res.string.dialog_cancel,
	val alternative: ConfirmAlternative? = null,
	val onConfirm: () -> Unit,
)

/**
 * The shell's transient overlay flags in one place: which modal chrome (palette, preferences, Help
 * dialogs, confirm dialog, file-open alert, export report, repack refusal report) is currently up.
 * The command handlers toggle these, the modal key ladder routes Escape/Enter by them, the
 * focus-reclaim effect watches their aggregate, and the shell renders the matching overlay for each -
 * one holder instead of eight loose vars, so the pieces that must agree read the same state.
 */
internal class ShellOverlayState {
	/** The command palette's visible flag - toggled by palette.toggle. */
	var paletteVisible: Boolean by mutableStateOf(false)

	/** The preferences overlay's visible flag - toggled by the edit.preferences command. */
	var settingsVisible: Boolean by mutableStateOf(false)

	/** The About dialog's visible flag - toggled by the help.about command. */
	var aboutVisible: Boolean by mutableStateOf(false)

	/** The Credits dialog's visible flag - toggled by the help.credits command. */
	var creditsVisible: Boolean by mutableStateOf(false)

	/**
	 * A destructive command (reset, import-overwrite) sets this instead of acting; the rendered
	 * ConfirmDialog runs the action on confirm.  At most one is pending at a time.
	 */
	var pendingConfirm: ConfirmRequest? by mutableStateOf(null)

	/**
	 * Runs the pending confirmation's action - what its confirm button and Enter both do.
	 *
	 * The slot clears before the action runs, so an action that raises a confirmation of its own leaves that one
	 * pending instead of having it cleared out from under it.  The key ladder and the rendered dialog both come
	 * through here, so the two cannot disagree about what confirming means.
	 */
	fun confirmPending() {
		val request = pendingConfirm ?: return
		pendingConfirm = null
		request.onConfirm()
	}

	/**
	 * Dismisses the pending confirmation without acting - what its cancel button, the scrim, and Escape do.
	 */
	fun cancelPending() {
		pendingConfirm = null
	}

	/**
	 * Runs the pending confirmation's third choice, when it has one, clearing the slot first as [confirmPending]
	 * does.
	 */
	fun choosePendingAlternative() {
		val alternative = pendingConfirm?.alternative ?: return
		pendingConfirm = null
		alternative.onSelect()
	}

	/**
	 * The file-open failure alert's payload - set by the document.openFailed command (dispatched by
	 * the app's document layer), cleared by its OK button, the scrim, Escape, or Enter.  Null while
	 * none shows.
	 */
	var openFailure: DocumentOpenFailure? by mutableStateOf(null)

	/**
	 * The export report alert's payload - set by the document.exportReport command when a CMO3 or MOC3
	 * export finished with advisory notices (edits the target format could not carry, features stripped
	 * for an older runtime target, weld divergence), cleared like the open-failure alert.  Null while
	 * none shows.  Non-blocking: the export has already been written when this shows.
	 */
	var exportReport: ExportReport? by mutableStateOf(null)

	/**
	 * The repack refusal report's payload - set by the document.repackReport command when a repack
	 * aborted over tiles it could not carry, cleared like the open-failure alert.  Null while none
	 * shows.  Unlike the export report this one is about work that did NOT happen: nothing was
	 * applied when it shows.
	 */
	var repackReport: AtlasRepackReport? by mutableStateOf(null)

	/**
	 * The export-options dialog's payload - set by the document.exportOptionsMoc3 command when an
	 * export with options begins, cleared by Cancel, the scrim, Escape, or the Export button (which
	 * first runs the request's continuation).  Null while none shows.
	 */
	var pendingExportOptions: ExportOptionsRequest? by mutableStateOf(null)

	/**
	 * True while an overlay that holds its own focus is open (the palette's search field, the
	 * preferences window's popups, the Help dialogs, the export-options dialog's fields).  While one
	 * is up the shell must NOT steal focus; it reclaims when this flips false.
	 */
	val selfFocusedOverlayOpen: Boolean
		get() = paletteVisible || settingsVisible || aboutVisible || creditsVisible || pendingExportOptions != null

	/**
	 * Closes the topmost open self-focused overlay, if any - what Escape does to this family.
	 *
	 * The order is the overlays' stacking order.  It lives here rather than as consecutive
	 * modal-ladder arms, so the flags and the precedence over them cannot drift apart.  It only matters
	 * when two are somehow open at once; with one open, any order closes it.
	 */
	fun closeTopmostSelfFocused() {
		when {
			pendingExportOptions != null -> pendingExportOptions = null
			settingsVisible -> settingsVisible = false
			aboutVisible -> aboutVisible = false
			creditsVisible -> creditsVisible = false
			paletteVisible -> paletteVisible = false
		}
	}

	/**
	 * True while a modal alert (confirm dialog, file-open failure, export report, repack refusal
	 * report) is up.  These do NOT hold their own focus - the shell keeps root focus so their
	 * Escape/Enter route through the modal key ladder.
	 */
	val modalAlertOpen: Boolean
		get() = pendingConfirm != null || openFailure != null || exportReport != null || repackReport != null
}