package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera

/**
 * The commit-side seam of a modal G / S / R gesture: what differs between the Edit overlay (per-mesh
 * covered vertices folded through the deformer inverse), the Object overlay (whole drawables), and a
 * future UV editor (raw texture coordinates).  The pointer-side mechanics - stale-event discard,
 * virtual-pointer drive, cursor wrap, confirm / cancel buttons - are identical and live in
 * [ModalTransformController]; implementations supply only where the preview goes and how it commits.
 */
internal interface ModalTransformTarget {
	/**
	 * Re-derives the live preview for the given wrap-continuous pointer and pushes it to the renderer.
	 *
	 * @param Offset virtualPointer The wrap-continuous pointer in screen pixels.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return Boolean True when a preview was driven; false when there is nothing to drive yet (the
	 *   gesture capture has not landed), which also skips the cursor wrap for the event.
	 */
	fun drivePreview(virtualPointer: Offset, camera: ViewportCamera, size: IntSize): Boolean

	/** Commits the in-flight gesture (a primary click; Enter arrives via the confirm-request bus). */
	fun confirm()

	/** Cancels the in-flight gesture (a right-click; Escape arrives via the session's operator clear). */
	fun cancel()

	/**
	 * Handles a modal wheel scroll.  The Edit overlay resizes the proportional influence radius here;
	 * targets without a scroll behavior take the default no-op.
	 *
	 * @param Float steps The scroll delta in wheel steps (negative is wheel-up).
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	fun onScroll(steps: Float, camera: ViewportCamera, size: IntSize) {}
}

/**
 * Drives the pointer side of a modal transform gesture, shared by the gizmo overlays: one event
 * dispatcher over a [ModalTransformTarget], wired to the gesture's [CursorWrapState].  Owning this
 * here (instead of twin when-branches in every overlay) is what lets a new editor space gain the
 * whole modal interaction - wrap, stale-event discard, button semantics - by implementing the target.
 *
 * @param CursorWrapState cursorWrap The gesture's wrap bookkeeping (also reset by the overlay's
 *   capture latch / teardown effect).
 */
internal class ModalTransformController(
	private val cursorWrap: CursorWrapState,
) {
	/**
	 * Feeds one pointer event of an active modal gesture to [target]: Move / Exit discard stale
	 * pre-warp events, drive the preview from the virtual pointer, then wrap the cursor at the
	 * viewport edge (Exit is handled with Move so a fast flick that clears the trigger band before its
	 * last in-bounds Move still wraps); a secondary press cancels, a primary press confirms; a scroll
	 * goes to the target.  Every event is consumed - a modal gesture owns the pointer.
	 *
	 * @param PointerEvent event The full pointer event (buttons and type).
	 * @param PointerInputChange change The event's first change (position and consumption).
	 * @param ModalTransformTarget target The gesture's commit-side seam.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @param Offset? areaScreenOrigin The area's top-left in absolute screen pixels, or null before
	 *   first layout.
	 * @return Offset The pointer position the overlay should record: the frozen stale landing, the
	 *   wrap landing, or the event position.
	 */
	fun handleEvent(
		event: PointerEvent,
		change: PointerInputChange,
		target: ModalTransformTarget,
		camera: ViewportCamera,
		size: IntSize,
		areaScreenOrigin: Offset?,
	): Offset {
		var pointer = change.position
		when (event.type) {
			PointerEventType.Move, PointerEventType.Exit -> {
				// Discard stale pre-warp events, keeping the HUD anchored at the landing instead of
				// flicking back to the old edge (see CursorWrapState.resolveArrival).
				val staleLanding = cursorWrap.resolveArrival(change.position, size)
				if (staleLanding != null) {
					change.consume()
					return staleLanding
				}
				// Drive from the virtual pointer so the transform stays continuous across wraps (what
				// makes Scale / Rotate unbounded), then wrap at the viewport edge, folding the ACTUAL
				// landing into the wrap offset (see CursorWrapState.maybeWrap).
				if (target.drivePreview(cursorWrap.virtualPointer(change.position), camera, size)) {
					val landed = cursorWrap.maybeWrap(change.position, size, areaScreenOrigin)
					if (landed != null) {
						pointer = landed
					}
				}
			}

			PointerEventType.Press ->
				if (event.buttons.isSecondaryPressed) {
					target.cancel()
				} else if (event.buttons.isPrimaryPressed) {
					target.confirm()
				}

			PointerEventType.Scroll -> target.onScroll(change.scrollDelta.y, camera, size)

			else -> {}
		}
		change.consume()
		return pointer
	}
}

/**
 * Collects the session's Enter-confirm requests for one overlay instance and confirms its gesture.
 * Only the INITIATING area commits - every overlay instance collects this shared flow, and an
 * ungated confirm would commit once per split viewport.  The gate is the caller's: it checks that
 * its own operator latch names this area, which is deterministic and also keeps an Edit-mode Enter
 * from confirming through an idle Object overlay.
 *
 * @param EditorSession session The session whose confirm requests to collect.
 * @param Function isGestureActiveHere True while an operator latched to this overlay's area runs.
 * @param Function confirm Commits the in-flight gesture.
 */
internal suspend fun collectModalConfirmRequests(
	session: EditorSession,
	isGestureActiveHere: () -> Boolean,
	confirm: () -> Unit,
) {
	session.meshConfirmRequests.collect {
		if (isGestureActiveHere()) {
			confirm()
		}
	}
}
