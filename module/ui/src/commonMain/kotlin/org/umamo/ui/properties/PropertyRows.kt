package org.umamo.ui.properties

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Checkbox
import org.umamo.ui.kit.Text
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography

/*
 * The shared row primitives every Properties section is built from: the label + control grid, the read-only
 * line, the checkbox row, and the numeric ranges those controls clamp to.  Sections live in the per-tab
 * files beside this one; anything used by more than one of them belongs here.
 */

/**
 * One read-only "label: value" property line.
 *
 * @param String text The composed line text.
 */
@Composable
internal fun PropertyLine(text: String) {
	Text(text = text, style = LocalUmamoTypography.current.bodySmall, modifier = Modifier.padding(top = 1.dp, bottom = 1.dp))
}

/** An unbounded float range: a numeric field that clamps nothing and draws no magnitude fill. */
internal val UNBOUNDED_RANGE = Float.NEGATIVE_INFINITY..Float.POSITIVE_INFINITY

/** A half-open float range (min set, no max): clamps below and draws no fill (needs both bounds). */
internal val POSITIVE_RANGE = 1f..Float.POSITIVE_INFINITY

/**
 * The clamp for a drawable's world extent.  Deliberately NOT [POSITIVE_RANGE]: a canvas is measured in
 * whole pixels so a floor of 1 is meaningful there, but a drawable extent is in world units and can
 * legitimately be a fraction - clamping it to 1 would silently double a typed 0.5.  The floor is the
 * smallest value the row's one-decimal display can actually show, which keeps the number in the field
 * honest while still refusing the zero (collapse) and negative (mirror) cases.
 */
internal val DRAWABLE_EXTENT_RANGE = 0.1f..Float.POSITIVE_INFINITY

/**
 * Space the Size rows reserve at their right edge for the aspect lock that overlays it: the 20dp icon
 * button plus a little breathing room.  Keeping it a named constant is what ties the reservation and the
 * overlaid control to the same width - if they drift, the lock either overlaps the field or floats away.
 */
internal val ASPECT_LOCK_GUTTER = 24.dp

/**
 * A labelled Properties field row, Blender-style: the right-aligned label takes the left half and the
 * control fills the right half, so a column of rows aligns and every field spans a consistent width.  The
 * control should [Modifier.fillMaxWidth] so it fills its half.
 *
 * [trailingGutter] reserves space at the RIGHT EDGE OF THE CONTROL for an adornment that sits outside the
 * row (the Size stack's aspect lock).  It shrinks the control only - the label column keeps its half of the
 * full row width, so a row with a gutter still lines up with the plain rows above and below it.  Reserving
 * the space here rather than wrapping the whole row in a narrower box is the difference between the field
 * shrinking and the entire two-column grid shifting.
 *
 * @param String label The localized field label.
 * @param Dp trailingGutter Space reserved after the control for an out-of-row adornment (0 for none).
 * @param Function control The editable control (a fillMaxWidth NumberField, SelectField, etc.).
 */
@Composable
internal fun PropertyFieldRow(label: String, trailingGutter: Dp = 0.dp, control: @Composable () -> Unit) {
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = label,
			style = LocalUmamoTypography.current.bodySmall,
			color = LocalUmamoColors.current.text,
			textAlign = TextAlign.End,
			modifier = Modifier.weight(1f).padding(end = 8.dp),
		)
		Box(modifier = Modifier.weight(1f).padding(end = trailingGutter)) {
			control()
		}
	}
}

/**
 * A Properties checkbox row: the checkbox (box plus its own label) sits in the right half like every other
 * field, with the left label column left empty - matching Blender, where a lone toggle occupies the field
 * column.  A group of related toggles can carry a left-column heading later.
 *
 * @param Boolean checked The current state.
 * @param Function onCheckedChange The toggle callback.
 * @param String label The checkbox's own label (drawn to the right of the box).
 */
@Composable
internal fun PropertyCheckboxRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Spacer(modifier = Modifier.weight(1f))
		Box(modifier = Modifier.weight(1f)) {
			Checkbox(checked = checked, onCheckedChange = onCheckedChange, label = label)
		}
	}
}

/**
 * A labelled block wrapping a relation list, since a tall list does not fit the two-column field row.
 *
 * @param String label The block's localized label.
 * @param Function content The list to draw beneath it.
 */
@Composable
internal fun RelationListBlock(label: String, content: @Composable () -> Unit) {
	Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Text(text = label, style = LocalUmamoTypography.current.bodySmall, color = LocalUmamoColors.current.text)
		content()
	}
}
