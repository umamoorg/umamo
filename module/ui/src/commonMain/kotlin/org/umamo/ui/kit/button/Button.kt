package org.umamo.ui.kit.button

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * A small flat button: tight padding, small corners, hover-highlight and a darker fill while held (no
 * ripple). [primary] fills with the accent; otherwise a neutral control fill.
 *
 * @param String   label    The button text.
 * @param Function onClick  Click callback.
 * @param Modifier modifier Layout modifier.
 * @param Boolean  primary  Accent fill when true, neutral when false.
 * @param Boolean  enabled  When false the button rests on the neutral fill with a disabled label, shows no
 *   hover or press, and ignores clicks.
 */
@Composable
fun Button(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val pressed by interaction.collectIsPressedAsState()
	// The same ramp the filled icon buttons and button-group segments use (accentControlFill), except that
	// a secondary button's hover stays the softer rowHover it has always drawn; a press outranks a hover, and
	// a disabled button outranks both, showing no feedback, as a disabled IconButton does.
	val fill =
		when {
			!enabled -> colors.controlBackground
			primary && pressed -> colors.accentPressed
			primary && hovered -> colors.accentHover
			primary -> colors.accent
			pressed -> colors.buttonPressed
			hovered -> colors.rowHover
			else -> colors.controlBackground
		}
	val labelColor =
		when {
			!enabled -> colors.textDisabled
			primary -> colors.accentText
			else -> colors.text
		}
	Box(
		modifier =
			modifier
				.clip(shapes.small)
				.background(fill)
				.clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
				.padding(horizontal = 8.dp, vertical = 3.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = label,
			style = LocalUmamoTypography.current.labelMedium,
			color = labelColor,
		)
	}
}