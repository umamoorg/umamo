package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveOperator
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.MeshSelection
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.TransformAxisConstraint
import org.umamo.render.ViewportCamera
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.SelectionOverlayStyle
import org.umamo.ui.theme.drawRubberBand
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.ModalGestureState
import org.umamo.ui.viewport.gizmo.drawOwnedModalTransformHud
import org.umamo.ui.viewport.gizmo.drawSelectToolAffordances

/**
 * Draws the UV Edit overlay's gesture chrome: the rubber band, the armed select tool's affordance, and the
 * modal HUD with its proportional ring.  The pointer, the marquee corners, the capture, the axis constraint,
 * the proportional state, and the radius are read HERE, in the draw phase, never while the overlay composes
 * - so a pointer move or a mid-gesture change redraws this layer without recomposing anything.  The axis and
 * the proportional state arrive as State holders because they are read only for the gesture this area owns,
 * and the ring's radius is the gesture's own (the radius of the surface it began on, see UvEditGesture).
 *
 * @param MarqueeSelectController<MeshSelection> marquee The area's box / circle machinery.
 * @param ModalGestureState<UvEditGesture, FloatArray> gesture The area's modal gesture state.
 * @param ActiveSelectTool? ownedSelectTool The select tool armed in this area, or null.
 * @param ActiveOperator? hudOperator The latched UV operator, whichever area owns it.
 * @param State axisConstraint The session's axis constraint.
 * @param State proportionalEdit The session's proportional editing state.
 * @param ViewportCamera camera The frame camera.
 * @param IntSize size The area size in pixels.
 * @param SelectionOverlayStyle style The marching-ants style for the band and the affordances.
 * @param Color lineColor The HUD's line color.
 */
internal fun DrawScope.drawUvEditGizmoChrome(
	marquee: MarqueeSelectController<MeshSelection>,
	gesture: ModalGestureState<UvEditGesture, FloatArray>,
	ownedSelectTool: ActiveSelectTool?,
	hudOperator: ActiveOperator?,
	axisConstraint: State<TransformAxisConstraint?>,
	proportionalEdit: State<ProportionalEditState?>,
	camera: ViewportCamera,
	size: IntSize,
	style: SelectionOverlayStyle,
	lineColor: Color,
) {
	drawRubberBand(marquee.boxStart, marquee.boxCurrent, style)

	// Armed select-tool affordances (Blender B / C), shared chrome with the viewport overlays.  Only the
	// arming area draws them - the latch is session-global, every UV area composes this.
	drawSelectToolAffordances(
		tool = ownedSelectTool,
		pointer = gesture.lastPointer,
		boxDragInFlight = marquee.boxStart != null,
		viewport = Size(size.width.toFloat(), size.height.toFloat()),
		style = style,
		crosshairCursor = LocalUmamoCursors.crosshair,
	)

	// Modal transform HUD (axis line, pivot dash, drawn cursor, proportional ring), shared chrome with the
	// viewport overlays.  Only the initiating area draws it - the capture exists solely in the overlay whose
	// area the operator latch names.
	drawOwnedModalTransformHud(
		owned = hudOperator != null,
		pivotWorld = gesture.capture?.transform?.anchor,
		gesture = gesture,
		axisConstraint = axisConstraint,
		camera = camera,
		size = size,
		lineColor = lineColor,
		proportionalRadiusPx = {
			if (proportionalEdit.value != null) {
				(gesture.capture?.proportionalRadius?.value ?: 0f).takeIf { radius -> radius > 0f }?.times(camera.zoom)
			} else {
				null
			}
		},
	)
}