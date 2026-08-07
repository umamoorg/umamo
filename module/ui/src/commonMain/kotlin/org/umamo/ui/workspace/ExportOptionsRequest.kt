package org.umamo.ui.workspace

import org.umamo.interop.moc3.Moc3ExportOptions

/**
 * A pending export-options dialog: the format's options to edit, the facts the dialog needs to
 * render them, and the action to run with whatever the rigger confirms.
 *
 * One case per export format that HAS options - the dialog matches exhaustively, so giving a new
 * format (GLTF, some day) an options pane starts by adding its case here and the compiler walks the
 * rest of the way.  A format with no case (CMO3) never shows the dialog, by construction rather
 * than by flag.
 *
 * The shell holds at most one of these (see [ShellOverlayState.pendingExportOptions]) and renders
 * the dialog for it; the app layer builds the request and continues the export in [onConfirm] -
 * the same continuation shape as [ConfirmRequest].
 */
internal sealed interface ExportOptionsRequest {
	/**
	 * The MOC3 export's options.
	 *
	 * @property Moc3ExportOptions initial The options to open the dialog with (the session's sticky
	 *                                     values, scale already seeded).
	 * @property Boolean physicsAvailable  Whether a retained physics3.json exists to include; the
	 *                                     toggle is disabled when there is nothing to carry.
	 * @property Boolean userDataAvailable Whether a retained userdata3.json exists to include.
	 * @property Float   canvasWidth       The model's canvas width in pixels, for the units readout.
	 * @property Float   canvasHeight      The model's canvas height in pixels, for the units readout.
	 * @property Function onConfirm        Continues the export with the confirmed options.
	 */
	data class Moc3(
		val initial: Moc3ExportOptions,
		val physicsAvailable: Boolean,
		val userDataAvailable: Boolean,
		val canvasWidth: Float,
		val canvasHeight: Float,
		val onConfirm: (Moc3ExportOptions) -> Unit,
	) : ExportOptionsRequest
}
