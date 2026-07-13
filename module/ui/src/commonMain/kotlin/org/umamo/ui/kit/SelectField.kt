package org.umamo.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography

/** The guaranteed gap between the field's current-value label and its trailing chevron. */
private val SELECT_FIELD_TRAILING_GAP = 12.dp

/**
 * A compact dropdown select: a bordered, clickable box showing the current selection's label with a
 * trailing chevron, opening a [Menu] of the options beneath it (the one menu surface the menu bar and
 * context menus also use).  The reusable single-choice control for forms - the header chips are the
 * separate [DropdownChip] anatomy, but settings rows and future form callers share this.
 *
 * The menu is focusable, so it owns its own outside-click / Escape dismissal independently of any host
 * key dispatch: pressing Escape with the menu open closes the menu, not the surrounding overlay.
 *
 * 単一選択のドロップダウン。現在値を枠付きの行に表示し、クリックで選択肢メニューを真下に開く共通部品。
 *
 * @param T          selected The currently selected option.
 * @param List       options  The options to choose from, in display order.
 * @param Function   label    Resolves an option to its display string (localized chrome or verbatim data).
 * @param Function   onSelect Called with the chosen option (a no-op selection of the current value is fine).
 * @param Modifier   modifier Layout modifier for the field box.
 */
@Composable
fun <T> SelectField(
	selected: T,
	options: List<T>,
	label: (T) -> String,
	onSelect: (T) -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	val shapes = LocalUmamoShapes.current
	var expanded by remember { mutableStateOf(false) }
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Box(modifier = modifier) {
		Row(
			modifier =
				Modifier
					.clip(shapes.small)
					.background(colors.controlBackground)
					.border(BorderStroke(1.dp, if (hovered) colors.panelBorderHover else colors.controlBorder), shapes.small)
					.clickable(interactionSource = interaction, indication = null) { expanded = !expanded }
					.padding(horizontal = 10.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(text = label(selected), style = typography.labelMedium, color = colors.text)
			Spacer(modifier = Modifier.width(SELECT_FIELD_TRAILING_GAP))
			Text(text = "▾", style = typography.labelMedium, color = colors.textMuted)
		}
		if (expanded) {
			Menu(
				items =
					options.map { option ->
						MenuItem.Action(
							label = label(option),
							onSelect = { onSelect(option) },
						)
					},
				onDismissRequest = { expanded = false },
				positionProvider = BelowAnchorPositionProvider,
			)
		}
	}
}
