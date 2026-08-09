package org.umamo.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.UmamoIcon

/**
 * A [DropdownChip] over a stay-open panel of arbitrary content: only an outside click or Esc dismisses
 * it, so several controls can be driven in one visit.  That is the whole reason this is a raw [Popup]
 * rather than the kit [Menu], which dismisses per click - and the reason [DropdownChip] leaves its
 * dropdown a slot in the first place.
 *
 * The panel provides [LocalPopupDismissOwned], so a [Menu] or nested chip composed inside the content
 * yields the dismiss to this popup instead of fighting it for focus.
 *
 * Open state is optionally hoistable: leave [expanded] null and the chip owns it (the common case), or
 * pass it with [onExpandedChange] when the caller has to force the panel shut - the overflow row does,
 * since a strip that widens while the panel is open would otherwise leave it anchored to nothing.
 *
 * 開いたままのパネルを持つチップ。外側クリックか Esc でのみ閉じるので、複数の操作を一度に行える。
 * 開閉状態は内部で持つか、呼び出し側に持ち上げるかを選べる。
 *
 * @param String    contentDescription The accessible label for the chip (the face is icon-only).
 * @param Modifier  modifier           The layout modifier.
 * @param UmamoIcon icon               The chip's leading glyph, or null for a chevron-only face.
 * @param Boolean?  expanded           The open state when hoisted; null to let the chip own it.
 * @param Function? onExpandedChange   Open-state sink, required when [expanded] is hoisted.
 * @param Boolean   enabled            When false the chip dims and clicks are inert.
 * @param Function  content            The panel's rows.
 */
@Composable
fun PopupChip(
	contentDescription: String,
	modifier: Modifier = Modifier,
	icon: UmamoIcon? = null,
	expanded: Boolean? = null,
	onExpandedChange: ((Boolean) -> Unit)? = null,
	enabled: Boolean = true,
	content: @Composable ColumnScope.() -> Unit,
) {
	val colors = LocalUmamoColors.current
	var selfOpen by remember { mutableStateOf(false) }
	val open = expanded ?: selfOpen
	val setOpen: (Boolean) -> Unit = { next ->
		if (onExpandedChange != null) {
			onExpandedChange(next)
		} else {
			selfOpen = next
		}
	}
	DropdownChip(
		expanded = open,
		onExpandRequest = { setOpen(true) },
		contentDescription = contentDescription,
		modifier = modifier,
		icon = icon,
		enabled = enabled,
	) {
		Popup(
			popupPositionProvider = BelowAnchorPositionProvider,
			onDismissRequest = { setOpen(false) },
			properties = PopupProperties(focusable = true),
		) {
			CompositionLocalProvider(LocalPopupDismissOwned provides true) {
				Surface(color = colors.menuBackground, shape = LocalUmamoShapes.current.medium) {
					// Intrinsic width so the panel hugs its widest row rather than needing a magic dp.
					Column(modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 4.dp), content = content)
				}
			}
		}
	}
}