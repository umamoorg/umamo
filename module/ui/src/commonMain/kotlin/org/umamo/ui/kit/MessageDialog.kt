package org.umamo.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import org.umamo.ui.resources.dialog_ok
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * A minimal modal message dialog: a scrim over the whole shell with a small centered card carrying the
 * [message] and a single OK button - [ConfirmDialog]'s one-button sibling for alerts that only inform
 * (a failed file open, a rejected import).  Presentation-only and shell-agnostic: the caller owns the
 * visible state and supplies [onDismiss].  Clicking the scrim, like OK, dismisses; the card swallows
 * clicks so a press inside it does not count as a scrim dismissal.
 *
 * 最小限のモーダルメッセージダイアログ。スクリム＋中央の小カード（メッセージと OK）。通知専用で再利用可能。
 *
 * @param String   message   The already-localized message shown in the card.
 * @param Function onDismiss Called when the user dismisses (OK button or scrim click).
 * @param Modifier modifier  Layout modifier for the scrim.
 */
@Composable
fun MessageDialog(
	message: String,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalUmamoColors.current
	Box(
		// indication = null (the kit convention): a bare clickable renders the default hover/press
		// indication across the whole scrim, a stray dimming layer that toggles on pointer enter/leave.
		modifier =
			modifier
				.fillMaxSize()
				.background(colors.overlayScrim)
				.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
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
				Box(modifier = Modifier.padding(top = 20.dp).align(Alignment.End)) {
					Button(label = stringResource(Res.string.dialog_ok), onClick = onDismiss, primary = true)
				}
			}
		}
	}
}
