package org.umamo.ui.kit

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/*
 * The custom design system (org.umamo.ui.kit): small, flat, hover-driven widgets on Compose Foundation, with
 * no Material. Names are unprefixed and read the theme tokens (LocalUmamoColors / LocalUmamoTypography /
 * LocalUmamoShapes) directly. There is no ripple - interaction feedback is hover-highlight throughout.
 *
 * 独自デザイン系。Material を使わない平坦な Foundation 製ウィジェット群。
 */

/**
 * Renders text. Wraps Foundation's [BasicText] with the kit's type scale and color: the [style] defaults to
 * the body style, and the color resolves to [color] → the style's own color → the theme's text color.
 *
 * @param String       text      The text to render.
 * @param Modifier     modifier  Layout modifier.
 * @param TextStyle    style     The text style (defaults to the body scale).
 * @param Color        color     Explicit color, or Unspecified to inherit from the style/theme.
 * @param TextAlign?   textAlign Optional horizontal alignment override.
 * @param Int          maxLines  Maximum line count before truncation.
 * @param TextOverflow overflow  How overflowing text is handled.
 */
@Composable
fun Text(
	text: String,
	modifier: Modifier = Modifier,
	style: TextStyle = LocalUmamoTypography.current.bodyMedium,
	color: Color = Color.Unspecified,
	textAlign: TextAlign? = null,
	maxLines: Int = Int.MAX_VALUE,
	overflow: TextOverflow = TextOverflow.Clip,
) {
	val resolvedColor = color.takeOrElse { style.color.takeOrElse { LocalUmamoColors.current.text } }
	val resolvedStyle = style.copy(color = resolvedColor, textAlign = textAlign ?: style.textAlign)
	BasicText(text = text, modifier = modifier, style = resolvedStyle, maxLines = maxLines, overflow = overflow)
}
