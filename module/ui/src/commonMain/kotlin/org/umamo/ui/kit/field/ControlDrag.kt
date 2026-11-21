package org.umamo.ui.kit.field

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** How far past an end a wrapping control's pointer pushes before the value comes in at the other end. */
val CONTROL_WRAP_DETENT = 8.dp

/**
 * Where a control sits on screen, for turning a warp target into screen pixels.  A plain holder rather than state:
 * only a warp reads it, and a layout pass must not recompose the control.
 */
internal class ControlPlacement {
	/** The control's layout coordinates, as of its latest placement; null before it is placed. */
	var coordinates: LayoutCoordinates? = null

	/**
	 * The control's top-left in absolute screen pixels.
	 *
	 * @return Offset? The origin, or null when the control is not placed.
	 */
	fun screenOrigin(): Offset? = coordinates?.takeIf { placed -> placed.isAttached }?.positionOnScreen()
}

/**
 * Runs the press-and-drag gestures of a control that sets its value from where the pointer is, the slider and the pad.
 * A press jumps the value to the pointer and a drag follows it, each axis read through its wrap (see
 * [ControlWrapTracker]).  Ctrl is read off every event, so it can join a drag already under way.  A secondary press
 * opens the row's menu and a middle press belongs to the view, so neither moves the value.  The press is claimed, so it
 * never reaches a surface under the control that answers a click, and every gesture is finished once.
 *
 * @param Function horizontalAxisOf The control's horizontal axis at a given size.
 * @param Function verticalAxisOf The control's vertical axis at a given size.
 * @param Function warp The cursor warp the gesture uses, read as the gesture starts.
 * @param ControlPlacement placement Where the control sits on screen.
 * @param Function onDrag Called with the point the control reads and whether Ctrl is held, on the press and each
 *   drag frame.
 * @param Function onFinished Called once when the gesture ends, on release or on the cancellation that ends a control
 *   torn down mid-drag.
 */
internal suspend fun PointerInputScope.trackControlDrags(
	horizontalAxisOf: (IntSize) -> ControlWrapAxis,
	verticalAxisOf: (IntSize) -> ControlWrapAxis,
	warp: () -> (Float, Float) -> Offset?,
	placement: ControlPlacement,
	onDrag: (Offset, Boolean) -> Unit,
	onFinished: () -> Unit,
) {
	val detent = CONTROL_WRAP_DETENT.toPx()
	awaitEachGesture {
		val down = awaitFirstDown()

		if (currentEvent.buttons.isSecondaryPressed || currentEvent.buttons.isTertiaryPressed) {
			return@awaitEachGesture
		}

		down.consume()
		val tracker = ControlWrapTracker(warp())
		var position = down.position
		var timeMillis = down.uptimeMillis
		var ctrlHeld = currentEvent.keyboardModifiers.isCtrlPressed

		try {
			while (true) {
				val control = tracker.controlPointOf(position, timeMillis, horizontalAxisOf(size), verticalAxisOf(size), detent, placement::screenOrigin)
				onDrag(control, ctrlHeld)
				val event = awaitPointerEvent()
				val change = event.changes.firstOrNull { candidate -> candidate.id == down.id } ?: break
				change.consume()

				if (!change.pressed) {
					controlWrapTrace?.invoke("end type=${event.type} buttons=${event.buttons} t=${change.uptimeMillis} position=${change.position}")

					break
				}

				position = change.position
				timeMillis = change.uptimeMillis
				ctrlHeld = event.keyboardModifiers.isCtrlPressed
			}
		} finally {
			onFinished()
		}
	}
}