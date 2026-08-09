package org.umamo.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera

/**
 * The UV editor's Object-mode overlay, the mode-exclusive sibling of [UvGizmoOverlay] (each one
 * self-gates on the session's mode, the viewport overlay pair's convention): a read-only preview of
 * the selected drawables' mappings in the Blender object-overlay style - edges plus a faint face
 * fill, no vertex or face dots (objectOverlay ignores selectMode's handle rules), no selection
 * emphasis.
 *
 * One gesture is owned here, matching the viewport's Object gizmo: Shift+RightClick places the UV
 * cursor (consumed, so the context menu stays on the plain right-click).  Every other event falls
 * through - pan / zoom to the navigation layer beneath, the plain right-click to the context menu -
 * and the input runs regardless of the selection, since cursor placement needs no wireframe on
 * screen.
 *
 * Posed from the FRAME camera so it lags with the GL page during pan / zoom, and unclipped to the
 * page tile by design, so UVs outside it stay visible (the hosting area still clips to its own
 * bounds).
 *
 * @param String areaId The UV editor area this overlay covers (keys the pointer loop).
 * @param EditorSession session The session whose mode gates this overlay and whose select mode styles the fill.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' display-space gizmo geometry.
 * @param Int pageWidth The shown atlas page's width in texels (the display mapping's scale).
 * @param Int pageHeight The shown atlas page's height in texels.
 * @param ViewportCamera? camera The displayed frame's camera; null hides the overlay (no frame yet).
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun UvObjectOverlay(
	areaId: String,
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	pageWidth: Int,
	pageHeight: Int,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val mode by session.mode.collectAsState()
	val meshSelection by session.meshSelection.collectAsState()
	val gizmoColors = rememberMeshEditColors()
	if (mode == EditorMode.Edit || camera == null) {
		return
	}
	// Live values the areaId-keyed pointer loop reads, so a pan / resize / page hop is seen without
	// re-keying (the UvGizmoOverlay pattern).
	val liveCamera = rememberUpdatedState(camera)
	val liveSize = rememberUpdatedState(IntSize(widthPx, heightPx))
	val livePageWidth = rememberUpdatedState(pageWidth)
	val livePageHeight = rememberUpdatedState(pageHeight)
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.pointerInput(areaId) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							val change = event.changes.firstOrNull() ?: continue
							if (event.type == PointerEventType.Press &&
								event.buttons.isSecondaryPressed &&
								event.keyboardModifiers.isShiftPressed
							) {
								// Shift+RightClick places the UV cursor at the pointer (Blender's gesture,
								// the viewport's 2D-cursor placement in texture space); the cursor overlay
								// draws it and the Cursor pivot mode / snap pie anchor on it.
								val (displayX, displayY) = screenToWorld(change.position.x, change.position.y, liveCamera.value, liveSize.value)
								session.setUvCursor(
									displayToUvU(displayX, livePageWidth.value),
									displayToUvV(displayY, livePageHeight.value),
								)
								change.consume()
							}
						}
					}
				},
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			for (geometry in geometries) {
				drawMeshWireframe(
					positions = geometry.positions,
					indices = geometry.indices,
					edges = geometry.edges,
					highlight = buildHighlightSets(emptySet(), null, meshSelection.selectMode, geometry.indices),
					selectMode = meshSelection.selectMode,
					colors = gizmoColors,
					camera = camera,
					size = IntSize(widthPx, heightPx),
					objectOverlay = true,
				)
			}
		}
	}
}