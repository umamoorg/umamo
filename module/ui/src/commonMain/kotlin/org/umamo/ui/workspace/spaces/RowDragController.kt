package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * A panel's row drag-and-drop state, shared by every row so the rows stay thin: each reports its
 * window bounds and its drag gestures here, and the space reads the current drag / drop target to
 * draw the indicator and to dispatch the move on release.  Window coordinates throughout, so a
 * pointer that has left the dragged row still hit-tests against every visible row.  Rows are
 * identified by a stable string key (the outliner's node id, the parameters panel's rowKey); what a
 * drag relocates is the panel-specific [Payload] (its kind decides the legal drop bands).
 *
 * Holds only transient interaction state (never document state), so it is remembered per panel
 * instance and discarded with it.
 *
 * パネルの行ドラッグ＆ドロップ状態。各行が境界と操作を報告し、空間が落下先を読んで指標表示と移動
 * 適用を行う。行は安定した文字列キーで識別し、ドラッグ対象はパネル固有の Payload。
 */
class RowDragController<Payload : Any> {
	/** The row key currently being dragged, or null when no drag is in progress. */
	var draggingKey: String? by mutableStateOf(null)
		private set

	/** What the drag relocates (its kind decides the legal drop bands), or null when no drag is in progress. */
	var draggedPayload: Payload? by mutableStateOf(null)
		private set

	/** The drag pointer's current X in window coordinates (for the floating drag label that follows the cursor). */
	var dragWindowX: Float by mutableStateOf(0f)
		private set

	/** The drag pointer's current Y in window coordinates (meaningful only while [draggingKey] is set). */
	var dragWindowY: Float by mutableStateOf(0f)
		private set

	// Each visible row's window bounds, by row key, for hit-testing the drop target.  A SnapshotStateMap so
	// the drop-indicator overlay recomposes as rows lay out / the pointer moves.
	private val rowBounds = mutableStateMapOf<String, Rect>()

	/**
	 * Records a row's current window bounds (called from its onGloballyPositioned).
	 *
	 * @param String rowKey The row's stable key.
	 * @param Rect bounds The row's bounds in window coordinates.
	 */
	fun reportBounds(rowKey: String, bounds: Rect) {
		rowBounds[rowKey] = bounds
	}

	/**
	 * Drops a row's bounds when it leaves composition (scrolled off), so the hit-test never targets a row
	 * that is no longer visible.
	 *
	 * @param String rowKey The row's stable key.
	 */
	fun clearBounds(rowKey: String) {
		rowBounds.remove(rowKey)
	}

	/**
	 * Begins a drag of [rowKey].  Seeds the pointer position with the press point so the drop target
	 * starts on the dragged row itself (i.e. no target) rather than wherever the previous drag ended -
	 * so a fresh grab never shows a stale drop, and a release without moving is a no-op.
	 *
	 * @param String rowKey The row being picked up.
	 * @param Payload payload What the drag relocates.
	 * @param Float windowX The press X in window coordinates.
	 * @param Float windowY The press Y in window coordinates.
	 */
	fun start(rowKey: String, payload: Payload, windowX: Float, windowY: Float) {
		draggingKey = rowKey
		draggedPayload = payload
		dragWindowX = windowX
		dragWindowY = windowY
	}

	/**
	 * Updates the drag pointer's window position.
	 *
	 * @param Float windowX The pointer X in window coordinates.
	 * @param Float windowY The pointer Y in window coordinates.
	 */
	fun drag(windowX: Float, windowY: Float) {
		dragWindowX = windowX
		dragWindowY = windowY
	}

	/** Ends the current drag (clears the drag state); the caller reads the target / fraction first. */
	fun end() {
		draggingKey = null
		draggedPayload = null
	}

	/**
	 * Aborts the in-flight drag without dropping.  Compose's drag gestures cannot be aborted from
	 * outside mid-stream, so cancelling resets the controller state instead: with [draggingKey] null
	 * the remaining onDrag updates and the release's drop dispatch become no-ops ([dropTargetKey]
	 * returns null, so the drop application early-returns), and the floating drag label and drop
	 * indicators - all keyed off [isDragging] - disappear at once.
	 */
	fun cancel() {
		end()
	}

	/** True while a drag is in progress. */
	val isDragging: Boolean get() = draggingKey != null

	/**
	 * The row key the drag pointer is currently over (excluding the dragged row itself), or null when
	 * over empty space.  Uses a half-open band [top, bottom) so a pointer exactly on a shared row edge
	 * belongs to the lower row only - one unambiguous target, never two.
	 *
	 * @return String? The hovered row's key, or null.
	 */
	val dropTargetKey: String?
		get() {
			val dragged = draggingKey ?: return null
			return rowBounds.entries
				.firstOrNull { (rowKey, bounds) -> rowKey != dragged && dragWindowY >= bounds.top && dragWindowY < bounds.bottom }
				?.key
		}

	/**
	 * The pointer's vertical position within the drop-target row, 0 (top edge) .. 1 (bottom edge), or null
	 * when there is no target.  The space reads this to decide before / into / after on release, and a row
	 * reads its own band to draw the matching insertion edge.
	 *
	 * @return Float? The 0..1 fraction down the target row, or null.
	 */
	val dropTargetFraction: Float?
		get() {
			val target = dropTargetKey ?: return null
			val bounds = rowBounds[target] ?: return null
			val height = bounds.bottom - bounds.top
			if (height <= 0f) {
				return 0.5f
			}
			return ((dragWindowY - bounds.top) / height).coerceIn(0f, 1f)
		}
}
