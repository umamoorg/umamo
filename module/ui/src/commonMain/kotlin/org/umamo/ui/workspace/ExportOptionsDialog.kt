package org.umamo.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Divider
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.Button
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.dialog_cancel
import org.umamo.ui.resources.export_options_export
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The export-options dialog: format-generic chrome around a per-format option pane.
 *
 * Dispatches on the request's case, so a new export format gains an options pane by adding its
 * [ExportOptionsRequest] case and the compiler demands an arm here - the same compile-time prompt
 * the preferences window uses for its categories.  Cancel and the scrim dismiss without exporting;
 * the pane's Export button runs the request's continuation first.
 *
 * @param ExportOptionsRequest request The pending request to render.
 * @param Function onDismiss Clears the pending request, with or without a confirm.
 */
@Composable
internal fun ExportOptionsDialog(
	request: ExportOptionsRequest,
	onDismiss: () -> Unit,
) {
	when (request) {
		is ExportOptionsRequest.Moc3 -> Moc3ExportOptionsPane(request, onDismiss)
	}
}

/**
 * The shared dialog chrome: scrim, centered card, title, the format's rows, and the Cancel / Export
 * footer.
 *
 * The same scrim-and-card recipe as ConfirmDialog, one size up: the scrim click cancels, the card
 * swallows clicks so a press inside it is not read as a dismissal.
 *
 * @param String   title    The already-localized dialog title.
 * @param Function onCancel Called on Cancel, the scrim, or Escape's dismissal path.
 * @param Function onExport Called when the rigger confirms the export.
 * @param Function content  The format's option rows.
 */
@Composable
internal fun ExportOptionsCard(
	title: String,
	onCancel: () -> Unit,
	onExport: () -> Unit,
	content: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalUmamoColors.current
	Box(
		// indication = null (the kit convention): a bare clickable renders the default hover/press
		// indication across the whole scrim, a stray dimming layer that toggles on pointer enter/leave.
		modifier =
			Modifier
				.fillMaxSize()
				.background(colors.overlayScrim)
				.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			// The card swallows clicks (enabled = false) so a press on it is not read as a scrim dismissal.
			modifier = Modifier.widthIn(min = 320.dp, max = 440.dp).clickable(enabled = false, onClick = {}),
			color = colors.panelBackground,
			shape = LocalUmamoShapes.current.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(text = title, style = LocalUmamoTypography.current.titleSmall)
				content()
				Row(
					modifier = Modifier.padding(top = 12.dp).align(Alignment.End),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Button(label = stringResource(Res.string.dialog_cancel), onClick = onCancel, primary = false)
					Button(label = stringResource(Res.string.export_options_export), onClick = onExport, primary = true)
				}
			}
		}
	}
}

/**
 * A section heading within the options card: a muted label over a divider.
 *
 * @param String label The already-localized section name.
 */
@Composable
internal fun ExportOptionsSectionLabel(label: String) {
	val colors = LocalUmamoColors.current
	Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
		Text(
			text = label,
			style = LocalUmamoTypography.current.labelMedium,
			color = colors.textMuted,
			modifier = Modifier.padding(bottom = 4.dp),
		)
		Divider()
	}
}