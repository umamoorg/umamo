package org.umamo.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The hairline seam between two butted fields in a vertical [FieldStack].  The panel surface behind the
 * stack shows through this gap, so the division follows the theme (a dark seam over a dark island, a light
 * seam over a light one) rather than being a drawn border - the vertical analogue of a ButtonGroup's
 * SEGMENT_GAP, keeping both stacked runs visually consistent.
 */
val FIELD_STACK_SEAM = 1.dp

/**
 * Lays out a vertical run of butted fields as one joined group, Blender-style: the fields sit flush with a
 * [FIELD_STACK_SEAM] hairline between them, and only the run's outer corners round.  Mirrors [ButtonGroup]
 * for the vertical axis - the container owns the seams and hands each row its [StackPosition] (via
 * [stackPositionOf]), so a caller stacks fields without hand-threading First / Middle / Last and can never
 * get an end wrong.  Each row is a composable taking the position it should pass to its field's
 * stackPosition (so the row's own label / control layout stays the caller's concern).
 *
 * 縦に連結したフィールド群を 1 つのグループとして配置する。各行に StackPosition を渡し、両端だけ角丸にする。
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
