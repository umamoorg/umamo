package org.umamo.ui.viewport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.CIRCLE_RADIUS_STEP_PX
import org.umamo.render.ViewportCamera

/** The outcome of releasing a box drag: nothing was in flight, a box applied, or a sub-threshold click. */
internal enum class BoxRelease {
	/** No box drag was in flight (the release belongs to something else). */
	None,

	/** The drag passed the click threshold and the box was applied. */
	Boxed,

	/** The drag stayed under the click threshold: the caller decides the click semantics. */
	Click,
}

/**
 * The marquee (box + circle) selection machinery shared by the gizmo overlays, generic over the
 * stroke selection type: MeshSelection for the Edit overlay (and the future UV editor), the object
 * Selection for the Object overlay.  Owns the gesture state shared across the overlays - the
 * in-flight circle stroke (with its erase flag) and the box rubber-band corners - plus
 * the full circle event branch, the box begin / drag / release rules, and the cancel semantics
 * (Blender-style: a circle stroke KEEPS what it painted and commits; a box rubber-band is abandoned
 * with no selection change).  The domain differences pass in as constructor callbacks.
 *
 * State is snapshot-backed so the overlay's draw pass observes the stroke and rubber-band live.
 *
 * @param Function seedStroke The committed selection a fresh stroke starts from.
 * @param Function stampStroke Applies one brush stamp (working, erasing, center, radiusPx, camera,
 *   size) and returns the updated working selection.
 * @param Function commitStroke Commits the finished stroke as one undo step.
 * @param Function applyBox Applies a finished box drag (start, end, additive, camera, size).
 * @param Function setCircleRadius Resizes the brush (the session clamps and remembers it).
 * @param Function clearTool Leaves the armed select tool (a right-click inside the circle tool).
 * @param Function onStrokeBegin Runs before the first stamp of a stroke (the Object overlay
 *   snapshots its centroid cache here); defaults to nothing.
 * @param Function previewStroke Publishes the live stroke after every stamp and null when the stroke
 *   ends (the Object overlay's GPU tint); defaults to nothing.
 */
internal class MarqueeSelectController<StrokeSelection>(
	private val seedStroke: () -> StrokeSelection,
	private val stampStroke: (StrokeSelection, Boolean, Offset, Float, ViewportCamera, IntSize) -> StrokeSelection,
	private val commitStroke: (StrokeSelection) -> Unit,
	private val applyBox: (Offset, Offset, Boolean, ViewportCamera, IntSize) -> Unit,
	private val setCircleRadius: (Float) -> Unit,
	private val clearTool: () -> Unit,
	private val onStrokeBegin: () -> Unit = {},
	private val previewStroke: (StrokeSelection?) -> Unit = {},
) {
	/**
	 * The live Circle-select stroke: a working selection seeded at press, painted on move, committed
	 * once on release (one undo step); null when no stroke is in flight.
	 */
	var circleStroke: StrokeSelection? by mutableStateOf(null)
		private set

	// Whether the in-flight stroke erases (a middle or Shift+primary drag) rather than adds.
	private var circleErasing: Boolean = false

	/** The box rubber-band's press corner in area-local pixels, or null when no drag is in flight. */
	var boxStart: Offset? by mutableStateOf(null)
		private set

	/** The box rubber-band's current corner, or null when no drag is in flight. */
	var boxCurrent: Offset? by mutableStateOf(null)
		private set

	/**
	 * Handles one pointer event of the live Circle-select tool: a primary drag paints (adds), a middle
	 * or Shift+primary drag erases, the stroke accumulates into the working selection committed once on
	 * release, the wheel resizes the brush, and a right-click leaves the tool keeping what was painted
	 * (Blender parity).  Every event is consumed so the tool owns the pointer.
	 *
	 * @param PointerEvent event The full pointer event (buttons and modifiers).
	 * @param PointerInputChange change The event's first change (position and consumption).
	 * @param Float radiusPx The live brush radius in screen pixels.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	fun handleCircleEvent(event: PointerEvent, change: PointerInputChange, radiusPx: Float, camera: ViewportCamera, size: IntSize) {
		when (event.type) {
			PointerEventType.Press ->
				if (event.buttons.isSecondaryPressed) {
					// Right-click leaves the tool; a held stroke keeps what it painted (Blender
					// parity), so commit it here before clearing rather than dropping it.
					endStroke()
					clearTool()
				} else if (event.buttons.isPrimaryPressed) {
					beginStamp(event.keyboardModifiers.isShiftPressed, change.position, radiusPx, camera, size)
				} else if (event.buttons.isTertiaryPressed) {
					beginStamp(erasing = true, change.position, radiusPx, camera, size)
				}

			PointerEventType.Move -> {
				val stroke = circleStroke
				if (stroke != null) {
					val painted = stampStroke(stroke, circleErasing, change.position, radiusPx, camera, size)
					circleStroke = painted
					previewStroke(painted)
				}
			}

			PointerEventType.Release -> endStroke()

			PointerEventType.Scroll -> {
				val steps = change.scrollDelta.y
				if (steps != 0f) {
					// Wheel up (negative y) grows the brush; the session clamps the radius.
					setCircleRadius(radiusPx - steps * CIRCLE_RADIUS_STEP_PX)
				}
			}

			else -> {}
		}
		change.consume()
	}

	/**
	 * Starts a box drag at [position] (both corners collapse onto the press point).
	 *
	 * @param Offset position The press position in area-local pixels.
	 */
	fun beginBox(position: Offset) {
		boxStart = position
		boxCurrent = position
	}

	/**
	 * Advances an in-flight box drag to [position].
	 *
	 * @param Offset position The current pointer position in area-local pixels.
	 * @return Boolean True when a drag was in flight (the caller consumes the event).
	 */
	fun dragBox(position: Offset): Boolean {
		if (boxStart == null) {
			return false
		}
		boxCurrent = position
		return true
	}

	/**
	 * Ends an in-flight box drag: past the click threshold the box applies through the callback; under
	 * it nothing applies and the caller decides the click semantics (clear, pick, or just disarm).
	 *
	 * @param Offset end The release position in area-local pixels.
	 * @param Boolean additive True to add to the current selection (Shift held).
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return BoxRelease What happened: [BoxRelease.None] with no drag in flight, else Boxed or Click.
	 */
	fun releaseBox(end: Offset, additive: Boolean, camera: ViewportCamera, size: IntSize): BoxRelease {
		val start = boxStart ?: return BoxRelease.None
		boxStart = null
		boxCurrent = null
		return if ((end - start).getDistance() > SELECT_DRAG_THRESHOLD_PX) {
			applyBox(start, end, additive, camera, size)
			BoxRelease.Boxed
		} else {
			BoxRelease.Click
		}
	}

	/**
	 * Resolves any in-flight gesture on an exit (Escape, right-click, a tool switch, or leaving the
	 * mode).  The two gestures exit DIFFERENTLY, Blender-style: a circle stroke KEEPS what it painted
	 * (its accumulated working selection commits as one undo step - the commit callback no-ops on an
	 * unchanged selection, so a stroke that painted nothing adds no undo step), while a box rubber-band
	 * is abandoned with no selection change.  Only one of the two is ever live at once, so resolving
	 * both arms is safe.
	 */
	fun cancel() {
		endStroke()
		boxStart = null
		boxCurrent = null
	}

	/**
	 * Begins (or continues) a stroke with one stamp: snapshots via [onStrokeBegin], records the erase
	 * flag, stamps from the in-flight stroke or a fresh seed, and publishes the preview.
	 *
	 * @param Boolean erasing True when this stroke removes elements.
	 * @param Offset center The brush centre in area-local pixels.
	 * @param Float radiusPx The brush radius in screen pixels.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	private fun beginStamp(erasing: Boolean, center: Offset, radiusPx: Float, camera: ViewportCamera, size: IntSize) {
		onStrokeBegin()
		circleErasing = erasing
		val painted = stampStroke(circleStroke ?: seedStroke(), erasing, center, radiusPx, camera, size)
		circleStroke = painted
		previewStroke(painted)
	}

	/**
	 * Ends the in-flight stroke, committing its paint (one undo step) and clearing the preview; a
	 * no-op with no stroke live beyond resetting the erase flag and preview.
	 */
	private fun endStroke() {
		val stroke = circleStroke
		if (stroke != null) {
			commitStroke(stroke)
			circleStroke = null
		}
		circleErasing = false
		previewStroke(null)
	}
}
