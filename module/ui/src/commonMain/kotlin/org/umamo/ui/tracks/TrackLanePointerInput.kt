package org.umamo.ui.tracks

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.positionChange
import org.umamo.ui.kit.field.KEY_SNAP_RADIUS
import org.umamo.ui.kit.field.snapToNearestWithin
import kotlin.math.abs

/**
 * The lane's owner callbacks, bundled so the pointer loops read every one of them through ONE latest-state
 * holder at event time.
 *
 * Read through a holder rather than keying the gesture loops on them, and that is load-bearing rather than
 * tidy: a pointerInput RESTARTS when a key changes, and these change identity the moment the SELECTION does.
 * Keying on them would let an owner that clears the selection on the press (exactly what pressing empty
 * track does) tear its own gesture down mid-stroke: the drag would freeze at the press point with no
 * release ever arriving, so nothing would commit.  The axis reaches the gesture loop the same way, so a zoom
 * or pan moves the window under a stroke rather than ending it; only the lane's row and mark radius key that
 * loop.  The hover loop holds no stroke, so it still keys on the axis.  A plain class rather than a data
 * class: lambda equality is meaningless, and the holder is rebuilt every recomposition anyway.
 *
 * @property Function? onMarkClick Invoked when a click lands on a mark, with whether Shift was held.
 * @property Function? onTrackScrub Invoked as an empty-track drag moves, with the gesture's Shift state.
 * @property Function? onTrackScrubEnd Invoked when an empty-track drag is released.
 * @property Function? onMarkDrag Invoked on every move of a mark drag.
 * @property Function? onMarkDragEnd Invoked when a mark drag is released; null leaves the marks in place, so a
 *   press on one is a click however far the pointer travels.
 * @property Function? onLaneHover Reports the live hit under the pointer, or null on exit.
 * @property Function? snapTargets The domain values a held-Ctrl drag latches onto, asked at most once per press,
 *   when Ctrl is first held, with the mark under it (null for empty track); null never snaps.
 */
internal class TrackLaneCallbacks(
	val onMarkClick: ((TrackRow, TrackKeyMark, Boolean) -> Unit)?,
	val onTrackScrub: ((TrackRow, Float, Boolean) -> Unit)?,
	val onTrackScrubEnd: ((TrackRow, Float) -> Unit)?,
	val onMarkDrag: ((TrackRow, TrackKeyMark, Float) -> Unit)?,
	val onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)?,
	val onLaneHover: ((TrackRow, TrackLaneHit?) -> Unit)?,
	val snapTargets: ((TrackRow, TrackKeyMark?) -> List<Float>)? = null,
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
				PointerEventType.Exit -> {
					callbacks.value.onLaneHover?.invoke(row, null)
				}

				PointerEventType.Enter, PointerEventType.Move -> {
					val value = domainAt(change.position.x, axis, size.width, markRadiusPx)
					val mark =
						nearestMark(editableMarks.value, value, pickTolerance(axis, size.width, markRadiusPx))
					callbacks.value.onLaneHover?.invoke(row, TrackLaneHit(row, value, mark))
				}

				else -> {}
			}
		}
	}
}

/**
 * The lane's tap / scrub / drag loop: one gesture at a time, resolved from the raw pointer stream into a
 * click on a mark, a drag of a mark, or a scrub of empty track, with the owner told at the press, on every
 * move past the slop, and at the release.  A lane with no mark-drag release handler never drags a mark: the press
 * stays a click, rather than drawing a drag at the pointer that can land nowhere and snaps back.
 *
 * @param TrackRow row The lane's row.
 * @param State<TrackAxis> axis The domain, read per event so a zoom or pan mid-stroke does not end it.
 * @param Float markRadiusPx Half-extent of a mark and the lane's end inset, in pixels.
 * @param Float touchSlop How far a press must travel before it is a drag rather than a click.
 * @param State<List<TrackKeyMark>> editableMarks The marks a press may land on, read at the press.
 * @param State<TrackLaneCallbacks> callbacks The owner's callbacks, read at each use.
 * @param TrackLaneDragFeedback drag The drag feedback the lane draws, written as a mark drag moves.
 */
internal suspend fun PointerInputScope.trackLaneGestureLoop(
	row: TrackRow,
	axis: State<TrackAxis>,
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
		// The press resolves against the window it landed in.
		val pressAxis = axis.value
		val pressedValue = domainAt(down.position.x, pressAxis, size.width, markRadiusPx)
		// Summary marks ARE addressable (summarizedMarks keeps them editable and renumbers them to a summary
		// ordinal), so a press on one starts a drag like any other; it is the owner that maps that ordinal
		// back to the whole set of child keys beneath it.  Only a non-editable mark falls through to the
		// scrub path.
		// Sampled once at the press, so the marks a stroke hit-tests against cannot shift under it - the
		// drag is manipulating the mark it started on, whatever the list does meanwhile.
		val hitMark =
			nearestMark(editableMarks.value, pressedValue, pickTolerance(pressAxis, size.width, markRadiusPx))
		// The owner's question is the press's, so what a drag can latch onto cannot change under it, but it is
		// asked only the first time Ctrl is held: a plain click or a Ctrl-less drag never pays for the walk over
		// the owner's rows.  CTRL, unlike Shift, is read off every event: snapping is a live toggle rather than
		// part of what the gesture meant, and on macOS, where Ctrl+click is the right click, Ctrl joins a drag
		// already under way.
		val snapTargetsAtPress = callbacks.value.snapTargets
		val snapTargets = lazy(LazyThreadSafetyMode.NONE) { snapTargetsAtPress?.invoke(row, hitMark).orEmpty() }
		val snapRadiusPx = KEY_SNAP_RADIUS.toPx()
		// Sampled at the press like Shift: whether the mark under it can move is part of what the gesture
		// meant when it began.  An empty-track press always may, since its drag is the scrub.
		val pressMayDrag = hitMark == null || callbacks.value.onMarkDragEnd != null
		// A mouse gesture begun on the primary button ends when that button comes up, even while the middle
		// button still pans the sheet.  Any other pointer ends when it lifts.
		val endsOnPrimaryRelease = down.type == PointerType.Mouse && currentEvent.buttons.isPrimaryPressed
		var dragging = false
		// A press on empty track scrubs immediately, so the playhead lands under the pointer on the way
		// down rather than only on release - the affordance a timeline ruler has, applied across the whole
		// track region.
		//
		// CLAMPED to the axis, as a mark drag is clamped to its drag bounds.  A value outside the axis is
		// not merely odd: the axis is the whole range the owner can show, so a playhead past its end would
		// land on nothing.  The clamp is the same one the pointer position is drawn with, so the playhead
		// stops where the pointer stops.
		var releaseValue =
			if (hitMark == null) {
				val pressBounds = dragBoundsOf(pressAxis)
				snapped(
					value = pressedValue,
					targets = snapTargets,
					reach = laneReach(pressAxis, size.width, markRadiusPx, snapRadiusPx),
					bounds = pressBounds,
					ctrlHeld = currentEvent.keyboardModifiers.isCtrlPressed,
				).coerceIn(pressBounds)
			} else {
				pressedValue
			}

		if (hitMark == null) {
			callbacks.value.onTrackScrub?.invoke(row, releaseValue, additive)
		}

		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull { candidate -> candidate.id == down.id } ?: break

			if (!change.pressed || (endsOnPrimaryRelease && !event.buttons.isPrimaryPressed)) {
				break
			}

			// Reads zero while the sheet's middle-drag pan holds the pointer, since the pan consumes every move.
			// The held value is right through a pan: it moves the content with the hand, so the key or the
			// playhead stays under the pointer, and a pan cannot carry a press past the slop into a drag.  Once
			// the middle button lets go, the next move resolves against the panned window.
			if (change.positionChange().x != 0f || change.positionChange().y != 0f) {
				if (!dragging && pressMayDrag && abs(change.position.x - down.position.x) > touchSlop) {
					dragging = true

					if (hitMark != null) {
						drag.mark = hitMark
					}
				}

				if (dragging) {
					// The window as it is NOW: a zoom or pan mid-stroke moves it, and the drag carries on within
					// the moved window, its walls included, rather than ending.
					val moveAxis = axis.value
					val moveBounds = dragBoundsOf(moveAxis)
					val pointerValue =
						snapped(
							value = domainAt(change.position.x, moveAxis, size.width, markRadiusPx),
							targets = snapTargets,
							reach = laneReach(moveAxis, size.width, markRadiusPx, snapRadiusPx),
							bounds = moveBounds,
							ctrlHeld = event.keyboardModifiers.isCtrlPressed,
						)
					// Clamped to the axis as it moves rather than on release: a mark that follows the pointer past
					// its bound and then snaps back reads as a rejected edit, where stopping at the wall reads as
					// the wall being there.  A scrub moves the playhead, which the same clamp keeps on the axis.
					releaseValue = pointerValue.coerceIn(moveBounds)

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

/**
 * [value] latched onto the nearest of [targets] inside [bounds] within [reach] while Ctrl is held, else
 * unchanged.  The targets are only resolved under Ctrl.
 *
 * @param Float value The pointer's domain value.
 * @param Lazy targets The press's snap targets.
 * @param Float reach The snap tolerance, in domain units.
 * @param ClosedFloatingPointRange bounds The visible window the value is then clamped to.
 * @param Boolean ctrlHeld Whether Ctrl is held on the event being handled.
 * @return Float The value to use.
 */
private fun snapped(
	value: Float,
	targets: Lazy<List<Float>>,
	reach: Float,
	bounds: ClosedFloatingPointRange<Float>,
	ctrlHeld: Boolean,
): Float = if (ctrlHeld) snapToNearestWithin(value, targets.value, reach, bounds) else value