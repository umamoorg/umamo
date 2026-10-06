package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.Selection
import org.umamo.edit.TransformAxisConstraint
import org.umamo.render.ViewportCamera
import org.umamo.ui.theme.SelectionOverlayStyle
import org.umamo.ui.theme.drawRubberBand
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.ModalGestureState
import org.umamo.ui.viewport.gizmo.drawOwnedModalTransformHud

/**
 * Draws the UV Object overlay's gesture chrome: the rubber band and the modal HUD (axis line, pivot dash,
 * drawn cursor), shared chrome with the other gizmo overlays.  The pointer, the marquee corners, the
 * capture, and the axis constraint are read HERE, in the draw phase, never while the overlay composes.
 * Only the initiating area draws the HUD - the capture exists solely in the overlay whose area the
 * operator latch names.
 *
 * @param MarqueeSelectController<Selection> marquee The area's box machinery.
 * @param ModalGestureState<PlacementGesture> gesture The area's placement gesture state.
 * @param Boolean owned Whether the latched UV operator is this area's.
 * @param State axisConstraint The session's axis constraint.
 * @param ViewportCamera camera The frame camera.
 * @param IntSize size The area size in pixels.
 * @param SelectionOverlayStyle style The marching-ants style for the band.
 * @param Color lineColor The HUD's line color.
 */
internal fun DrawScope.drawUvObjectGizmoChrome(
	marquee: MarqueeSelectController<Selection>,
	gesture: ModalGestureState<PlacementGesture>,
	owned: Boolean,
	axisConstraint: State<TransformAxisConstraint?>,
	camera: ViewportCamera,
	size: IntSize,
	style: SelectionOverlayStyle,
	lineColor: Color,
) {
	drawRubberBand(marquee.boxStart, marquee.boxCurrent, style)
	drawOwnedModalTransformHud(
		owned = owned,
		pivotWorld = gesture.capture?.transform?.anchor,
		gesture = gesture,
		axisConstraint = axisConstraint,
		camera = camera,
		size = size,
		lineColor = lineColor,
	)
}