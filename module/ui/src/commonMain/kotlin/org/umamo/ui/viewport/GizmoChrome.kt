package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.TransformAxisConstraint
import org.umamo.render.WorldAxisColors
import org.umamo.ui.theme.UmamoCursor
import org.umamo.ui.theme.drawCursor
import kotlin.math.abs
import kotlin.math.min

// The shared gizmo chrome: the draw helpers and constants both gizmo overlays (Edit and Object - and
// later the UV editor's) render identically.  Everything here is geometry-source agnostic: it takes
// screen-space points and the shared affordance style, never a session or a mesh.

/** A primary drag shorter than this (px) is treated as a click, not a box select. */
internal const val SELECT_DRAG_THRESHOLD_PX = 3f

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
 * Draws the in-flight box-select rubber-band between two drag corners, or nothing when no drag is in
 * flight (either corner null).  Normalizes the corners so the drag direction never matters.
 *
 * @param Offset? start The drag's press corner in area-local pixels, or null.
 * @param Offset? end The drag's current corner in area-local pixels, or null.
 * @param SelectionOverlayStyle style The shared two-tone marching-ants style.
 */
internal fun DrawScope.drawRubberBand(start: Offset?, end: Offset?, style: SelectionOverlayStyle) {
	if (start == null || end == null) {
		return
	}
	val topLeft = Offset(min(start.x, end.x), min(start.y, end.y))
	val boxSize = Size(abs(end.x - start.x), abs(end.y - start.y))
	drawSelectionBox(topLeft, boxSize, style)
}
