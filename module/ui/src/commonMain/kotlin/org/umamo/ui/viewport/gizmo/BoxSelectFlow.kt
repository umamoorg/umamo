package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.ui.kit.BoxGestureFlow
import org.umamo.ui.kit.BoxGestureSurface

/**
 * The area camera and size an event's points resolve through, handed back to the surface at a press and
 * at the landing: both change under a running pointer loop, so neither is captured at construction.
 *
 * @property ViewportCamera camera The area camera.
 * @property IntSize size The area size in pixels.
 */
internal data class AreaProjection(val camera: ViewportCamera, val size: IntSize)

/**
 * The viewport surfaces' box select - the 2D viewport and the UV editor, Edit and Object mode, armed
 * (Blender's B) and not - as kit's BoxGestureFlow run over a [MarqueeSelectController].  The RULES (what
 * a press, a drag, a release, a right-click, and a cancelled release do, and the click threshold) are the
 * flow's; this class is what they mean here:
 *   - the rubber-band is the marquee's, and a landed box applies through its applyBox;
 *   - an armed tool is the session's select-tool latch, so disarming clears it;
 *   - while a box is in flight the session's viewportGestureActive flag is up: it keeps navigation from
 *     also panning, routes Escape to the gesture cancel, and holds undo until the release;
 *   - an un-armed Shift+RightClick places the space's cursor at the unprojected point (Blender's
 *     gesture; the Cursor pivot mode and the snap / mirror commands anchor on it).
 * Only primary-driven events and right-clicks are consumed, so middle-drag pan and wheel zoom fall
 * through to the navigation layer beneath.
 *
 * @param EditorSession session The session owning the select-tool latch and the gesture flag.
 * @param MarqueeSelectController<*> marquee The rubber-band this flow drives; its applyBox lands the box.
 * @param Function placeCursor Places the space's cursor at a Shift+RightClick, given the unprojected point.
 * @param Function onClick The un-armed sub-threshold click, given the release event and its change.
 * @param Function pressSelects Selects what is under an un-armed press, if the surface does that, and
 *   says whether it did (then no box starts); by default nothing does.
 * @param Function onBoxBegin Runs at the press that starts a box (the viewport's Object mode refreshes its
 *   selection anchors here); defaults to nothing.
 */
internal class BoxSelectFlow(
	private val session: EditorSession,
	private val marquee: MarqueeSelectController<*>,
	private val placeCursor: (Float, Float) -> Unit,
	private val onClick: (PointerEvent, PointerInputChange) -> Unit,
	private val pressSelects: (PointerEvent, PointerInputChange, ViewportCamera, IntSize) -> Boolean = { _, _, _, _ -> false },
	private val onBoxBegin: () -> Unit = {},
) {
	private val surface =
		object : BoxGestureSurface<AreaProjection> {
			override fun beginBox(position: Offset) {
				onBoxBegin()
				marquee.beginBox(position)
			}

			override fun dragBox(position: Offset) {
				marquee.dragBox(position)
			}

			override fun landBox(start: Offset, end: Offset, additive: Boolean, frame: AreaProjection) {
				marquee.landBox(start, end, additive, frame.camera, frame.size)
			}

			override fun abandonBox() {
				marquee.cancel()
			}

			override fun click(event: PointerEvent, change: PointerInputChange) {
				onClick(event, change)
			}

			override fun disarm() {
				session.clearSelectTool()
			}

			override fun pressSelects(event: PointerEvent, change: PointerInputChange, frame: AreaProjection): Boolean =
				this@BoxSelectFlow.pressSelects(event, change, frame.camera, frame.size)

			override fun idleShiftSecondary(change: PointerInputChange, frame: AreaProjection) {
				val (unprojectedX, unprojectedY) = screenToWorld(change.position.x, change.position.y, frame.camera, frame.size)
				placeCursor(unprojectedX, unprojectedY)
			}

			override fun setGestureActive(active: Boolean) {
				session.setViewportGestureActive(active)
			}
		}

	private val flow = BoxGestureFlow(surface)

	/**
	 * Handles one pointer event while nothing else owns the area (no transform, no circle tool): the
	 * rules on the flow, over this viewport's meaning of them.
	 *
	 * @param PointerEvent event The full pointer event (buttons and modifiers).
	 * @param PointerInputChange change The event's first change (position and consumption).
	 * @param Boolean armed True while Box select is armed in this area.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	fun handleEvent(event: PointerEvent, change: PointerInputChange, armed: Boolean, camera: ViewportCamera, size: IntSize) {
		flow.handleEvent(event, change, armed, AreaProjection(camera, size))
	}

	/**
	 * Abandons an in-flight box, dropping the rubber-band without touching the selection and lowering the
	 * gesture flag; a no-op when none is in flight, so callers invoke it unconditionally.
	 */
	fun cancel() {
		flow.cancel()
	}
}