package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveOperator
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.MeshRestPositions
import org.umamo.edit.Selection
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
 * Draws the Object overlay's gesture chrome: the rubber band, the armed select tool's affordance, and the
 * modal HUD.  The pointer, the marquee corners, the capture, and the axis constraint are read HERE, in the
 * draw phase, never while the overlay composes - so a pointer move or a mid-gesture axis change redraws
 * this layer without recomposing anything.  The axis arrives as a State holder because it is read only for
 * the gesture this area owns.  What Object mode draws over the puppet beyond the chrome joins this file.
 *
 * @param MarqueeSelectController<Selection> marquee The area's box / circle machinery.
 * @param ModalGestureState<ObjectGesture, MeshRestPositions> gesture The area's modal gesture state.
 * @param ActiveSelectTool? ownedSelectTool The select tool armed in this area, or null.
 * @param ActiveOperator? hudOperator The latched object operator, whichever area owns it.
 * @param State axisConstraint The session's axis constraint.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @param SelectionOverlayStyle style The marching-ants style for the band and the affordances.
 * @param Color lineColor The HUD's line color.
 */
internal fun DrawScope.drawObjectGizmoChrome(
	marquee: MarqueeSelectController<Selection>,
	gesture: ModalGestureState<ObjectGesture, MeshRestPositions>,
	ownedSelectTool: ActiveSelectTool?,
	hudOperator: ActiveOperator?,
	axisConstraint: State<TransformAxisConstraint?>,
	camera: ViewportCamera,
	size: IntSize,
	style: SelectionOverlayStyle,
	lineColor: Color,
) {
	val fullSize = Size(size.width.toFloat(), size.height.toFloat())
	// The box rubber-band.
	drawRubberBand(marquee.boxStart, marquee.boxCurrent, style)

	// Armed select-tool affordances (Blender B / C), shared chrome with the Edit gizmo.  Only the arming area
	// draws them - the latch is session-global, every split viewport draws.
	drawSelectToolAffordances(
		tool = ownedSelectTool,
		pointer = gesture.lastPointer,
		boxDragInFlight = marquee.boxStart != null,
		viewport = fullSize,
		style = style,
		crosshairCursor = LocalUmamoCursors.crosshair,
	)

	// Modal transform HUD, shared chrome with the Edit gizmo (see drawModalTransformHud).  Only the initiating
	// area draws it: the capture exists solely in the overlay whose area the operator latch names, so its
	// presence IS the ownership gate.
	drawOwnedModalTransformHud(
		owned = hudOperator != null,
		pivotWorld = gesture.capture?.transform?.anchor,
		gesture = gesture,
		axisConstraint = axisConstraint,
		camera = camera,
		size = size,
		lineColor = lineColor,
	)
}