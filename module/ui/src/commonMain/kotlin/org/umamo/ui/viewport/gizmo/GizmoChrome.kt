package org.umamo.ui.viewport.gizmo

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.TransformAxisConstraint
import org.umamo.render.ViewportCamera
import org.umamo.render.WorldAxisColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.SelectionOverlayStyle
import org.umamo.ui.theme.UmamoCursor
import org.umamo.ui.theme.drawCrosshairGuides
import org.umamo.ui.theme.drawCursor
import org.umamo.ui.theme.drawIcon
import org.umamo.ui.theme.drawSelectionCircle

// The shared gizmo chrome: the draw helpers and constants the gizmo overlays (the viewport's Edit and
// Object, the UV editor's Edit and Object) render identically.  Everything here is geometry-source
// agnostic: it takes screen-space points and the shared affordance style, never a session or a mesh.
// drawOwnedModalTransformHud alone reads a gesture's pointer state and projects its pivot through the
// area camera, so the four overlays' HUDs cannot drift apart.

// The modal HUD's pivot-to-pointer dash pattern (on, off).
private val MODAL_DASH_ON = 6.dp
private val MODAL_DASH_OFF = 4.dp

// The axis-lock guide colors, constructed from the world axes' own palette (WorldAxisColors.Classic)
// so the guide can never drift from the axis lines it mirrors.
private val AXIS_LOCK_X_COLOR =
	Color(WorldAxisColors.Classic.xRed, WorldAxisColors.Classic.xGreen, WorldAxisColors.Classic.xBlue)
private val AXIS_LOCK_Z_COLOR =
	Color(WorldAxisColors.Classic.zRed, WorldAxisColors.Classic.zGreen, WorldAxisColors.Classic.zBlue)

/**
 * Draws the modal axis-lock guide: a full-viewport line through the gesture anchor along the locked
 * axis, in the matching world-axis color (red X / blue Z), so the constrained direction reads at a
 * glance.  Draws nothing when unconstrained.
 *
 * @param TransformAxisConstraint? constraint The active axis lock, or null.
 * @param Offset anchorScreen The gesture anchor in screen pixels.
 * @param Size viewport The viewport size in pixels.
 */
internal fun DrawScope.drawAxisConstraintLine(constraint: TransformAxisConstraint?, anchorScreen: Offset, viewport: Size) {
	when (constraint) {
		TransformAxisConstraint.AxisX ->
			drawLine(color = AXIS_LOCK_X_COLOR, start = Offset(0f, anchorScreen.y), end = Offset(viewport.width, anchorScreen.y), strokeWidth = 1f)

		TransformAxisConstraint.AxisZ ->
			drawLine(color = AXIS_LOCK_Z_COLOR, start = Offset(anchorScreen.x, 0f), end = Offset(anchorScreen.x, viewport.height), strokeWidth = 1f)

		null -> {}
	}
}

/**
 * Draws the modal transform HUD for the gesture this area owns: the pivot projects through the area
 * camera, and the pointers come from the gesture state (the virtual one past a cursor wrap).  The caller
 * evaluates [owned] and [pivotWorld] inside its Canvas draw lambda, where it always has; nothing else is
 * read unless both hold, so the axis constraint and the ring radius stay reads of an owned gesture only
 * and a change to either while no gesture runs redraws nothing.
 *
 * @param Boolean owned Whether the caller's gate says this area draws the HUD.
 * @param Pair<Float, Float>? pivotWorld The capture's pivot, or null when no capture has landed.
 * @param ModalGestureState<*, *> gesture The area's modal gesture state.
 * @param State axisConstraint The session's axis constraint.
 * @param ViewportCamera camera The camera the pivot projects through.
 * @param IntSize size The area size in pixels.
 * @param Color lineColor The HUD's line color.
 * @param Function proportionalRadiusPx The proportional ring's radius in pixels, or null for none; read
 *   only when the HUD draws.
 */
internal fun DrawScope.drawOwnedModalTransformHud(
	owned: Boolean,
	pivotWorld: Pair<Float, Float>?,
	gesture: ModalGestureState<*, *>,
	axisConstraint: State<TransformAxisConstraint?>,
	camera: ViewportCamera,
	size: IntSize,
	lineColor: Color,
	proportionalRadiusPx: () -> Float? = { null },
) {
	if (!owned || pivotWorld == null) {
		return
	}
	drawModalTransformHud(
		axisConstraint = axisConstraint.value,
		pivotScreen = worldToScreen(pivotWorld.first, pivotWorld.second, camera, size),
		virtualPointer = gesture.cursorWrap.virtualPointer(gesture.lastPointer),
		realPointer = gesture.lastPointer,
		viewport = Size(size.width.toFloat(), size.height.toFloat()),
		lineColor = lineColor,
		pointerCursor = LocalUmamoCursors.nsewScroll,
		proportionalRadiusPx = proportionalRadiusPx(),
	)
}

/**
 * Draws the modal transform HUD (Blender's transform gizmo) shared by the gizmo overlays: the
 * axis-lock guide, an optional proportional-influence ring around the pivot, a dashed line from the
 * pivot to the virtual pointer (raw + every cursor wrap, so its direction tracks where the continuous
 * cursor has travelled - off-screen and all), and a custom double-arrow at the real pointer (the OS
 * cursor is hidden while modal).  The line naturally stays pointing the way the wrap went and only
 * swings round the pivot when the drag reverses, giving a sense of direction the bare wrap lacks.
 *
 * @param TransformAxisConstraint? axisConstraint The active axis lock, or null.
 * @param Offset pivotScreen The gesture anchor in screen pixels (the dashed line's origin).
 * @param Offset virtualPointer The wrap-continuous pointer in screen pixels (the dashed line's end).
 * @param Offset realPointer The physical pointer in screen pixels (where the drawn cursor lands).
 * @param Size viewport The viewport size in pixels.
 * @param Color lineColor The dashed-line (and default ring) color, from the theme marquee.
 * @param UmamoCursor pointerCursor The drawn stand-in for the hidden OS cursor (the double-arrow).
 * @param Float? proportionalRadiusPx The proportional influence ring's radius in screen pixels, or
 *   null to draw no ring (Object mode, Vertex Slide, or proportional editing off).
 */
internal fun DrawScope.drawModalTransformHud(
	axisConstraint: TransformAxisConstraint?,
	pivotScreen: Offset,
	virtualPointer: Offset,
	realPointer: Offset,
	viewport: Size,
	lineColor: Color,
	pointerCursor: UmamoCursor,
	proportionalRadiusPx: Float? = null,
) {
	drawAxisConstraintLine(axisConstraint, pivotScreen, viewport)
	if (proportionalRadiusPx != null) {
		drawCircle(color = lineColor, radius = proportionalRadiusPx, center = pivotScreen, style = Stroke(width = 1f))
	}
	drawLine(
		color = lineColor,
		start = pivotScreen,
		end = virtualPointer,
		strokeWidth = 1.dp.toPx(),
		pathEffect = PathEffect.dashPathEffect(floatArrayOf(MODAL_DASH_ON.toPx(), MODAL_DASH_OFF.toPx()), 0f),
	)
	drawCursor(pointerCursor, realPointer)
}

/**
 * Draws the armed select-tool affordances (Blender B / C) shared by the gizmo overlays: while
 * Box-select is armed and not yet dragging, the full-viewport crosshair guides plus a crosshair
 * cursor; while Circle-select is live, the brush circle plus a crosshair cursor.  The OS cursor is
 * hidden while a select tool is armed, so the drawn one is it.  Draws nothing with no tool armed.
 *
 * @param ActiveSelectTool? tool The armed select tool, or null.
 * @param Offset pointer The pointer position in area-local pixels.
 * @param Boolean boxDragInFlight True while a box rubber-band is being dragged (the crosshair guides
 *   hide during the drag - the rubber-band itself is the affordance).
 * @param Size viewport The viewport size in pixels.
 * @param SelectionOverlayStyle style The shared two-tone marching-ants style.
 * @param UmamoCursor crosshairCursor The drawn stand-in for the hidden OS cursor.
 */
internal fun DrawScope.drawSelectToolAffordances(
	tool: ActiveSelectTool?,
	pointer: Offset,
	boxDragInFlight: Boolean,
	viewport: Size,
	style: SelectionOverlayStyle,
	crosshairCursor: UmamoCursor,
) {
	when (tool) {
		is ActiveSelectTool.BoxArmed -> {
			if (!boxDragInFlight) {
				drawCrosshairGuides(pointer, viewport, style)
			}
			drawCursor(crosshairCursor, pointer)
		}

		is ActiveSelectTool.Circle -> {
			drawSelectionCircle(pointer, tool.radiusPx, style)
			drawCursor(crosshairCursor, pointer)
		}

		null -> {}
	}
}

/**
 * The cursor crosshair marker (the authored dashed-ring icon) at a projected screen point, drawn at a
 * screen-constant size - shared by the 2D viewport's cursor overlay and the UV editor's.
 *
 * @param Offset center The marker's center in area-local pixels.
 * @param Color tint The icon tint.
 */
internal fun DrawScope.drawCursorMarker(center: Offset, tint: Color) {
	val iconSizePx = 36.dp.toPx()
	// drawIcon fills the DrawScope's square, so shrink the bounds to an icon-sized box centered
	// on the projected point (negative insets are fine when the cursor sits near an edge).
	inset(
		left = center.x - iconSizePx / 2f,
		top = center.y - iconSizePx / 2f,
		right = size.width - center.x - iconSizePx / 2f,
		bottom = size.height - center.y - iconSizePx / 2f,
	) {
		drawIcon(LocalUmamoIcons.cursor2d, tint)
	}
}