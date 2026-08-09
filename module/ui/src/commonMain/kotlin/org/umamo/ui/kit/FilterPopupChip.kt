package org.umamo.ui.kit

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon

/**
 * A header filter dropdown: the shared [PopupChip] wearing a funnel, holding a panel of view toggles.
 * The panel stays open while toggling, so several filters can be flipped in one visit, and the open
 * state stays inside the chip - a panel adds a filter menu by supplying only its toggle rows.
 *
 * @param String    contentDescription The accessible label for the chip (the face is icon-only).
 * @param Modifier  modifier           The layout modifier.
 * @param UmamoIcon icon               The chip's leading glyph; the funnel by default.
 * @param Boolean   enabled            When false the chip dims and clicks are inert.
 * @param Function  content            The toggle rows, stacked in the popup panel.
 */
@Composable
fun FilterPopupChip(
	contentDescription: String,
	modifier: Modifier = Modifier,
	icon: UmamoIcon = LocalUmamoIcons.filterFiltered,
	enabled: Boolean = true,
	content: @Composable ColumnScope.() -> Unit,
) {
	PopupChip(
		contentDescription = contentDescription,
		modifier = modifier,
		icon = icon,
		enabled = enabled,
		content = content,
	)
}

/**
 * A muted caption heading one group of rows inside a [FilterPopupChip] panel.  Extracted so every
 * filter panel's section headings share one type style and inset.
 *
 * @param String   text     The heading text.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun FilterSectionLabel(text: String, modifier: Modifier = Modifier) {
	Text(
		text = text,
		style = LocalUmamoTypography.current.labelSmall,
		color = LocalUmamoColors.current.textMuted,
		modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
	)
}