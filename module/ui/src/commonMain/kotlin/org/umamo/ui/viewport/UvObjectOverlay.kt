package org.umamo.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera

/**
 * The UV editor's Object-mode overlay, the mode-exclusive sibling of [UvGizmoOverlay] (each one
 * self-gates on the session's mode, the viewport overlay pair's convention): a read-only preview of
 * the selected drawables' mappings in the Blender object-overlay style - edges plus a faint face
 * fill, no vertex or face dots (objectOverlay ignores selectMode's handle rules), no selection
 * emphasis.  Draw-only: it installs no pointer input, so pan / zoom fall through to the navigation
 * layer beneath.
 *
 * Posed from the FRAME camera so it lags with the GL page during pan / zoom, and unclipped to the
 * page tile by design, so UVs outside it stay visible (the hosting area still clips to its own
 * bounds).
 *
 * @param EditorSession session The session whose mode gates this overlay and whose select mode styles the fill.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' display-space gizmo geometry.
 * @param ViewportCamera? camera The displayed frame's camera; null hides the overlay (no frame yet).
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun UvObjectOverlay(
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val mode by session.mode.collectAsState()
	val meshSelection by session.meshSelection.collectAsState()
	val gizmoColors = rememberMeshEditColors()
	if (mode == EditorMode.Edit || camera == null || geometries.isEmpty()) {
		return
	}
	Canvas(modifier = modifier.fillMaxSize()) {
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