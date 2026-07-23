package org.umamo.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/**
 * Wraps [content] with a hover tooltip showing [text].  Desktop reveals the tooltip after a short
 * mouse-dwell over the content; touch platforms have no hover, so the content renders unchanged there (a
 * long-press tooltip awaits the Android target).  The caller's layout [modifier] rides on the wrapper
 * (the outermost node) so alignment and weight still resolve against the parent.
 *
 * The label is meant to double as the control's accessible description - passing the same string to both
 * the tooltip and semantics keeps the on-screen hint and the a11y name in lockstep.  A blank [text]
 * attaches no tooltip, so callers can pass an unconditional label.
 *
 * ホバー時にツールチップを表示する。デスクトップはマウス静止で表示、タッチはホバーが無いのでそのまま描画
 * （長押しツールチップは Android ターゲットで対応）。ラベルはアクセシビリティ名を兼ねる。
 *
 * @param String   text     The tooltip label; blank attaches no tooltip.
 * @param Modifier modifier The layout modifier, applied to the wrapper.
 * @param Function content  The control the tooltip describes.
 */
@Composable
expect fun Tooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit)

/**
 * The tooltip's floating card: a small rounded popover reading like the kit's menus (menu fill, a
 * hairline border, a soft drop shadow) with the label in the primary text color.  Shared by every
 * platform's [Tooltip] so the chrome stays identical, and by any other transient label that should read
 * as a tooltip - the relation-pick badge positions one at the cursor through [modifier].  Keeping the
 * chrome here is the point: a second hand-rolled card would drift out of step with this one.
 *
 * ツールチップの浮動カード。メニューと同じ見た目（塗り・枠線・影）でラベルを表示する。
 *
 * @param String   text     The tooltip label.
 * @param Modifier modifier The layout modifier, applied to the card (a caller may position it).
 */
@Composable
internal fun TooltipCard(text: String, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	Surface(
		modifier = modifier,
		color = colors.menuBackground,
		shape = LocalUmamoShapes.current.small,
		border = BorderStroke(1.dp, colors.panelBorder),
		shadowElevation = 4.dp,
	) {
		Text(
			text = text,
			style = LocalUmamoTypography.current.labelMedium,
			color = colors.text,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
		)
	}
}
