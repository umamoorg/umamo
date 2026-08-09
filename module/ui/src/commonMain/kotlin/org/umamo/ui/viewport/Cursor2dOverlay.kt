package org.umamo.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.drawIcon

/**
 * The world-space 2D cursor marker: the authored dashed-ring crosshair (LocalUmamoIcons.cursor2d,
 * axis-colored arm tips) at its world position, in both modes (it anchors pivots and snaps regardless
 * of mode), projected through the frame camera and drawn at a screen-constant size.
 *
 * The cursor is a control, not HUD chrome - the mode gizmos place it (Shift+RightClick) and the
 * transform pivot and snap commands consume it - so it draws in this overlay of its own, mounted
 * above the gizmo chrome and below the informational HUD.  The layer itself is draw-only (no pointer
 * input), and it projects through the DISPLAYED frame's camera (like every world-anchored overlay
 * drawing) so it never swims against the raster.
 *
 * @param EditorSession session The session whose 2D cursor this overlay draws.
 * @param ViewportCamera? camera The displayed frame's camera (world<->screen); null skips drawing.
 * @param Int widthPx The viewport width in px.
 * @param Int heightPx The viewport height in px.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun Cursor2dOverlay(
	session: EditorSession,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val cursor by session.cursor2d.collectAsState()
	val cursorColors = LocalUmamoColors.current
	val cursorToDraw = cursor
	if (cursorToDraw == null || camera == null) {
		return
	}
	Canvas(modifier = modifier.fillMaxSize()) {
		drawCursorMarker(
			center = worldToScreen(cursorToDraw.worldX, cursorToDraw.worldY, camera, IntSize(widthPx, heightPx)),
			tint = cursorColors.viewportBadgeText,
		)
	}
}

/**
 * The UV cursor marker (the texture-space 2D cursor), the UV editor's twin of [Cursor2dOverlay]:
 * the same crosshair at the cursor's display-space position, present in both modes and drawn above
 * the gizmo chrome for viewport parity.  Draw-only; placement stays a gizmo gesture
 * (Shift+RightClick, handled by the mode's own overlay in both modes).
 *
 * @param EditorSession session The session whose UV cursor this overlay draws.
 * @param Int pageWidth The shown atlas page's width in texels (the display mapping's scale).
 * @param Int pageHeight The shown atlas page's height in texels.
 * @param ViewportCamera? camera The displayed frame's camera; null skips drawing.
 * @param Int widthPx The area width in px.
 * @param Int heightPx The area height in px.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun UvCursorOverlay(
	session: EditorSession,
	pageWidth: Int,
	pageHeight: Int,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val cursor by session.uvCursor.collectAsState()
	val cursorColors = LocalUmamoColors.current
	val cursorToDraw = cursor
	if (cursorToDraw == null || camera == null) {
		return
	}
	Canvas(modifier = modifier.fillMaxSize()) {
		drawCursorMarker(
			center =
				worldToScreen(
					uvToDisplayX(cursorToDraw.u, pageWidth),
					uvToDisplayY(cursorToDraw.v, pageHeight),
					camera,
					IntSize(widthPx, heightPx),
				),
			tint = cursorColors.viewportBadgeText,
		)
	}
}

/**
 * The cursor crosshair marker (the authored dashed-ring icon) at a projected screen point, drawn at a
 * screen-constant size - shared by the two cursor overlays above.
 *
 * @param Offset center The marker's center in area-local pixels.
 * @param Color tint The icon tint.
 */
private fun DrawScope.drawCursorMarker(center: Offset, tint: Color) {
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