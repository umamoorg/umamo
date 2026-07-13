package org.umamo.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors

/**
 * A background container: a [Box] with a fill [color], an optional [shape] clip, an optional [border], and an
 * optional drop [shadowElevation]. Replaces Material's Surface (its tonal-elevation tinting is intentionally
 * dropped - pick an explicit [color] instead).
 *
 * @param Modifier      modifier        Layout modifier.
 * @param Color         color           Background fill (defaults to the panel color).
 * @param Shape         shape           Clip + border + shadow shape.
 * @param BorderStroke? border          Optional border.
 * @param Dp            shadowElevation Drop-shadow elevation (0 = none).
 * @param Function      content         The surface content.
 */
@Composable
fun Surface(
	modifier: Modifier = Modifier,
	color: Color = LocalUmamoColors.current.panelBackground,
	shape: Shape = RectangleShape,
	border: BorderStroke? = null,
	shadowElevation: Dp = 0.dp,
	content: @Composable () -> Unit,
) {
	var surfaceModifier = modifier
	if (shadowElevation > 0.dp) {
		surfaceModifier = surfaceModifier.shadow(shadowElevation, shape)
	}
	surfaceModifier = surfaceModifier.clip(shape).background(color)
	if (border != null) {
		surfaceModifier = surfaceModifier.border(border, shape)
	}
	Box(modifier = surfaceModifier) { content() }
}

/**
 * A thin horizontal divider line.
 *
 * @param Modifier modifier  Layout modifier.
 * @param Dp       thickness Line thickness.
 * @param Color    color     Line color (defaults to the divider color).
 */
@Composable
fun Divider(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = LocalUmamoColors.current.divider) {
	Box(modifier = modifier.fillMaxWidth().height(thickness).background(color))
}
