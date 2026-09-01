package org.umamo.ui.workspace.commands

import org.umamo.interop.ExportReport
import org.umamo.ui.action.Command
import org.umamo.ui.document.DocumentOpenFailure
import org.umamo.ui.model.AtlasRepackReport
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.ExportOptionsRequest
import org.umamo.ui.workspace.ShellOverlayState

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
		// The document layer asks before replacing a dirty document (an import discards its unsaved
		// edits); the shell owns the confirm dialog so Escape/Enter route like every other overlay.
		Command("document.confirmReplace", title = null) { argument ->
			(argument as? Function0<*>)?.let { proceed ->
				overlays.pendingConfirm = ConfirmRequest(Res.string.confirm_discard_unsaved) { proceed.invoke() }
			}
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
		// continuation carries the export on from whatever the rigger confirms.
		Command("document.exportOptionsMoc3", title = null) { argument ->
			(argument as? ExportOptionsRequest)?.let { request -> overlays.pendingExportOptions = request }
		},
		// A ready-built confirm from the app layer (the export-overwrite warning).  Unlike
		// document.confirmReplace, whose prompt is fixed here, the caller owns the prompt and its
		// arguments - the command only routes it into the shell's one pending-confirm slot.
		Command("document.confirm", title = null) { argument ->
			(argument as? ConfirmRequest)?.let { request -> overlays.pendingConfirm = request }
		},
	)