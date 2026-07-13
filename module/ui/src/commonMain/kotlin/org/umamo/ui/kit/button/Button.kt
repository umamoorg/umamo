package org.umamo.ui.kit.button

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
 * A small flat button: tight padding, small corners, hover-highlight (no ripple). [primary] fills with the
 * accent; otherwise a neutral control fill.
 *
 * @param String   label    The button text.
 * @param Function onClick  Click callback.
 * @param Modifier modifier Layout modifier.
 * @param Boolean  primary  Accent fill when true, neutral when false.
 */
@Composable
fun Button(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val fill =
		when {
			primary && hovered -> colors.accentHover
			primary -> colors.accent
			hovered -> colors.rowHover
			else -> colors.controlBackground
		}
	Box(
		modifier =
			modifier
				.clip(shapes.small)
				.background(fill)
				.clickable(interactionSource = interaction, indication = null, onClick = onClick)
				.padding(horizontal = 8.dp, vertical = 3.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = label,
			style = LocalUmamoTypography.current.labelMedium,
			color = if (primary) colors.accentText else colors.text,
		)
	}
}
