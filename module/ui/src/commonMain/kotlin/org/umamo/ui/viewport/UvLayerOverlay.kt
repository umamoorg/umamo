package org.umamo.ui.viewport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera

/**
 * The UV editor's source-layer overlay: the mapping a drawable was authored with, drawn over the
 * artwork it was authored against rather than over the packed page.
 *
 * Read-only by design in this form.  Editing here would author the vertex-to-layer mapping, which is
 * a different edit from the page view's - it has to push its result back through the drawable's atlas
 * placement to reach the stored coordinates - and shipping the display first lets the recovered
 * mapping be trusted by eye before anything writes through it.  So this installs no pointer input at
 * all: pan / zoom and the context menu fall through to the layers below, and the mode-exclusive Edit
 * and Object gizmo overlays are simply not mounted alongside it.
 *
 * Drawn in the object-overlay style for the same reason the read-only page preview is: no
 * click-affordance dots, because there is nothing here to click.  Posed from the FRAME camera so it
 * lags with the GL layer image during pan / zoom, and unclipped by design so a mapping reaching past
 * its art stays visible - which is normal, since a mesh rings outside the opaque region it samples.
 *
 * @param EditorSession session The session owning the mesh selection whose select mode styles the draw.
 * @param List<GizmoMeshGeometry> geometries The shown mappings, in the layer's display space.
 * @param ViewportCamera? camera The displayed frame's camera; null hides the overlay (no frame yet).
 * @param Int widthPx The area width in pixels.
 * @param Int heightPx The area height in pixels.
 * @param Modifier modifier The layout modifier.
 */
@Composable
internal fun UvLayerOverlay(
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	camera: ViewportCamera?,
	widthPx: Int,
	heightPx: Int,
	modifier: Modifier = Modifier,
) {
	val meshSelection by session.meshSelection.collectAsState()
	val gizmoColors = rememberMeshEditColors()
	if (camera == null || geometries.isEmpty()) {
		return
	}
	// The wireframe composites OFFSCREEN: a default layer retains a display list that every window
	// repaint replays (re-stroking every edge), where the offscreen buffer rasterizes once per content
	// change and blits per frame.
	Canvas(
		modifier =
			modifier.fillMaxSize().graphicsLayer {
				compositingStrategy = CompositingStrategy.Offscreen
			},
	) {
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