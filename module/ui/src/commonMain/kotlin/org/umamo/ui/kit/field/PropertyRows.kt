package org.umamo.ui.kit.field

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/*
 * The labelled property rows the Properties area, the operation strip, the viewport overlays popover, and the
 * preferences window share: a two-column grid whose left half is the right-aligned label and whose right half
 * is the control, so a column of rows aligns and every control spans one width.  The description rides on the
 * label (or on the checkbox, whose label sits beside its box) and never on the whole row: nested tooltip areas
 * all fire at once, so a row-wide one would stack a second card over a control's own button tooltips.  A row
 * knows no document; whoever owns it resolves the label, the description, and any keyed state.
 */

/** The drawn size of a [PropertyFieldRow]'s optional label icon. */
private val PROPERTY_LABEL_ICON_SIZE = 14.dp

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
 * fills the right half.  The control should [Modifier.fillMaxWidth] so it fills its half.
 *
 * [trailingGutter] shrinks the control only, so a row reserving room for an out-of-row adornment (an
 * [AspectLockFieldPair]'s lock) still lines up with the plain rows above and below it.
 *
 * @param String label The localized field label.
 * @param String description What the field does, as the label's tooltip; blank attaches none.
 * @param UmamoIcon? icon A glyph drawn just before the label, or null for a text-only label.
 * @param Dp trailingGutter Space reserved after the control for an out-of-row adornment (0 for none).
 * @param Modifier modifier The layout modifier, applied to the row.
 * @param Function control The editable control (a fillMaxWidth NumberField, SelectField, etc.).
 */
@Composable
fun PropertyFieldRow(
	label: String,
	description: String = "",
	icon: UmamoIcon? = null,
	trailingGutter: Dp = 0.dp,
	modifier: Modifier = Modifier,
	control: @Composable () -> Unit,
) {
	Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		// The whole left half is the hover target, not just the glyphs, so a short label is easy to find.
		Tooltip(text = description, modifier = Modifier.weight(1f).padding(end = 8.dp)) {
			PropertyRowLabel(text = label, icon = icon)
		}
		Box(modifier = Modifier.weight(1f).padding(end = trailingGutter)) {
			control()
		}
	}
}

/**
 * A property checkbox row: the checkbox (box plus its own label) sits in the right half like every other
 * field, matching Blender, where a lone toggle occupies the field column.  The left half is empty unless a
 * [heading] names the row: a group of toggles under one heading is its first row with the heading and the
 * rows under it without.
 *
 * @param Boolean checked The current state.
 * @param Function onCheckedChange The toggle callback.
 * @param String label The checkbox's own label (drawn to the right of the box).
 * @param String description What the toggle does, as the checkbox's tooltip; blank attaches none.
 * @param String heading The label in the left half, right-aligned like a field row's; blank leaves it empty.
 * @param KeyedFieldState keyState The keyform state to tint the box's border with.
 * @param Boolean enabled When false the state still shows but dimmed, and a click does nothing.
 */
@Composable
fun PropertyCheckboxRow(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	label: String,
	description: String = "",
	heading: String = "",
	keyState: KeyedFieldState = KeyedFieldState.None,
	enabled: Boolean = true,
) {
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		if (heading.isBlank()) {
			Spacer(modifier = Modifier.weight(1f))
		} else {
			Box(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
				PropertyRowLabel(text = heading, icon = null)
			}
		}

		Box(modifier = Modifier.weight(1f)) {
			Tooltip(text = description) {
				Checkbox(checked = checked, onCheckedChange = onCheckedChange, label = label, keyState = keyState, enabled = enabled)
			}
		}
	}
}

/**
 * The right-aligned label a property row's left half shows, led by an optional glyph.
 *
 * @param String text The already-localized label.
 * @param UmamoIcon? icon A glyph drawn just before the text, or null for none.
 */
@Composable
private fun PropertyRowLabel(text: String, icon: UmamoIcon?) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current

	if (icon == null) {
		Text(text = text, style = typography.bodySmall, color = colors.text, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())

		return
	}

	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
		// Decorative: the label beside it carries the meaning, so the glyph has no semantics of its own.
		Canvas(modifier = Modifier.size(PROPERTY_LABEL_ICON_SIZE)) {
			drawIcon(icon, colors.text)
		}
		Spacer(modifier = Modifier.width(4.dp))
		Text(text = text, style = typography.bodySmall, color = colors.text, textAlign = TextAlign.End)
	}
}