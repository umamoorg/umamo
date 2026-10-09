package org.umamo.ui.viewport.uv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.viewport.LocalAreaOverlays
import org.umamo.ui.viewport.gizmo.drawCursorMarker
import org.umamo.ui.viewport.gizmo.worldToScreen

/**
 * The UV cursor marker (the texture-space 2D cursor), the UV editor's twin of [org.umamo.ui.viewport.viewport2d.Cursor2dOverlay]:
 * the same crosshair at the cursor's display-space position, present in both modes and drawn above
 * the gizmo chrome for viewport parity.  Draw-only; placement stays a gizmo gesture
 * (Shift+RightClick, handled by the mode's own overlay in both modes).
 *
 * The cursor is stored in ATLAS coordinates, so the shown surface's frame is what puts it in the
 * right place: over a page that is the plain texel mapping, and over a source layer it also carries
 * the drawable's placement.  One cursor either way - the same point on the art, wherever it is seen.
 *
 * The area's overlays control (its 2D Cursor row, or the Show Overlays master) hides the marker through
 * LocalAreaOverlays; the cursor stays placed, and the pivot and snap commands keep reading it.
 *
 * @param EditorSession session The session whose UV cursor this overlay draws.
 * @param UvEditFrame frame The shown surface's texel size plus its conversion from stored coordinates.
 * @param ViewportCamera? camera The displayed frame's camera; null skips drawing.
 * @param Int widthPx The area width in px.
 * @param Int heightPx The area height in px.
 * @param Modifier modifier The layout modifier (the host passes a stack fill).
 */
@Composable
internal fun UvCursorOverlay(
	session: EditorSession,
	frame: UvEditFrame,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val cursor by session.uvCursor.collectAsState()
	val cursorColors = LocalUmamoColors.current
	val shown = LocalAreaOverlays.current?.effectiveCursor ?: true
	val cursorLabel = stringResource(Res.string.overlay_row_cursor)
	val cursorToDraw = cursor
	if (cursorToDraw == null || camera == null || !shown) {
		return
	}
	val (cursorDisplayX, cursorDisplayY) = frame.displayAt(cursorToDraw.u, cursorToDraw.v)
	// Named for accessibility and for tests: a draw-only canvas is otherwise invisible to the semantics tree.
	Canvas(modifier = modifier.fillMaxSize().semantics { contentDescription = cursorLabel }) {
		drawCursorMarker(
			center = worldToScreen(cursorDisplayX, cursorDisplayY, camera, IntSize(widthPx, heightPx)),
			tint = cursorColors.viewportBadgeText,
		)
	}
}

/**
 * Places the UV cursor where a Shift+RightClick lands on the shown surface: the display point converts
 * through the surface's frame into the ATLAS coordinates the cursor is stored in.  Both UV gizmo overlays
 * place it this way, over a page and over a source layer alike.
 *
 * @param EditorSession session The session owning the UV cursor.
 * @param UvEditFrame frame The shown surface's frame.
 * @param Float displayX The display x in texels.
 * @param Float displayY The display y in texels.
 */
internal fun placeUvCursor(session: EditorSession, frame: UvEditFrame, displayX: Float, displayY: Float) {
	val (cursorU, cursorV) = frame.storedUvAt(displayX, displayY)
	session.setUvCursor(cursorU, cursorV)
}

/**
 * Where the UV cursor sits on the shown surface, in display space: the Cursor pivot both UV gizmo overlays
 * transform about.
 *
 * @param EditorSession session The session owning the UV cursor.
 * @param UvEditFrame frame The shown surface's frame.
 * @return Pair<Float, Float>? The cursor's display (x, y), or null when it is unplaced.
 */
internal fun uvCursorDisplay(session: EditorSession, frame: UvEditFrame): Pair<Float, Float>? =
	session.uvCursor.value?.let { cursor -> frame.displayAt(cursor.u, cursor.v) }