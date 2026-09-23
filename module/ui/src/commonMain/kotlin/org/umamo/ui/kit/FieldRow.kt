package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon

/** The default width of a [FieldRow]'s label column, so a column of rows aligns its controls. */
val FIELD_ROW_LABEL_WIDTH = 180.dp

/** The default spacing between stacked [FieldRow]s in a section. */
val FIELD_ROW_SPACING = 14.dp

/**
 * The uniform height of a compact inline form control (a NumberField, a SelectField), so a column of mixed
 * controls lines up to one height rather than each sizing to its own text metrics.  A single source shared
 * by every field control keeps them exactly matched - change it here and the whole form follows.
 */
val FIELD_CONTROL_HEIGHT = 20.dp

/** The drawn size of a [FieldRow]'s optional label icon. */
private val FIELD_ROW_ICON_SIZE = 14.dp

/**
 * One labelled control row: a fixed-width label on the left and its [control] on the right, so the
 * controls down a section align.  The shared label:control primitive the settings sections and the
 * Properties panel build on (promoted here from the settings package so both reuse one row).
 *
 * An [icon] leads the label inside the same column, so the controls stay aligned with the rows that
 * have none.  It is decorative - the label beside it is the row's name - so it carries no semantics.
 *
 * @param String label The already-localized row label.
 * @param Modifier modifier The layout modifier for the row.
 * @param Dp labelWidth The label column width (narrow it for a tight panel).
 * @param UmamoIcon? icon A glyph drawn before the label, or null for a text-only label.
 * @param Function control The control composable (a SelectField, NumberField, Checkbox, etc.).
 */
@Composable
fun FieldRow(
	label: String,
	modifier: Modifier = Modifier,
	labelWidth: Dp = FIELD_ROW_LABEL_WIDTH,
	icon: UmamoIcon? = null,
	control: @Composable () -> Unit,
) {
	val colors = LocalUmamoColors.current
	Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Row(modifier = Modifier.width(labelWidth), verticalAlignment = Alignment.CenterVertically) {
			if (icon != null) {
				Canvas(modifier = Modifier.size(FIELD_ROW_ICON_SIZE)) {
					drawIcon(icon, colors.text)
				}
				Spacer(modifier = Modifier.width(6.dp))
			}
			Text(
				text = label,
				style = LocalUmamoTypography.current.bodyMedium,
				color = colors.text,
			)
		}
		Spacer(modifier = Modifier.width(12.dp))
		// The control fills the remaining width, so a fillMaxWidth field / dropdown spans the row and a
		// fixed-width control sits at the column start - both stay aligned down a section.
		Box(modifier = Modifier.weight(1f)) {
			control()
		}
	}
}