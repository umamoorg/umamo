package org.umamo.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon

/**
 * A header filter dropdown: a funnel-and-chevron [DropdownChip] opening a panel of view toggles.
 *
 * The popup deliberately STAYS OPEN while toggling - only an outside click or Esc dismisses it - so
 * several filters can be flipped in one visit.  That is why this is a raw [Popup] rather than the kit
 * [Menu], which dismisses per click; the distinction is the whole reason [DropdownChip] leaves its
 * dropdown a slot.  The open state is owned here, so a panel adds a filter menu by supplying only its
 * toggle rows.
 *
 * ヘッダの絞り込みドロップダウン。トグルを複数切り替えられるよう、クリックでは閉じない。
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
	val colors = LocalUmamoColors.current
	var open by remember { mutableStateOf(false) }
	DropdownChip(
		expanded = open,
		onExpandRequest = { open = true },
		contentDescription = contentDescription,
		modifier = modifier,
		icon = icon,
		enabled = enabled,
	) {
		Popup(
			popupPositionProvider = BelowAnchorPositionProvider,
			onDismissRequest = { open = false },
			properties = PopupProperties(focusable = true),
		) {
			Surface(color = colors.menuBackground, shape = LocalUmamoShapes.current.medium) {
				// Intrinsic width so the panel hugs its widest row rather than needing a magic dp.
				Column(modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 4.dp), content = content)
			}
		}
	}
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
