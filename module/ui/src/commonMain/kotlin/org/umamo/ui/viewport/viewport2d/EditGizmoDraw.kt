package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveOperator
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.TransformAxisConstraint
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.render.ViewportCamera
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.SelectionOverlayStyle
import org.umamo.ui.theme.drawRubberBand
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.ModalGestureState
import org.umamo.ui.viewport.gizmo.drawOwnedModalTransformHud
import org.umamo.ui.viewport.gizmo.drawSelectToolAffordances

/**
 * Draws the Edit overlay's gesture chrome: the rubber band, the armed select tool's affordance, and the
 * modal HUD.  The pointer, the marquee corners, the capture, the axis constraint, and the proportional
 * state are read HERE, in the draw phase, never while the overlay composes - so a pointer move or a
 * mid-gesture change redraws this layer without recomposing anything.  The axis and the proportional
 * state arrive as State holders because they are read only for the gesture this area owns.
 *
 * @param MarqueeSelectController<MeshSelection> marquee The area's box / circle machinery.
 * @param ModalGestureState<EditGesture, MeshRestPositions> gesture The area's modal gesture state.
 * @param ActiveSelectTool? ownedSelectTool The select tool armed in this area, or null.
 * @param ActiveOperator? hudOperator The latched mesh operator, whichever area owns it.
 * @param State axisConstraint The session's axis constraint.
 * @param State proportionalEdit The session's proportional editing state.
 * @param EditorSession session The session (whether the latched operator takes weights).
 * @param ViewportCamera camera The camera the displayed frame was rendered at.
 * @param IntSize size The area size in pixels.
 * @param SelectionOverlayStyle style The marching-ants style for the band and the affordances.
 * @param Color lineColor The HUD's line color.
 */
internal fun DrawScope.drawEditGizmoChrome(
	marquee: MarqueeSelectController<MeshSelection>,
	gesture: ModalGestureState<EditGesture, MeshRestPositions>,
	ownedSelectTool: ActiveSelectTool?,
	hudOperator: ActiveOperator?,
	axisConstraint: State<TransformAxisConstraint?>,
	proportionalEdit: State<ProportionalEditState?>,
	session: EditorSession,
	camera: ViewportCamera,
	size: IntSize,
	style: SelectionOverlayStyle,
	lineColor: Color,
) {
	val viewport = Size(size.width.toFloat(), size.height.toFloat())
	// The rubber-band box (both plain and armed drags) shares the one selection-box style.
	drawRubberBand(marquee.boxStart, marquee.boxCurrent, style)

	// Armed select-tool affordances (Blender B / C), shared chrome with the Object gizmo.  Only the arming
	// area draws them - the latch is session-global, every split viewport draws.
	drawSelectToolAffordances(
		tool = ownedSelectTool,
		pointer = gesture.lastPointer,
		boxDragInFlight = marquee.boxStart != null,
		viewport = viewport,
		style = style,
		crosshairCursor = LocalUmamoCursors.crosshair,
	)

	// Modal transform HUD, shared chrome with the Object gizmo (see drawModalTransformHud).  Only the
	// initiating area draws the modal chrome: the capture exists solely in the overlay whose area the
	// operator latch names, so its presence IS the ownership gate.
	drawOwnedModalTransformHud(
		owned = hudOperator != null,
		pivotWorld = gesture.capture?.transform?.anchor,
		gesture = gesture,
		axisConstraint = axisConstraint,
		camera = camera,
		size = size,
		lineColor = lineColor,
		proportionalRadiusPx = {
			// The proportional influence ring hugs the world-unit radius the weights use (scaled by the frame
			// camera).  Vertex Slide and a suppressed latch never take weights, so they show no ring.
			val proportionalState = proportionalEdit.value
			if (hudOperator != null && proportionalState != null && session.meshOperatorTakesProportional(hudOperator.kind)) {
				proportionalState.radiusWorld * camera.zoom
			} else {
				null
			}
		},
	)
}