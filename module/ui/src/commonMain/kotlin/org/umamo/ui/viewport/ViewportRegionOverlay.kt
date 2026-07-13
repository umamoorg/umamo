package org.umamo.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.drawCursor
import kotlin.math.abs
import kotlin.math.min

/** A drag shorter than this (px) is treated as a click (just disarm), not a region to frame. */
private const val REGION_DRAG_THRESHOLD_PX = 3f

/**
 * The Zoom Region overlay (Blender's Shift+B): a mode-agnostic top-level layer over the puppet image that,
 * while armed for this area, shows full-viewport crosshair guides and rubber-bands a box; on release it
 * frames that box into the viewport ([PuppetViewportService.zoomToRegion]).  Mounted above the Edit-mode
 * gizmo overlay so it captures the drag in Edit mode too, and self-gated on [EditorSession.zoomRegionArmedArea]
 * matching this [areaId] so only the armed area is live - every other area (and the unarmed state) composes
 * nothing here and passes pointer input through to the gizmo / navigation layers beneath.
 *
 * The crosshair cursor and dashed guides are drawn (the OS cursor is hidden while armed), so the affordance
 * matches the armed Box-select tool; the two share [drawCrosshairGuides].
 *
 * ズーム領域重畳（Shift+B）。モード非依存。対象エリアで武装中のみ全画面十字ガイドと破線ボックスを描き、
 * 離すとそのボックスをビューポートにフレーミングする。
 *
 * @param String areaId The viewport area this overlay covers.
 * @param PuppetViewportService service The render service (for the zoom-to-region call).
 * @param EditorSession session The session owning the armed-area flag.
 * @param ViewportCamera? camera The area's camera; null (no first fit yet) hides the overlay.
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun ViewportRegionOverlay(
	areaId: String,
	service: PuppetViewportService,
	session: EditorSession,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val armedArea by session.zoomRegionArmedArea.collectAsState()
	val overlayColors = LocalUmamoColors.current
	if (armedArea != areaId || camera == null) {
		return
	}
	val overlayStyle = selectionOverlayStyle(overlayColors)

	var lastPointer by remember(areaId) { mutableStateOf(Offset.Zero) }
	var regionStart by remember(areaId) { mutableStateOf<Offset?>(null) }
	var regionCurrent by remember(areaId) { mutableStateOf<Offset?>(null) }

	Box(
		modifier =
			modifier
				.fillMaxSize()
				.clipToBounds()
				// Hide the OS cursor so only the drawn crosshair shows while the gesture is armed.
				.pointerHoverIcon(hiddenPointerIcon(), overrideDescendants = true)
				.pointerInput(areaId) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull() ?: continue
							lastPointer = change.position
							when (event.type) {
								PointerEventType.Press ->
									// Right-click cancels the armed gesture; left-click begins the region drag.
									if (event.buttons.isSecondaryPressed) {
										session.disarmZoomRegion()
									} else if (event.buttons.isPrimaryPressed) {
										regionStart = change.position
										regionCurrent = change.position
										change.consume()
									}

								PointerEventType.Move ->
									if (regionStart != null) {
										regionCurrent = change.position
										change.consume()
									}

								PointerEventType.Release -> {
									val start = regionStart
									if (start != null) {
										val end = change.position
										// A real drag frames the box; a sub-threshold click just disarms (framing a
										// point would jump to MAX_ZOOM).
										if ((end - start).getDistance() > REGION_DRAG_THRESHOLD_PX) {
											service.zoomToRegion(areaId, start.x, start.y, end.x, end.y)
										}
										regionStart = null
										regionCurrent = null
										session.disarmZoomRegion()
										change.consume()
									}
								}

								else -> {}
							}
						}
					}
				},
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			val start = regionStart
			val current = regionCurrent
			if (regionStart == null) {
				drawCrosshairGuides(lastPointer, Size(widthPx.toFloat(), heightPx.toFloat()), overlayStyle)
			}
			if (start != null && current != null) {
				val topLeft = Offset(min(start.x, current.x), min(start.y, current.y))
				val boxSize = Size(abs(current.x - start.x), abs(current.y - start.y))
				drawSelectionBox(topLeft, boxSize, overlayStyle)
			}
			drawCursor(LocalUmamoCursors.crosshair, lastPointer)
		}
	}
}
