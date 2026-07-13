package org.umamo.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.document.DocumentOpenFailure

/**
 * A pending confirmation: the localized prompt to show and the action to run if the user confirms.  The
 * shell holds at most one of these (like its palette-visible flag) and renders a ConfirmDialog for it,
 * so a destructive command (reset, import-overwrite) sets one instead of acting immediately.
 *
 * 保留中の確認。表示する文言と、確定時に実行する処理を持つ。
 *
 * @property StringResource message The localized prompt shown in the dialog.
 * @property Function onConfirm The action to run when confirmed.
 */
internal data class ConfirmRequest(val message: StringResource, val onConfirm: () -> Unit)

/**
 * The shell's transient overlay flags in one place: which modal chrome (palette, preferences, Help
 * dialogs, confirm dialog, file-open alert) is currently up.  The command handlers toggle these, the
 * modal key ladder routes Escape/Enter by them, the focus-reclaim effect watches their aggregate,
 * and the shell renders the matching overlay for each - one holder instead of six loose vars, so the
 * pieces that must agree read the same state.
 *
 * シェルの一時的なオーバーレイ状態（パレット・設定・ヘルプ・確認・ファイルオープン失敗）を
 * まとめて保持する。コマンドが切り替え、キー処理とフォーカス回復と描画が同じ状態を読む。
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
	 * The file-open failure alert's payload - set by the document.openFailed command (dispatched by
	 * the app's document layer), cleared by its OK button, the scrim, Escape, or Enter.  Null while
	 * none shows.
	 */
	var openFailure: DocumentOpenFailure? by mutableStateOf(null)

	/**
	 * True while an overlay that holds its own focus is open (the palette's search field, the
	 * preferences window's popups, the Help dialogs).  While one is up the shell must NOT steal focus;
	 * it reclaims when this flips false.
	 */
	val selfFocusedOverlayOpen: Boolean
		get() = paletteVisible || settingsVisible || aboutVisible || creditsVisible

	/**
	 * True while a modal alert (confirm dialog, file-open failure) is up.  These do NOT hold their own
	 * focus - the shell keeps root focus so their Escape/Enter route through the modal key ladder.
	 */
	val modalAlertOpen: Boolean
		get() = pendingConfirm != null || openFailure != null
}
