package org.umamo.ui.workspace.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.CommandPalette
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.paletteCommands
import org.umamo.ui.document.DocumentOpenError
import org.umamo.ui.help.AboutDialog
import org.umamo.ui.help.CreditsDialog
import org.umamo.ui.kit.dialog.ConfirmDialog
import org.umamo.ui.kit.dialog.DialogChoice
import org.umamo.ui.kit.dialog.MessageDialog
import org.umamo.ui.model.repack.AtlasRepackRefusalReason
import org.umamo.ui.model.repack.AtlasRepackReport
import org.umamo.ui.resources.*
import org.umamo.ui.settings.QuickSetupDialog
import org.umamo.ui.settings.SettingsWindow
import org.umamo.ui.workspace.HoveredSurfaceTracker
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.export.ExportOptionsDialog
import org.umamo.ui.workspace.export.exportReportMessage

/**
 * The shell's modal overlays, painted in stacking order straight into the root surface's content.
 *
 * They are siblings of the shell's content column, so their full-window scrims cover the menu bar and
 * tab strip too and the chrome behind is not interactable while one is open.  Emits no layout of its
 * own, so the order of the calls below is the stacking order: the reverse of the order the modal key
 * ladder hands them keys, so the overlay taking Escape and Enter is always the one on top.  Each modal
 * alert is keyed on the arrival it shows, so the next arrival gets a fresh dialog and a late click never
 * answers it.
 *
 * @param ShellOverlayState     overlays        The modal chrome flags and alert queues.
 * @param CommandRegistry       commandRegistry The registry the palette lists and dispatches into.
 * @param HoveredSurfaceTracker hoveredSurfaces The tracker the palette reads the space it was summoned over from.
 */
@Composable
internal fun ShellModalOverlays(
	overlays: ShellOverlayState,
	commandRegistry: CommandRegistry,
	hoveredSurfaces: HoveredSurfaceTracker,
) {
	if (overlays.paletteVisible) {
		// The space the palette was summoned over, read once per open.  The palette's scrim
		// keeps every leaf from stamping while it is up, so this is also the surface the
		// registry resolves when the chosen command runs - the list and the dispatch cannot
		// disagree about where the pointer is.
		val paletteSurfaceKind = remember { hoveredSurfaces.observedKind }
		// Remembered because this group recomposes whenever any modal arrives or leaves, and a fresh
		// list each time would re-query every availability and recompose the palette with it.  The
		// palette is modal, so nothing changes what applies while it is up; the revision covers the
		// table itself.
		val paletteList =
			remember(commandRegistry.revision, paletteSurfaceKind) {
				paletteCommands(commandRegistry.all(), paletteSurfaceKind)
			}
		CommandPalette(
			commands = paletteList,
			onDismiss = { overlays.paletteVisible = false },
			onInvoke = { command ->
				overlays.paletteVisible = false
				commandRegistry.invoke(command.id)
			},
		)
	}

	// The Help dialogs, below preferences as Escape closes them after it.
	if (overlays.creditsVisible) {
		CreditsDialog(onDismiss = { overlays.creditsVisible = false })
	}

	if (overlays.aboutVisible) {
		AboutDialog(onDismiss = { overlays.aboutVisible = false })
	}

	// The preferences overlay; auto-saves every change, so closing it is the only action it needs.
	if (overlays.settingsVisible) {
		SettingsWindow(onDismiss = { overlays.settingsVisible = false })
	}

	// Quick Setup, open on a first run.  Above preferences and the Help dialogs, and below the alerts,
	// so a message a first launch raises (a read-only document from the command line) still shows
	// over it.
	if (overlays.quickSetupVisible) {
		QuickSetupDialog(onDismiss = { overlays.quickSetupVisible = false })
	}

	// The export-options dialog, the last of the self-focused family (its number field owns
	// focus); the modal alerts below still paint above it.  The request's continuation runs
	// from its own Export button, so dismissal here is unconditional.
	overlays.pendingExportOptions?.let { request ->
		ExportOptionsDialog(
			request = request,
			onDismiss = { overlays.pendingExportOptions = null },
		)
	}
	// The repack refusal report, the lowest of the modal alerts.  Unlike the export report it
	// describes work that did NOT happen: the repack aborted whole rather than dropping these tiles.
	overlays.repackReport?.let { report ->
		key(report) {
			MessageDialog(
				message = repackReportMessage(report),
				onDismiss = { overlays.dismissRepackReport(report) },
			)
		}
	}
	// The export report, in the same modal family: advisory only - the export has already
	// been written when it shows.
	overlays.exportReport?.let { report ->
		key(report) {
			MessageDialog(
				message = exportReportMessage(report),
				onDismiss = { overlays.dismissExportReport(report) },
			)
		}
	}
	// A message the app layer raised (document.alert), in the same modal family.
	overlays.pendingAlert?.let { alert ->
		key(alert) {
			MessageDialog(
				message = stringResource(alert.message, *alert.arguments.toTypedArray()),
				onDismiss = { overlays.dismissAlert(alert) },
				alternative =
					alert.alternative?.let { alternative ->
						DialogChoice(stringResource(alternative.label)) { overlays.chooseAlertAlternative(alert) }
					},
			)
		}
	}
	// The file-open failure alert, the highest of the modal alerts below the confirm dialog.
	overlays.openFailure?.let { failure ->
		key(failure) {
			MessageDialog(
				message = stringResource(openFailureMessage(failure.error), failure.displayName),
				onDismiss = { overlays.dismissOpenFailure(failure) },
			)
		}
	}
	// A destructive command raised a confirm: a modal scrim over the whole shell, painted last
	// so it floats above the tabs, the area tree, the palette, the settings window, and the alerts.
	overlays.pendingConfirm?.let { request ->
		key(request) {
			ConfirmDialog(
				// Format only when the prompt takes arguments: an argument-free prompt may carry a
				// literal % (a scale, a progress figure) that a formatter would choke on.
				message =
					if (request.arguments.isEmpty()) {
						stringResource(request.message)
					} else {
						stringResource(request.message, *request.arguments.toTypedArray())
					},
				onConfirm = { overlays.confirmPending(request) },
				onCancel = { overlays.cancelPending(request) },
				confirmLabel = stringResource(request.confirmLabel),
				cancelLabel = stringResource(request.cancelLabel),
				alternative =
					request.alternative?.let { alternative ->
						DialogChoice(stringResource(alternative.label)) { overlays.choosePendingAlternative(request) }
					},
			)
		}
	}
}

/**
 * Builds the repack refusal alert's text: the localized header, then one line per refused tile.
 *
 * The tile names are document data, shown verbatim; only the reasons are localized.
 *
 * @param AtlasRepackReport report The refusal report.
 * @return String The multiline alert text.
 */
@Composable
private fun repackReportMessage(report: AtlasRepackReport): String {
	val lines = ArrayList<String>(report.refusals.size + 1)
	lines.add(stringResource(Res.string.repack_report_message))

	for (refusal in report.refusals) {
		val reasonText =
			stringResource(
				when (refusal.reason) {
					AtlasRepackRefusalReason.LargerThanPage -> Res.string.repack_report_larger_than_page
					AtlasRepackRefusalReason.NoOpaquePixels -> Res.string.repack_report_no_opaque_pixels
					AtlasRepackRefusalReason.BelowMinimumCoverage -> Res.string.repack_report_below_minimum_coverage
					AtlasRepackRefusalReason.Undecodable -> Res.string.repack_report_undecodable
					AtlasRepackRefusalReason.DegeneratePlacement -> Res.string.repack_report_degenerate_placement
					AtlasRepackRefusalReason.PinnedOffPage -> Res.string.repack_report_pinned_off_page
				},
			)
		lines.add("• ${refusal.tileName} - $reasonText")
	}

	return lines.joinToString(separator = "\n")
}

/**
 * Resolves a document-open failure to its localized alert message resource.  Every message takes the
 * file's display name as its one format argument.
 *
 * @param DocumentOpenError error The failure reason reported by the document loader.
 * @return StringResource The message resource for the file-open alert dialog.
 */
private fun openFailureMessage(error: DocumentOpenError): StringResource =
	when (error) {
		DocumentOpenError.ReadFailed -> Res.string.open_failed_read
		DocumentOpenError.Unrecognized -> Res.string.open_failed_unrecognized
		DocumentOpenError.NotOpenable -> Res.string.open_failed_not_openable
		DocumentOpenError.ParseFailed -> Res.string.open_failed_parse
		DocumentOpenError.MissingManifest -> Res.string.open_failed_missing_manifest
		DocumentOpenError.MissingTexture -> Res.string.open_failed_missing_texture
		DocumentOpenError.NoArtLayers -> Res.string.open_failed_no_art_layers
		DocumentOpenError.NewerFormat -> Res.string.open_failed_newer_format
	}