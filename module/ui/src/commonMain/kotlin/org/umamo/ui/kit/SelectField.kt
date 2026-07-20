package org.umamo.ui.kit

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoIcon

/**
 * A single-choice form dropdown: the current selection's (optional) icon and label shown in a [DropdownChip]
 * styled as a field, opening a [Menu] of the options beneath it.  The one reusable select control for forms
 * - settings rows and the Properties panel share it; the header chips are the same [DropdownChip] in its
 * [DropdownChipStyle.Header] role, so there is one chip anatomy, not two.
 *
 * The popup is forced to at least the chip's own width (measured via [onSizeChanged]) so the option list
 * never opens narrower than the field - a value picked near the field's trailing edge stays under the
 * pointer instead of requiring an awkward reach.  The menu owns its outside-click / Escape dismissal.
 *
 * 単一選択のフォーム用ドロップダウン。現在値を DropdownChip（フィールド様式）に表示し、真下に選択肢メニューを開く。
 * メニュー幅はチップ幅以上に保つ。ヘッダのチップと同じ DropdownChip を共有する。
 *
 * @param T          selected The currently selected option.
 * @param List       options  The options to choose from, in display order.
 * @param Function   label    Resolves an option to its display string (localized chrome or verbatim data).
 * @param Function   onSelect Called with the chosen option (a no-op selection of the current value is fine).
 * @param Modifier   modifier Layout modifier for the field (pass fillMaxWidth to span a form column).
 * @param Function?  icon     Optional per-option leading glyph (e.g. a blend-mode icon), or null for none.
 */
@Composable
fun <T> SelectField(
	selected: T,
	options: List<T>,
	label: (T) -> String,
	onSelect: (T) -> Unit,
	modifier: Modifier = Modifier,
	icon: ((T) -> UmamoIcon?)? = null,
) {
	var expanded by remember { mutableStateOf(false) }
	var anchorWidth by remember { mutableStateOf(0.dp) }
	val density = LocalDensity.current
	DropdownChip(
		expanded = expanded,
		onExpandRequest = { expanded = !expanded },
		contentDescription = label(selected),
		modifier = modifier.onSizeChanged { size -> anchorWidth = with(density) { size.width.toDp() } },
		icon = icon?.invoke(selected),
		label = label(selected),
		style = DropdownChipStyle.Field,
	) {
		Menu(
			items =
				options.map { option ->
					MenuItem.Action(
						label = label(option),
						icon = icon?.invoke(option),
						onSelect = { onSelect(option) },
					)
				},
			onDismissRequest = { expanded = false },
			positionProvider = BelowAnchorPositionProvider,
			modifier = Modifier.widthIn(min = anchorWidth),
		)
	}
}
