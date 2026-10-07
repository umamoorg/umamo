package org.umamo.ui.kit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed

/** A primary drag shorter than this (px) is a click, not a box. */
internal const val SELECT_DRAG_THRESHOLD_PX = 3f

/**
 * What a box gesture means on one surface - the half of a box select that differs between the viewport,
 * the UV editor, and a track sheet.  [BoxGestureFlow] runs the rules and calls these; the surface owns
 * the rubber-band it draws and whatever a landed box selects.
 *
 * Frame is whatever the surface needs to resolve a point when a press is tested or the box lands: the
 * viewport's camera and size, or Unit for a sheet whose lanes report their own bounds.
 */
internal interface BoxGestureSurface<Frame> {
	/**
	 * Starts the rubber-band at the press.
	 *
	 * @param Offset position The press position, local to the surface.
	 */
	fun beginBox(position: Offset)

	/**
	 * Advances the rubber-band to the pointer.
	 *
	 * @param Offset position The current pointer position.
	 */
	fun dragBox(position: Offset)

	/**
	 * Lands a box that passed the click threshold: the surface selects what it encloses and drops the band.
	 *
	 * @param Offset start The press corner.
	 * @param Offset end The release corner.
	 * @param Boolean additive True to add to the current selection (Shift held at the release).
	 * @param Frame frame The projection at the release.
	 */
	fun landBox(start: Offset, end: Offset, additive: Boolean, frame: Frame)

	/** Drops an in-flight band with nothing landing. */
	fun abandonBox()

	/**
	 * An un-armed release under the click threshold: the surface decides what a click means.
	 *
	 * @param PointerEvent event The release event (its modifiers).
	 * @param PointerInputChange change The release's change (its position).
	 */
	fun click(event: PointerEvent, change: PointerInputChange)

	/** Leaves the armed tool: after an armed release, or at an armed right-click. */
	fun disarm()

	/**
	 * Selects what is under an un-armed press, if the surface does that, and says whether it did - then no
	 * box starts.  By default nothing does.
	 *
	 * @param PointerEvent event The press event (its modifiers).
	 * @param PointerInputChange change The press's change (its position).
	 * @param Frame frame The projection at the press.
	 * @return Boolean True when the press selected something.
	 */
	fun pressSelects(event: PointerEvent, change: PointerInputChange, frame: Frame): Boolean = false

	/**
	 * An un-armed Shift+RightClick with no box in flight, which the viewport answers by placing its cursor.
	 * By default nothing happens.
	 *
	 * @param PointerInputChange change The press's change (its position).
	 * @param Frame frame The projection at the press.
	 */
	fun idleShiftSecondary(change: PointerInputChange, frame: Frame) {}

	/**
	 * Raised as a box begins and lowered as it lands or is abandoned, for a surface that holds a
	 * gesture-in-flight flag.  By default nothing happens.
	 *
	 * @param Boolean active True while a box is in flight.
	 */
	fun setGestureActive(active: Boolean) {}
}

/**
 * The one box-select gesture every selecting surface runs, armed (Blender's B) and not.  Armed differs
 * only in how the box starts (every primary press starts one) and in disarming afterward; everything
 * else is one set of rules:
 *   - A primary press starts the box - un-armed, unless the surface's press selects something first
 *     ([BoxGestureSurface.pressSelects]).
 *   - A drag rubber-bands and the release lands the box, with Shift read at the release adding to the
 *     selection.  Under [SELECT_DRAG_THRESHOLD_PX] the release is a click: un-armed, the surface decides
 *     what it means ([BoxGestureSurface.click]); armed, it only disarms.  An armed box disarms after any
 *     release.
 *   - Mid-drag, any right-click - Shift included - abandons the box, and disarms an armed one.
 *   - With no box in flight, an armed right-click (Shift included) disarms; an un-armed Shift+RightClick
 *     reaches the surface ([BoxGestureSurface.idleShiftSecondary]).
 *   - A cancelled release abandons the box, nothing landing: Compose answers a density or view
 *     configuration change under a pressed pointer with a synthetic release that arrives already
 *     consumed, where a real release reaching a surface never is.
 *   - A box whose armed state changes under it (the tool armed or cleared mid-drag) is abandoned; a
 *     surface abandons it too, through [cancel], when something else takes the pointer over mid-drag.
 *
 * Only primary-driven events and right-clicks are consumed, so a middle-drag pan and a wheel zoom fall
 * through to whatever sits beneath.
 *
 * @param BoxGestureSurface<Frame> surface What the gesture means here.
 */
internal class BoxGestureFlow<Frame>(private val surface: BoxGestureSurface<Frame>) {
	// Whether a box this flow started is in flight, where it started, and whether it started armed.  Plain
	// vars, not snapshot state: only the pointer loop and the cancel paths read them, never composition -
	// the rubber-band the draw pass observes lives in the surface.
	private var boxing = false
	private var startedArmed = false
	private var pressPosition = Offset.Zero

	/**
	 * Handles one pointer event while nothing else owns the surface: the rules on the class.
	 *
	 * @param PointerEvent event The full pointer event (buttons and modifiers).
	 * @param PointerInputChange change The event's first change (position and consumption).
	 * @param Boolean armed True while Box select is armed on this surface.
	 * @param Frame frame The surface's projection for this event.
	 */
	fun handleEvent(event: PointerEvent, change: PointerInputChange, armed: Boolean, frame: Frame) {
		if (boxing && armed != startedArmed) {
			cancel()
		}
		when (event.type) {
			PointerEventType.Press ->
				if (event.buttons.isSecondaryPressed) {
					if (boxing) {
						cancel()
						if (armed) {
							surface.disarm()
						}
						change.consume()
					} else if (armed) {
						surface.disarm()
						change.consume()
					} else if (event.keyboardModifiers.isShiftPressed) {
						surface.idleShiftSecondary(change, frame)
						change.consume()
					}
				} else if (event.buttons.isPrimaryPressed && !event.buttons.isTertiaryPressed && !boxing) {
					if (!armed && surface.pressSelects(event, change, frame)) {
						change.consume()
					} else {
						pressPosition = change.position
						surface.beginBox(change.position)
						boxing = true
						startedArmed = armed
						surface.setGestureActive(true)
						change.consume()
					}
				}

			PointerEventType.Move ->
				if (boxing) {
					surface.dragBox(change.position)
					change.consume()
				}

			PointerEventType.Release ->
				if (boxing) {
					if (change.isConsumed) {
						cancel()
						return
					}
					// The box lands BEFORE the flag drops and a click runs after it, so a landing that
					// commits a selection still sees the gesture as in flight.
					val landed = (change.position - pressPosition).getDistance() > SELECT_DRAG_THRESHOLD_PX
					if (landed) {
						surface.landBox(pressPosition, change.position, event.keyboardModifiers.isShiftPressed, frame)
					}
					boxing = false
					surface.setGestureActive(false)
					if (!landed && !armed) {
						surface.click(event, change)
					}
					if (armed) {
						// Armed Box-select is one-shot: disarm after the drag (or a bare click).
						surface.disarm()
					}
					change.consume()
				}

			else -> {}
		}
	}

	/**
	 * Abandons an in-flight box, dropping the rubber-band without touching the selection and lowering the
	 * gesture flag; a no-op when none is in flight, so callers invoke it unconditionally.
	 */
	fun cancel() {
		if (!boxing) {
			return
		}
		surface.abandonBox()
		boxing = false
		surface.setGestureActive(false)
	}
}