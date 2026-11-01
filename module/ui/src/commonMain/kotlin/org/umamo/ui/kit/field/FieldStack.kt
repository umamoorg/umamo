package org.umamo.ui.kit.field

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.StackPosition
import org.umamo.ui.kit.stackPositionOf

/**
 * The hairline seam between two butted fields in a vertical [FieldStack].  The panel surface behind the
 * stack shows through this gap, so the division follows the theme (a dark seam over a dark island, a light
 * seam over a light one) rather than being a drawn border - the vertical analogue of a ButtonGroup's
 * SEGMENT_GAP, keeping both stacked runs visually consistent.
 */
val FIELD_STACK_SEAM = 1.dp

/**
 * Lays out a vertical run of butted fields as one joined group, Blender-style: the fields sit flush with a
 * [FIELD_STACK_SEAM] hairline between them, and only the run's outer corners round.  The vertical
 * [org.umamo.ui.kit.button.ButtonGroup]: the container hands each row the [StackPosition] to pass to its
 * field, so a caller never hand-threads First / Middle / Last.
 *
 * @param List rows The stacked rows in top-to-bottom order; each is given its position in the run.
 * @param Modifier modifier The layout modifier for the enclosing column.
 */
@Composable
fun FieldStack(
	rows: List<@Composable (StackPosition) -> Unit>,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier) {
		rows.forEachIndexed { rowIndex, row ->
			if (rowIndex > 0) {
				Spacer(modifier = Modifier.height(FIELD_STACK_SEAM))
			}

			row(stackPositionOf(rowIndex, rows.size))
		}
	}
}