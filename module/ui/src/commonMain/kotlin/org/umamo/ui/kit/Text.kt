package org.umamo.ui.kit

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/*
 * The custom design system (org.umamo.ui.kit): small, flat, hover-driven widgets on Compose Foundation, with
 * no Material.  Names are unprefixed and read the theme tokens (LocalUmamoColors / LocalUmamoTypography /
 * LocalUmamoShapes) directly.  There is no ripple - interaction feedback is hover-highlight throughout.
 *
 * This package root holds the primitives the rest of the kit is built on - text, vertical text, surface,
 * tooltip, scrollbar, progress bar, stacked shapes, click gestures, box gestures, resize drags, pointer
 * fall-through, thumbnails, file drops, and popup placement - and imports none of the subpackages: button, chip,
 * container, dialog, field, menu, and textentry.
 */

/**
 * Renders text.  Wraps Foundation's [BasicText] with the kit's type scale and color: the [style] defaults to
 * the body style, and the color resolves to [color] → the style's own color → the theme's text color.
 *
 * @param String       text      The text to render.
 * @param Modifier     modifier  Layout modifier.
 * @param TextStyle    style     The text style (defaults to the body scale).
 * @param Color        color     Explicit color, or Unspecified to inherit from the style/theme.
 * @param TextAlign?   textAlign Optional horizontal alignment override.
 * @param Int          maxLines  Maximum line count before truncation.
 * @param TextOverflow overflow  How overflowing text is handled.
 * @param Function?    onTextLayout Called with the measured layout result, or null to skip it.
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
	onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
	Text(
		text = AnnotatedString(text),
		modifier = modifier,
		style = style,
		color = color,
		textAlign = textAlign,
		maxLines = maxLines,
		overflow = overflow,
		onTextLayout = onTextLayout,
	)
}

/**
 * The same text, with its own spans - the form a highlighted search hit takes.
 *
 * @param AnnotatedString text The text and its spans.
 * @param Modifier modifier The layout modifier.
 * @param TextStyle style The text style.
 * @param Color color The text colour, or unspecified for the style's own.
 * @param TextAlign? textAlign The text alignment.
 * @param Int maxLines The maximum lines the text takes.
 * @param TextOverflow overflow How the text is cut when it does not fit.
 * @param Function? onTextLayout Called with the measured result.
 */
@Composable
fun Text(
	text: AnnotatedString,
	modifier: Modifier = Modifier,
	style: TextStyle = LocalUmamoTypography.current.bodyMedium,
	color: Color = Color.Unspecified,
	textAlign: TextAlign? = null,
	maxLines: Int = Int.MAX_VALUE,
	overflow: TextOverflow = TextOverflow.Clip,
	onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
	val resolvedColor = color.takeOrElse { style.color.takeOrElse { LocalUmamoColors.current.text } }
	val resolvedStyle = style.copy(color = resolvedColor, textAlign = textAlign ?: style.textAlign)
	BasicText(
		text = text,
		modifier = modifier,
		style = resolvedStyle,
		// Reported so a caller can react to the MEASURED result - the only way to know a label was actually
		// ellipsized, which is what decides whether a "show the full name" tooltip is noise or necessary.
		onTextLayout = onTextLayout,
		maxLines = maxLines,
		overflow = overflow,
	)
}

/**
 * The text with the searched word banded, for a list that narrows as the user types.
 *
 * The band is the kit's search-match colour, so a hit reads the same wherever it is found.  An empty query,
 * or one that is not in the text, leaves the text alone.
 *
 * @param String text The text to mark up.
 * @param String query The search query.
 * @return AnnotatedString The text, with the match banded.
 */
@Composable
fun highlightedText(text: String, query: String): AnnotatedString {
	if (query.isEmpty()) {
		return AnnotatedString(text)
	}

	val matchColor = LocalUmamoColors.current.searchMatchBackground
	val start = text.indexOf(query, ignoreCase = true)

	if (start < 0) {
		return AnnotatedString(text)
	}

	return AnnotatedString.Builder(text).apply {
		addStyle(SpanStyle(background = matchColor), start, start + query.length)
	}.toAnnotatedString()
}