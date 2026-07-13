package org.umamo.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
 * A minimal modal confirm dialog: a scrim over the whole shell with a small centered card carrying the
 * [message] and a Cancel / Confirm pair.  Presentation-only and shell-agnostic so it is reusable for any
 * destructive action - the caller owns the pending-request state and supplies [onConfirm] / [onCancel]
 * (the kit has no dialog of its own otherwise).  Clicking the scrim, like Cancel, dismisses without
 * confirming; the card swallows clicks so a press inside it does not count as a scrim dismissal.
 *
 * 最小限のモーダル確認ダイアログ。スクリム＋中央の小カード（メッセージとキャンセル / 確定）。表示専用で再利用可能。
 *
 * @param String   message      The already-localized prompt shown in the card.
 * @param Function onConfirm     Called when the user confirms the action.
 * @param Function onCancel      Called when the user cancels (Cancel button or scrim click).
 * @param Modifier modifier      Layout modifier for the scrim.
 * @param String?  confirmLabel  An already-localized label for the confirm button (e.g. "Reassign",
 *   "Delete") for a more specific action than the generic default; null uses the default "Confirm".
 */
@Composable
fun ConfirmDialog(
	message: String,
	onConfirm: () -> Unit,
	onCancel: () -> Unit,
	modifier: Modifier = Modifier,
	confirmLabel: String? = null,
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
					modifier = Modifier.padding(top = 20.dp).align(Alignment.End),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Button(label = stringResource(Res.string.dialog_cancel), onClick = onCancel, primary = false)
					Button(label = confirmLabel ?: stringResource(Res.string.dialog_confirm), onClick = onConfirm, primary = true)
				}
			}
		}
	}
}
