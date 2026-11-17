package org.umamo.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * The widest a tooltip's label runs before it wraps.  A control's name never reaches it; a row's
 * description is a sentence or two, and without the cap it would draw as one line across the window.
 */
private val TOOLTIP_MAX_WIDTH = 280.dp

/** The gap between a tooltip's label and the accelerator that follows it. */
private val TOOLTIP_ACCELERATOR_GAP = 8.dp

/**
 * Wraps [content] with a hover tooltip showing [text], revealed after a short mouse dwell; touch platforms
 * have no hover and render the content unchanged.  The caller's layout [modifier] rides on the wrapper
 * (the outermost node) so alignment and weight still resolve against the parent.
 *
 * The label is meant to double as the control's accessible description, so the on-screen hint and the
 * accessible name stay in lockstep.
 *
 * @param String   text       The tooltip label; blank attaches no tooltip.
 * @param String?  accelerator The key that runs the control, shown beside the label; null for none.
 * @param Modifier modifier   The layout modifier, applied to the wrapper.
 * @param Function content    The control the tooltip describes.
 */
@Composable
expect fun Tooltip(text: String, accelerator: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit)

/**
 * The tooltip's floating card: a small rounded popover reading like the kit's menus, with the label in the
 * primary text color.  Shared by the desktop [Tooltip] and by every other transient label that should read
 * as a tooltip (the near-cursor notice and the relation-pick badge position one through [modifier]), so no
 * hand-rolled card drifts out of step with it.
 *
 * The [accelerator] rides the card as a keycap - the chip the keybindings editor shows a chord in - so a
 * key reads as a key wherever it is shown, and not as another word on the end of the label.
 *
 * @param String   text       The tooltip label.
 * @param String?  accelerator The key that runs the control, or null for none.
 * @param Modifier modifier   The layout modifier, applied to the card (a caller may position it).
 */
@Composable
internal fun TooltipCard(text: String, accelerator: String? = null, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	Surface(
		modifier = modifier,
		color = colors.menuBackground,
		shape = LocalUmamoShapes.current.small,
		border = BorderStroke(1.dp, colors.panelBorder),
		shadowElevation = 4.dp,
	) {
		Row(
			modifier = Modifier.widthIn(max = TOOLTIP_MAX_WIDTH).padding(horizontal = 8.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (text.isNotBlank()) {
				Text(
					text = text,
					style = LocalUmamoTypography.current.labelMedium,
					color = colors.text,
					// fill = false so the label keeps its intrinsic width where it fits and wraps only where it
					// must; the keycap keeps its own width either way.
					modifier = Modifier.weight(1f, fill = false),
				)
			}

			if (accelerator != null) {
				if (text.isNotBlank()) {
					Spacer(modifier = Modifier.width(TOOLTIP_ACCELERATOR_GAP))
				}

				// A keycap, the same chip the keybindings editor shows a chord in, so a key reads as a key
				// rather than as another word.
				Box(
					modifier =
						Modifier
							.clip(LocalUmamoShapes.current.small)
							.background(colors.controlBackground)
							.border(BorderStroke(1.dp, colors.controlBorder), LocalUmamoShapes.current.small)
							.padding(horizontal = 4.dp, vertical = 1.dp),
				) {
					Text(
						text = accelerator,
						style = LocalUmamoTypography.current.labelMedium,
						color = colors.text,
					)
				}
			}
		}
	}
}