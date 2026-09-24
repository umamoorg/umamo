package org.umamo.ui.workspace.commands

import org.umamo.interop.ExportReport
import org.umamo.ui.action.Command
import org.umamo.ui.document.DocumentOpenFailure
import org.umamo.ui.model.AtlasRepackReport
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.AlertRequest
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.DialogAlternative
import org.umamo.ui.workspace.ExportOptionsRequest
import org.umamo.ui.workspace.ShellOverlayState

/**
 * What a dirty document's replace and quit prompts can do: discard the unsaved edits and go on, or save
 * them and go on once the save has landed.
 *
 * @property Function  discard Goes on without saving.
 * @property Function? save    Saves, then goes on when the save succeeds; null when the document cannot be
 *   saved (it opened read-only), which leaves the prompt with the discard alone.
 */
class DirtyDocumentPrompt(
	val discard: () -> Unit,
	val save: (() -> Unit)?,
)

/**
 * The confirm a dirty document's prompt raises: Save (the default) / Don't Save / Cancel when a save is
 * possible, else the plain discard-or-cancel pair, worded for a replace or for quitting.
 *
 * @param DirtyDocumentPrompt prompt   The actions.
 * @param Boolean             quitting True for the quit guard's wording, false for a document replace.
 * @return ConfirmRequest The request.
 */
private fun dirtyDocumentRequest(prompt: DirtyDocumentPrompt, quitting: Boolean): ConfirmRequest {
	val save = prompt.save
	return if (save != null) {
		ConfirmRequest(
			message = if (quitting) Res.string.confirm_save_before_quit else Res.string.confirm_save_before_replace,
			confirmLabel = Res.string.dialog_save,
			alternative = DialogAlternative(Res.string.dialog_dont_save) { prompt.discard() },
			onConfirm = save,
		)
	} else {
		ConfirmRequest(
			message = if (quitting) Res.string.confirm_quit_unsaved else Res.string.confirm_discard_unsaved,
			confirmLabel = if (quitting) Res.string.dialog_quit_without_saving else Res.string.dialog_discard,
			onConfirm = prompt.discard,
		)
	}
}

/**
 * The document-report commands the app's document layer dispatches into.
 *
 * The reporting lives here rather than in the app because the shell owns the modal chrome: routing a
 * failure or a confirm through a command means Escape, Enter, the scrim, and focus reclamation behave
 * exactly as they do for every other overlay, instead of each alert re-implementing them.  All of them
 * are argument-only and untitled, so they never surface in the palette - there is nothing to invoke
 * without a payload.
 *
 * @param ShellOverlayState overlays The overlay state the confirms and the alerts go through.
 * @return List<Command> The commands to register.
 */
internal fun documentCommands(overlays: ShellOverlayState): List<Command> =
	listOf(
		Command("document.openFailed", title = null) { argument ->
			(argument as? DocumentOpenFailure)?.let { failure -> overlays.openFailure = failure }
		},
		// The document layer asks before replacing a dirty document (an open or an import discards its
		// unsaved edits); the shell owns the confirm dialog so Escape/Enter route like every other overlay.
		// Save is the default when the document can be saved, with Don't Save as the third choice.
		Command("document.confirmReplace", title = null) { argument ->
			(argument as? DirtyDocumentPrompt)?.let { prompt -> overlays.pendingConfirm = dirtyDocumentRequest(prompt, quitting = false) }
		},
		// The app asks before quitting over a dirty document - from File > Exit, the window's close button, the
		// OS's quit, or Android's back gesture - with the same Save / Don't Save / Cancel shape.
		Command("document.confirmExit", title = null) { argument ->
			(argument as? DirtyDocumentPrompt)?.let { prompt -> overlays.pendingConfirm = dirtyDocumentRequest(prompt, quitting = true) }
		},
		// A CMO3 or MOC3 export finished with advisory notices; the shell shows them in a modal alert.
		Command("document.exportReport", title = null) { argument ->
			(argument as? ExportReport)?.let { report -> overlays.exportReport = report }
		},
		// A repack refused; the shell shows which tiles kept it from running.  Modal like the export
		// report - a tile the pack cannot carry must not be a four-second toast.
		Command("document.repackReport", title = null) { argument ->
			(argument as? AtlasRepackReport)?.let { report -> overlays.repackReport = report }
		},
		// An export with options is starting; the shell shows the options dialog and the request's
		// continuation carries the export on from whatever the rigger confirms.  One command for every
		// format: the dialog picks its pane by the request's type.
		Command("document.exportOptions", title = null) { argument ->
			(argument as? ExportOptionsRequest)?.let { request -> overlays.pendingExportOptions = request }
		},
		// A ready-built confirm from the app layer (the export-overwrite warning).  Unlike
		// document.confirmReplace, whose prompt is fixed here, the caller owns the prompt and its
		// arguments - the command only routes it into the shell's one pending-confirm slot.
		Command("document.confirm", title = null) { argument ->
			(argument as? ConfirmRequest)?.let { request -> overlays.pendingConfirm = request }
		},
		// A ready-built message from the app layer with nothing to decide - a read-only open, a failed save.
		Command("document.alert", title = null) { argument ->
			(argument as? AlertRequest)?.let { request -> overlays.pendingAlert = request }
		},
	)