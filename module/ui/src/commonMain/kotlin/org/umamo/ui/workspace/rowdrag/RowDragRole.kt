package org.umamo.ui.workspace.rowdrag

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * The part a row plays in a drag: the row in hand, the row a release would drop on, or neither.  What a
 * row draws for a drag - the fade, the ring, the insertion line - follows from its part alone.
 */
@Immutable
sealed interface RowDragRole<out Band> {
	/** The band a release would land in, or null for a row that is no target. */
	val bandOrNull: Band?

	/** The row plays no part: no drag is in flight, or the drag is elsewhere. */
	data object Idle : RowDragRole<Nothing> {
		override val bandOrNull: Nothing? get() = null
	}

	/** The row is the one in hand. */
	data object Dragged : RowDragRole<Nothing> {
		override val bandOrNull: Nothing? get() = null
	}

	/**
	 * The row is where a release would drop.
	 *
	 * @property Band band Where on the row the drop would land, as the space's drop rule names it.
	 */
	data class Target<Band>(val band: Band) : RowDragRole<Band> {
		override val bandOrNull: Band get() = band
	}
}

/**
 * The part this row plays in the drag, as state that changes only when the row's own part does.
 *
 * The drag state changes on every pointer move, and a row that read it while composing would run again
 * on each.  Derived state reads it on every move and passes on only a change of its own value: a row runs
 * when it is picked up or put down, when the target arrives on it or leaves it, and when the pointer
 * crosses from one of its bands to another.  Read it in the scope that draws the row's drag feedback.
 *
 * @param String   rowKey  The row's stable key.
 * @param Function bandFor The space's drop rule for this row, as [RowDragController.roleOf] takes it.
 *   Read through state, so a rule built again for the same row starts nothing over.
 * @return State<RowDragRole> The row's part.
 */
@Composable
fun <Payload : Any, Band : Any> RowDragController<Payload>.rememberRowDragRole(
	rowKey: String,
	bandFor: (dragged: Payload, fraction: Float) -> Band?,
): State<RowDragRole<Band>> {
	val currentBandFor = rememberUpdatedState(bandFor)

	return remember(this, rowKey) { derivedStateOf { roleOf(rowKey) { dragged, fraction -> currentBandFor.value(dragged, fraction) } } }
}