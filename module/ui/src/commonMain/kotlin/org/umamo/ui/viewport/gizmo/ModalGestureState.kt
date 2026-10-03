package org.umamo.ui.viewport.gizmo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import org.umamo.edit.TransformGestureParameters
import org.umamo.runtime.model.DrawableId

/**
 * The per-area modal-gesture bookkeeping every gizmo overlay carries: the last pointer position, the frozen
 * capture and its live preview, the gesture-start anchor, the area's screen origin (for cursor wrap), and
 * the wrap / pointer-controller pair.  The three overlays declared these as seven separate
 * `remember(areaId) { mutableStateOf(...) }` locals plus the same reset boilerplate; bundling them keeps the
 * begin / end lifecycle in one place and out of each overlay.
 *
 * State-backed fields stay observable (the draw pass and the HUD read [capture] / [preview] / [lastPointer]
 * live), so the whole object is created once per area with `remember(areaId) { ModalGestureState() }` - the
 * same pattern [MarqueeSelectController] uses.
 *
 * [TCapture] is the overlay's own capture type (each overlay holds extra per-mesh data the shared
 * [org.umamo.edit.ModalTransformCapture] does not), and [TPreview] what its preview holds per drawable: a
 * viewport gesture's rest shape, a UV gesture's coordinates.
 */
internal class ModalGestureState<TCapture, TPreview> {
	/** The most recent pointer position in area-local pixels, tracked for the geometry-dependent effects. */
	var lastPointer by mutableStateOf(Offset.Zero)

	/** The frozen gesture capture, or null when no modal gesture is in flight. */
	var capture by mutableStateOf<TCapture?>(null)

	/**
	 * The live preview the drive last published: the new rest shape (or UVs) per drawable, or null before the
	 * first publish.  A drive computes off the UI thread (ModalDriveWorker), so this trails [lastPointer] by
	 * up to one compute; a confirm settles it first.
	 */
	var preview by mutableStateOf<Map<DrawableId, TPreview>?>(null)

	/** The pointer position the gesture started at (the transform's origin), or null when idle. */
	var gestureStart by mutableStateOf<Offset?>(null)

	/**
	 * The parameters of the most recently published drive, or null before the first: what the confirm hands
	 * the operation settings strip as the rows the rigger can then edit.  Plain state (no reader observes
	 * it), written as a drive publishes.
	 */
	var lastParameters: TransformGestureParameters? = null

	/**
	 * Which gesture this is: bumped as a gesture begins and as it ends, so a drive result computed for an
	 * earlier gesture, or one that landed after this one ended, is never published (ModalDriveWorker).
	 * Plain state, UI thread only.
	 */
	var epoch: Int = 0
		private set

	/** The area's top-left in absolute screen pixels, for converting a wrap target into screen space; null before first layout. */
	var areaScreenOrigin by mutableStateOf<Offset?>(null)

	/** The gesture's cursor-wrap bookkeeping (the virtual pointer, stale-event guard, actual-landing fold). */
	val cursorWrap = CursorWrapState()

	/** The shared pointer-side driver of the modal gesture (stale discard, drive, wrap, buttons). */
	val modalController = ModalTransformController(cursorWrap)

	/**
	 * Latches a fresh capture: starts a new gesture epoch, records the capture, sets the gesture origin, clears
	 * any stale preview, and resets the cursor wrap.
	 *
	 * @param TCapture fresh The capture frozen at latch.
	 * @param Offset pointer The pointer position the gesture starts at.
	 */
	fun begin(fresh: TCapture, pointer: Offset) {
		epoch++
		capture = fresh
		gestureStart = pointer
		preview = null
		lastParameters = null
		cursorWrap.reset()
	}

	/**
	 * Tears the gesture down: ends its epoch (so no drive in flight publishes after this), clears the capture,
	 * preview, and origin, and resets the cursor wrap.  The boolean return IS the resync gate every overlay's
	 * teardown repeats - true only when a gesture was actually in flight, so a bystander teardown (mount,
	 * another area latching) does not stomp a live preview.
	 *
	 * @return Boolean True when a gesture was in flight (the caller resyncs the renderer).
	 */
	fun end(): Boolean {
		val wasActive = capture != null
		epoch++
		capture = null
		preview = null
		gestureStart = null
		lastParameters = null
		cursorWrap.reset()
		return wasActive
	}
}