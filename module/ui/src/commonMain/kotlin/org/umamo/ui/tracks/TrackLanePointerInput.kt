package org.umamo.ui.tracks

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/**
 * The lane's owner callbacks, bundled so the pointer loops read every one of them through ONE latest-state
 * holder at event time.
 *
 * Read through a holder rather than keying the gesture loops on them, and that is load-bearing rather than
 * tidy: a pointerInput RESTARTS when a key changes, and these change identity the moment the SELECTION does.
 * Keying on them would let an owner that clears the selection on the press (exactly what pressing empty
 * track does) tear its own gesture down mid-stroke: the drag would freeze at the press point with no
 * release ever arriving, so nothing would commit.  Only the pixel-to-domain mapping keys the loops, because
 * that genuinely invalidates a stroke in progress.  A plain class rather than a data class: lambda
 * equality is meaningless, and the holder is rebuilt every recomposition anyway.
 *
 * @property Function? onMarkClick Invoked when a click lands on a mark, with whether Shift was held.
 * @property Function? onTrackScrub Invoked as an empty-track drag moves, with the gesture's Shift state.
 * @property Function? onTrackScrubEnd Invoked when an empty-track drag is released.
 * @property Function? onMarkDrag Invoked on every move of a mark drag.
 * @property Function? onMarkDragEnd Invoked when a mark drag is released.
 * @property Function? onLaneHover Reports the live hit under the pointer, or null on exit.
 */
internal class TrackLaneCallbacks(
	val onMarkClick: ((TrackRow, TrackKeyMark, Boolean) -> Unit)?,
	val onTrackScrub: ((TrackRow, Float, Boolean) -> Unit)?,
	val onTrackScrubEnd: ((TrackRow, Float) -> Unit)?,
	val onMarkDrag: ((TrackRow, TrackKeyMark, Float) -> Unit)?,
	val onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)?,
	val onLaneHover: ((TrackRow, TrackLaneHit?) -> Unit)?,
)

/**
 * The in-flight mark drag the lane draws at the pointer: the mark under the hand and its live domain value,
 * so the mark follows the pointer before the model is touched.  Snapshot state, so the draw pass follows
 * the drag; the move itself is committed on release, since a per-frame commit would push an undo step for
 * every pixel of the drag.
 */
internal class TrackLaneDragFeedback {
	/** The mark being dragged, or null between drags. */
	var mark: TrackKeyMark? by mutableStateOf(null)

	/** Where the dragged mark is drawn, in domain units. */
	var value: Float by mutableStateOf(0f)
}

/**
 * The lane's hover loop: reports the live hit under the pointer, or null on exit, and consumes nothing.
 *
 * The hit is reported as the pointer MOVES, not merely on enter, because where along the lane the pointer
 * sits is the whole point: an owner's command aimed at a lane means "here", and a row-level hover could
 * only say "somewhere on this row".
 *
 * @param TrackRow row The lane's row.
 * @param TrackAxis axis The domain.
 * @param Float markRadiusPx Half-extent of a mark and the lane's end inset, in pixels.
 * @param State<List<TrackKeyMark>> editableMarks The marks a hit may resolve to, read per event.
 * @param State<TrackLaneCallbacks> callbacks The owner's callbacks, read per event.
 */
internal suspend fun PointerInputScope.trackLaneHoverLoop(
	row: TrackRow,
	axis: TrackAxis,
	markRadiusPx: Float,
	editableMarks: State<List<TrackKeyMark>>,
	callbacks: State<TrackLaneCallbacks>,
) {
	awaitPointerEventScope {
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull() ?: continue
			when (event.type) {
				PointerEventType.Exit -> callbacks.value.onLaneHover?.invoke(row, null)
				PointerEventType.Enter, PointerEventType.Move -> {
					val value = domainAt(change.position.x, axis, size.width, markRadiusPx)
					val mark =
						nearestMark(editableMarks.value, value, pickTolerance(axis, size.width, markRadiusPx))
					callbacks.value.onLaneHover?.invoke(row, TrackLaneHit(row, value, mark))
				}

				else -> Unit
			}
		}
	}
}

/**
 * The lane's tap / scrub / drag loop: one gesture at a time, resolved from the raw pointer stream into a
 * click on a mark, a drag of a mark, or a scrub of empty track, with the owner told at the press, on every
 * move past the slop, and at the release.
 *
 * @param TrackRow row The lane's row.
 * @param TrackAxis axis The domain.
 * @param Float markRadiusPx Half-extent of a mark and the lane's end inset, in pixels.
 * @param Float touchSlop How far a press must travel before it is a drag rather than a click.
 * @param State<List<TrackKeyMark>> editableMarks The marks a press may land on, read at the press.
 * @param State<TrackLaneCallbacks> callbacks The owner's callbacks, read at each use.
 * @param TrackLaneDragFeedback drag The drag feedback the lane draws, written as a mark drag moves.
 */
internal suspend fun PointerInputScope.trackLaneGestureLoop(
	row: TrackRow,
	axis: TrackAxis,
	markRadiusPx: Float,
	touchSlop: Float,
	editableMarks: State<List<TrackKeyMark>>,
	callbacks: State<TrackLaneCallbacks>,
	drag: TrackLaneDragFeedback,
) {
	awaitEachGesture {
		val down = awaitFirstDown(requireUnconsumed = false)
		// A secondary (right) press belongs to the context menu and a tertiary (middle) one to the sheet's
		// pan.  Without this guard either would also run the tap path on its way past: scrubbing the
		// playhead if the press missed every mark, or selecting the mark if it landed on one - instead of
		// only reaching the gesture it actually means.
		if (currentEvent.buttons.isSecondaryPressed || currentEvent.buttons.isTertiaryPressed) {
			return@awaitEachGesture
		}
		// A sheet mounted with no handlers at all is read-only; it must not show drag feedback for a gesture
		// that can go nowhere.  Checked per gesture rather than keying the loop, because the handlers change
		// identity on every selection change and keying on them tears the stroke down.
		if (callbacks.value.onMarkClick == null && callbacks.value.onTrackScrub == null && callbacks.value.onMarkDragEnd == null) {
			return@awaitEachGesture
		}
		// Sampled at the PRESS: the modifier is part of what the gesture meant when it began, and a key
		// released between press and release would otherwise silently change an extend-click into a
		// replace-click.
		val additive = currentEvent.keyboardModifiers.isShiftPressed
		val pressedValue = domainAt(down.position.x, axis, size.width, markRadiusPx)
		// Summary marks ARE addressable (summarizedMarks keeps them editable and renumbers them to a summary
		// ordinal), so a press on one starts a drag like any other; it is the owner that maps that ordinal
		// back to the whole set of child keys beneath it.  Only a non-editable mark falls through to the
		// scrub path.
		// Sampled once at the press, so the marks a stroke hit-tests against cannot shift under it - the
		// drag is manipulating the mark it started on, whatever the list does meanwhile.
		val hitMark =
			nearestMark(editableMarks.value, pressedValue, pickTolerance(axis, size.width, markRadiusPx))
		var dragging = false
		var releaseValue = pressedValue
		// The window a mark drag may move within: the axis, end to end.  Read once per gesture so the bound
		// cannot drift under the stroke; the axis is a key of this loop, so a changed axis restarts the
		// gesture rather than moving the wall.
		val dragBounds = dragBoundsOf(axis)
		// A press on empty track scrubs immediately, so the playhead lands under the pointer on the way
		// down rather than only on release - the affordance a timeline ruler has, applied across the whole
		// track region.
		//
		// CLAMPED to the axis, as a mark drag is clamped to its drag bounds.  A value outside the axis is
		// not merely odd: the axis is the whole range the owner can show, so a playhead past its end would
		// land on nothing.  The clamp is the same one the pointer position is drawn with, so the playhead
		// stops where the pointer stops.
		val scrubBounds = minOf(axis.start, axis.end)..maxOf(axis.start, axis.end)
		if (hitMark == null) {
			callbacks.value.onTrackScrub?.invoke(row, pressedValue.coerceIn(scrubBounds), additive)
		}
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull { candidate -> candidate.id == down.id } ?: break
			if (!change.pressed) {
				break
			}
			if (change.positionChange().x != 0f || change.positionChange().y != 0f) {
				if (!dragging && abs(change.position.x - down.position.x) > touchSlop) {
					dragging = true
					if (hitMark != null) {
						drag.mark = hitMark
					}
				}
				if (dragging) {
					val pointerValue = domainAt(change.position.x, axis, size.width, markRadiusPx)
					// A MARK drag is clamped as it moves rather than on release: one that follows the
					// pointer past its bound and then snaps back reads as a rejected edit, where stopping
					// at the wall reads as the wall being there.  An empty-track scrub has no wall of its
					// own - it moves the playhead, clamped only to the axis.
					releaseValue =
						if (hitMark == null) {
							pointerValue.coerceIn(scrubBounds)
						} else {
							pointerValue.coerceIn(dragBounds)
						}
					if (hitMark == null) {
						callbacks.value.onTrackScrub?.invoke(row, releaseValue, additive)
					} else {
						drag.value = releaseValue
						// Reported every move, not only on release: an owner previewing a group drag needs
						// the live value, and it is the owner - not the lane - that knows what travels with it.
						callbacks.value.onMarkDrag?.invoke(row, hitMark, releaseValue)
					}
					change.consume()
				}
			}
		}
		when {
			hitMark != null && dragging -> callbacks.value.onMarkDragEnd?.invoke(row, hitMark, releaseValue)
			hitMark != null -> callbacks.value.onMarkClick?.invoke(row, hitMark, additive)
			else -> callbacks.value.onTrackScrubEnd?.invoke(row, releaseValue)
		}
		drag.mark = null
	}
}