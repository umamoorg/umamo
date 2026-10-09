package org.umamo.ui.kit.field

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/*
 * The labelled property rows the Properties area, the operation strip, and the viewport overlays popover
 * share: a two-column grid whose left half is the right-aligned label and whose right half is the control,
 * so a column of rows aligns and every control spans one width.  The description rides on the label (or on
 * the checkbox, whose label sits beside its box) and never on the whole row: nested tooltip areas all fire
 * at once, so a row-wide one would stack a second card over a control's own button tooltips.  A row knows
 * no document; whoever owns it resolves the label, the description, and any keyed state.
 */

/**
 * One read-only "label: value" property line.
 *
 * @param String text The composed line text.
 */
@Composable
fun PropertyLine(text: String) {
	Text(text = text, style = LocalUmamoTypography.current.bodySmall, modifier = Modifier.padding(top = 1.dp, bottom = 1.dp))
}

/**
 * A labelled property field row, Blender-style: the right-aligned label takes the left half and the control
 * fills the right half, so a column of rows aligns and every field spans a consistent width.  The control
 * should [Modifier.fillMaxWidth] so it fills its half.
 *
 * [description] is what the field does, shown as a tooltip over the LABEL HALF only.  The control keeps its
 * own hover: nested tooltip areas all fire at once, so one wrapping the whole row would stack a second card
 * over the icon buttons a relation or color field tooltips itself, and it would pop over a field mid-scrub.
 *
 * [trailingGutter] reserves space at the RIGHT EDGE OF THE CONTROL for an adornment that sits outside the
 * row (a size stack's aspect lock).  It shrinks the control only - the label column keeps its half of the
 * full row width, so a row with a gutter still lines up with the plain rows above and below it.  Reserving
 * the space here rather than wrapping the whole row in a narrower box is the difference between the field
 * shrinking and the entire two-column grid shifting.
 *
 * @param String label The localized field label.
 * @param String description What the field does, as the label's tooltip; blank attaches none.
 * @param Dp trailingGutter Space reserved after the control for an out-of-row adornment (0 for none).
 * @param Modifier modifier The layout modifier, applied to the row.
 * @param Function control The editable control (a fillMaxWidth NumberField, SelectField, etc.).
 */
@Composable
fun PropertyFieldRow(
	label: String,
	description: String = "",
	trailingGutter: Dp = 0.dp,
	modifier: Modifier = Modifier,
	control: @Composable () -> Unit,
) {
	Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		// The whole left half is the hover target, not just the glyphs, so a short label is easy to find.
		Tooltip(text = description, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
			Text(
				text = label,
				style = LocalUmamoTypography.current.bodySmall,
				color = LocalUmamoColors.current.text,
				textAlign = TextAlign.End,
				modifier = Modifier.fillMaxWidth(),
			)
		}
		Box(modifier = Modifier.weight(1f).padding(end = trailingGutter)) {
			control()
		}
	}
}

/**
 * A property checkbox row: the checkbox (box plus its own label) sits in the right half like every other
 * field, with the left label column left empty - matching Blender, where a lone toggle occupies the field
 * column.  A group of related toggles can carry a left-column heading later.
 *
 * The checkbox's label is the row's label, so [description] tooltips the checkbox itself; it has no hover
 * of its own to collide with.
 *
 * @param Boolean checked The current state.
 * @param Function onCheckedChange The toggle callback.
 * @param String label The checkbox's own label (drawn to the right of the box).
 * @param String description What the toggle does, as the checkbox's tooltip; blank attaches none.
 * @param KeyedFieldState keyState The keyform state to tint the box's border with.
 */
@Composable
fun PropertyCheckboxRow(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	label: String,
	description: String = "",
	keyState: KeyedFieldState = KeyedFieldState.None,
) {
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Spacer(modifier = Modifier.weight(1f))
		Box(modifier = Modifier.weight(1f)) {
			Tooltip(text = description) {
				Checkbox(checked = checked, onCheckedChange = onCheckedChange, label = label, keyState = keyState)
			}
		}
	}
}