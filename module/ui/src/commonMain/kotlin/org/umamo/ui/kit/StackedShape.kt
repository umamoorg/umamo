package org.umamo.ui.kit

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * A control's position within a butted run of stacked controls, driving which corners it rounds and which
 * it squares against a neighbour.  [Single] is a standalone control (all corners rounded).
 */
enum class StackPosition {
	Single,
	First,
	Middle,
	Last,
}

/** The axis a butted run of controls is laid along: a horizontal Row or a vertical Column. */
enum class StackAxis {
	Horizontal,
	Vertical,
}

/**
 * Resolves an item's [StackPosition] from its index within a run of [count] items: the sole item is
 * [StackPosition.Single], the ends are First / Last, and the interior is Middle.
 *
 * @param Int index The item's position in the run.
 * @param Int count The number of items in the run.
 * @return StackPosition The item's position role.
 */
fun stackPositionOf(index: Int, count: Int): StackPosition =
	when {
		count <= 1 -> StackPosition.Single
		index <= 0 -> StackPosition.First
		index >= count - 1 -> StackPosition.Last
		else -> StackPosition.Middle
	}

/**
 * Shapes one control in a butted run so the run reads as a single control with hairline seams: the run's
 * outer corners keep [groupShape]'s rounding while the corners shared with a neighbour are squared.  A
 * horizontal run (ButtonGroup) squares the leading / trailing edges; a vertical run (a stacked numeric
 * field group) squares the top / bottom edges.  RoundedCornerShape's start / end corners already mirror
 * under RTL, so the first item stays the visual leading end either way.
 *
 * @param CornerBasedShape groupShape The rounding applied to the run's outer corners.
 * @param StackPosition position The item's position role in the run.
 * @param StackAxis axis The run's layout axis.
 * @return Shape The item's corner shape.
 */
fun stackedShape(groupShape: CornerBasedShape, position: StackPosition, axis: StackAxis): Shape =
	when (position) {
		StackPosition.Single -> groupShape
		StackPosition.Middle -> RectangleShape
		StackPosition.First ->
			when (axis) {
				StackAxis.Horizontal -> groupShape.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize)
				StackAxis.Vertical -> groupShape.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
			}
		StackPosition.Last ->
			when (axis) {
				StackAxis.Horizontal -> groupShape.copy(topStart = ZeroCornerSize, bottomStart = ZeroCornerSize)
				StackAxis.Vertical -> groupShape.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)
			}
	}
