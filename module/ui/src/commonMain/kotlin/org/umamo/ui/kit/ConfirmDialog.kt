package org.umamo.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import org.umamo.ui.kit.button.Button
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.dialog_cancel
import org.umamo.ui.resources.dialog_confirm
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * A dialog's third choice beside Cancel and Confirm - "Don't Save" beside "Save".
 *
 * @property String   label    The already-localized button label.
 * @property Function onSelect Called when the user picks it.
 */
class DialogChoice(
	val label: String,
	val onSelect: () -> Unit,
)

/**
 * A minimal modal confirm dialog: a scrim over the whole shell with a small centered card carrying the
 * [message] and its buttons.  Presentation-only and shell-agnostic so it is reusable for any destructive
 * action - the caller owns the pending-request state and supplies the actions (the kit has no dialog of its
 * own otherwise).  Clicking the scrim, like Cancel, dismisses without confirming; the card swallows clicks so
 * a press inside it does not count as a scrim dismissal.
 *
 * The confirm button is the primary one - the dialog's default, which Enter activates wherever the caller
 * routes its keys.  An [alternative] sits apart at the left, so a three-way choice reads as the action, the
 * escape hatch, and the other outcome rather than three peers.
 *
 * @param String       message      The already-localized prompt shown in the card.
 * @param Function     onConfirm    Called when the user confirms the action.
 * @param Function     onCancel     Called when the user cancels (Cancel button or scrim click).
 * @param Modifier     modifier     Layout modifier for the scrim.
 * @param String?      confirmLabel An already-localized label for the confirm button naming its action
 *   ("Discard", "Reassign"); null uses the generic "Confirm".
 * @param String?      cancelLabel  An already-localized label for the cancel button; null uses "Cancel".
 * @param DialogChoice? alternative A third choice shown at the left, or null for a two-button dialog.
 */
@Composable
fun ConfirmDialog(
	message: String,
	onConfirm: () -> Unit,
	onCancel: () -> Unit,
	modifier: Modifier = Modifier,
	confirmLabel: String? = null,
	cancelLabel: String? = null,
	alternative: DialogChoice? = null,
) {
	val colors = LocalUmamoColors.current
	Box(
		// indication = null (the kit convention): a bare clickable renders the default hover/press
		// indication across the whole scrim, a stray dimming layer that toggles on pointer enter/leave.
		modifier =
			modifier
				.fillMaxSize()
				.background(colors.overlayScrim)
				.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			// The card swallows clicks (enabled = false) so a press on it is not read as a scrim dismissal.
			modifier = Modifier.widthIn(min = 280.dp, max = 420.dp).clickable(enabled = false, onClick = {}),
			color = colors.panelBackground,
			shape = LocalUmamoShapes.current.medium,
			border = BorderStroke(1.dp, colors.panelBorder),
			shadowElevation = 8.dp,
		) {
			Column(modifier = Modifier.padding(20.dp)) {
				Text(text = message, style = LocalUmamoTypography.current.bodyMedium)
				Row(
					modifier = Modifier.padding(top = 20.dp).fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					alternative?.let { choice -> Button(label = choice.label, onClick = choice.onSelect, primary = false) }
					Spacer(modifier = Modifier.weight(1f))
					Button(label = cancelLabel ?: stringResource(Res.string.dialog_cancel), onClick = onCancel, primary = false)
					Button(label = confirmLabel ?: stringResource(Res.string.dialog_confirm), onClick = onConfirm, primary = true)
				}
			}
		}
	}
}