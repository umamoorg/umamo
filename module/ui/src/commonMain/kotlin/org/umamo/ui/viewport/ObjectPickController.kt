package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.selectableOf
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DrawableId

/**
 * Resolves a primary click against the object selection: a hit with a membership modifier toggles
 * (Blender-style, so a second modified click deselects), a plain hit replaces, a modified click on
 * empty canvas keeps the selection, and a plain one clears it.
 *
 * @param Selection current The committed object selection.
 * @param SelectionTarget.Drawable? target The selectable drawable under the cursor, or null on empty canvas.
 * @param Boolean toggleMembership True when a membership modifier (Shift / Ctrl / Meta) is held.
 * @return Selection The selection the click produces.
 */
internal fun resolveObjectClickSelection(
	current: Selection,
	target: SelectionTarget.Drawable?,
	toggleMembership: Boolean,
): Selection =
	when {
		target != null && toggleMembership -> SelectionOps.toggle(current, target)
		target != null -> SelectionOps.replace(target)
		toggleMembership -> current
		else -> SelectionOps.clear()
	}

/** The outcome of an Alt click over a candidate stack: open the overlap picker, select directly, or nothing. */
internal sealed class AltPickResolution {
	/** Two or more candidates are stacked under the cursor: the overlap picker disambiguates. */
	class ShowOverlap(val candidates: List<PickCandidate>) : AltPickResolution()

	/** Exactly one candidate: select it directly, no picker needed. */
	class SelectSingle(val id: DrawableId) : AltPickResolution()

	/** Empty canvas: the selection stays as-is (Alt is the disambiguate gesture, not a clear). */
	object None : AltPickResolution()
}

/**
 * Resolves an Alt click over the (already selectable-filtered) candidate stack under the cursor.
 *
 * @param List<PickCandidate> candidates The selectable candidates under the cursor, front-to-back.
 * @return AltPickResolution What the click does.
 */
internal fun resolveAltOverlapPick(candidates: List<PickCandidate>): AltPickResolution =
	when {
		candidates.size > 1 -> AltPickResolution.ShowOverlap(candidates)
		candidates.size == 1 -> AltPickResolution.SelectSingle(candidates.first().id)
		else -> AltPickResolution.None
	}

/**
 * The idle object-pick pointer flow shared by the drawable-selecting surfaces: a primary press starts
 * a provisional rubber-band, a drag past the click threshold box-selects on release (Shift adds), a
 * sub-threshold release is the click pick (plain replaces, Shift / Ctrl toggles membership, an Alt
 * click resolves the overlap stack, an unmodified click on empty canvas clears), and Shift+RightClick
 * places the space's cursor.  Only primary-driven events are consumed, so middle-drag pan and wheel
 * zoom fall through to the navigation layer beneath.
 *
 * The domain seams pass in as constructor callbacks, the [MarqueeSelectController] pattern: the 2D
 * viewport binds the render service's raster pickers and the world 2D cursor; a UV object mode binds
 * its own display-space island hit tests and the UV cursor over the SAME session selection.  The
 * shared parts - the selection store, the selectable filter, and the navigation-suppressing
 * gesture-active flag - read the session directly, like [handleIdleMeshSelectionEvent] does for the
 * mesh-element domain.
 *
 * @param EditorSession session The session owning the model, the object selection, and the gesture-active flag.
 * @param MarqueeSelectController<Selection> marquee The box machinery this flow rubber-bands through.
 * @param Function pickTopmost The front-most selectable-unfiltered drawable at an area-local point, or null.
 * @param Function pickStack The full candidate stack at an area-local point, front-to-back, unfiltered.
 * @param Function onOverlapRequest Opens the overlap picker for an Alt click with 2+ candidates.
 * @param Function placeCursor Places the space's cursor at a Shift+RightClick, given the unprojected point.
 * @param Function onBoxBegin Runs at the press that starts the rubber-band (the viewport snapshots its
 *   centroid cache here, the [MarqueeSelectController] onStrokeBegin precedent); defaults to nothing.
 */
internal class ObjectPickController(
	private val session: EditorSession,
	private val marquee: MarqueeSelectController<Selection>,
	private val pickTopmost: (Offset) -> DrawableId?,
	private val pickStack: (Offset) -> List<PickCandidate>,
	private val onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
	private val placeCursor: (Float, Float) -> Unit,
	private val onBoxBegin: () -> Unit = {},
) {
	// Marks a box drag started from a plain primary press (the sub-threshold release of which is the
	// click pick).  A plain var, not snapshot state: only the pointer loop and the cancel paths read
	// it, never composition, so there is no observer to notify - the rubber-band the draw pass
	// observes lives in the marquee controller.
	private var boxing = false

	/**
	 * Handles one idle pointer event: the press / move / release flow described on the class.  The
	 * caller routes events here only while nothing is armed in its area (no modal operator, no armed
	 * select tool).
	 *
	 * @param PointerEvent event The full pointer event (buttons and modifiers).
	 * @param PointerInputChange change The event's first change (position and consumption).
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	fun handleIdleEvent(event: PointerEvent, change: PointerInputChange, camera: ViewportCamera, size: IntSize) {
		when (event.type) {
			PointerEventType.Press ->
				if (event.buttons.isSecondaryPressed && event.keyboardModifiers.isShiftPressed && !boxing) {
					// Shift+RightClick places the space's cursor at the pointer (Blender's gesture); the
					// cursor overlay draws it and the Cursor pivot mode / snap menu anchor on it.
					val (unprojectedX, unprojectedY) = screenToWorld(change.position.x, change.position.y, camera, size)
					placeCursor(unprojectedX, unprojectedY)
					change.consume()
				} else if (event.buttons.isSecondaryPressed) {
					if (boxing) {
						cancel()
						change.consume()
					}
				} else if (event.buttons.isPrimaryPressed && !event.buttons.isTertiaryPressed) {
					onBoxBegin()
					marquee.beginBox(change.position)
					boxing = true
					session.setViewportGestureActive(true)
					change.consume()
				}

			PointerEventType.Move ->
				if (boxing && marquee.dragBox(change.position)) {
					change.consume()
				}

			PointerEventType.Release -> {
				if (boxing) {
					val boxRelease = marquee.releaseBox(change.position, event.keyboardModifiers.isShiftPressed, camera, size)
					if (boxRelease != BoxRelease.None) {
						if (boxRelease == BoxRelease.Click) {
							val modifiers = event.keyboardModifiers
							applyClickPick(
								position = change.position,
								toggleMembership = modifiers.isCtrlPressed || modifiers.isMetaPressed || modifiers.isShiftPressed,
								alt = modifiers.isAltPressed,
							)
						}
						boxing = false
						session.setViewportGestureActive(false)
						change.consume()
					}
				}
			}

			else -> {}
		}
	}

	/**
	 * Abandons an in-flight un-armed box drag, dropping the rubber-band without touching the
	 * selection; a no-op when none is in flight, so callers invoke it unconditionally (the Escape /
	 * tool-switch / inert-area paths).
	 */
	fun cancel() {
		if (!boxing) {
			return
		}
		marquee.cancel()
		boxing = false
		session.setViewportGestureActive(false)
	}

	/**
	 * Applies the sub-threshold click: the Alt path resolves the candidate stack (2+ opens the overlap
	 * picker, exactly one selects directly, empty leaves the selection), the plain path picks the
	 * front-most drawable through the decision table.  Unselectable drawables are excluded from both,
	 * so a click passes through them.
	 *
	 * @param Offset position The click position in area-local pixels.
	 * @param Boolean toggleMembership True when a membership modifier (Shift / Ctrl / Meta) is held.
	 * @param Boolean alt True when Alt is held (the disambiguate gesture).
	 */
	private fun applyClickPick(position: Offset, toggleMembership: Boolean, alt: Boolean) {
		val model = session.model.value
		if (alt) {
			val candidates =
				pickStack(position).filter { candidate -> model.selectableOf(SelectionTarget.Drawable(candidate.id)) }
			when (val resolution = resolveAltOverlapPick(candidates)) {
				is AltPickResolution.ShowOverlap -> onOverlapRequest(position, resolution.candidates)
				is AltPickResolution.SelectSingle -> session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(resolution.id)))
				AltPickResolution.None -> {}
			}
			return
		}
		val hit = pickTopmost(position)?.takeIf { drawableId -> model.selectableOf(SelectionTarget.Drawable(drawableId)) }
		session.setSelection(
			resolveObjectClickSelection(
				current = session.selection.value,
				target = hit?.let { drawableId -> SelectionTarget.Drawable(drawableId) },
				toggleMembership = toggleMembership,
			),
		)
	}
}