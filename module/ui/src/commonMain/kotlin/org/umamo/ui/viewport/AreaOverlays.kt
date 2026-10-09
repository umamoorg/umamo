package org.umamo.ui.viewport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.umamo.render.FrameOverlays
import org.umamo.ui.rememberDoubleSetting
import org.umamo.ui.rememberIntSetting

/*
 * The per-area render options the editor hands the render service: each work-surface area's grid geometry
 * (its own, else the application's setting) and its frame's overlays (grid lines, axes, mesh overlay,
 * wireframe and its opacity, selection tint), derived from the area's ViewportOverlayState, pushed as the
 * area registers and again whenever the value changes.  The engine keeps the value on the area's slot and compares it by value for freshness,
 * so a flip or a grid edit re-renders that area alone and the other areas of the document never notice.
 */

/**
 * What one area asks the renderer to draw beyond its scene: its grid geometry and its frame overlays.
 *
 * @property GridConfig    grid  The grid's major spacing and subdivisions.
 * @property FrameOverlays frame Whether the frame draws the grid lines, the world axes, the mesh overlay, the
 *   wireframe and the selection tint, and how opaque the wireframe draws.
 */
data class AreaOverlays(
	val grid: GridConfig,
	val frame: FrameOverlays,
) {
	companion object {
		/**
		 * The editor's defaults: the built-in grid geometry with every overlay shown, axes and wireframe
		 * included, the wireframe at full opacity and the selection tinted.  What an area renders before its
		 * first push, and what a capture takes when no area stands behind it (a capture draws no mesh overlay
		 * and no selection, so the wireframe and the tint are moot there).
		 */
		val Default: AreaOverlays = AreaOverlays(GridConfig(), FrameOverlays(gridLines = true, axes = true, meshOverlay = true))
	}
}

/**
 * The render options an area's overlay state asks for over the application's grid.  A null state (no area, as
 * in a standalone shell) shows everything on the application's grid; an area with a grid of its own draws that
 * one; a UV editor never asks for the world axes, the wireframe, or the tint off, which its surface has no
 * rows for; the mesh overlay follows the Show Overlays master alone, since the Edit cage has no row of its
 * own, while the wireframe and the tint follow their rows under the master; the wireframe's opacity is the
 * area's on both surfaces, a UV editor's islands fading by it.
 *
 * @param ViewportOverlayState? state           The area's overlay state, or null for none.
 * @param GridConfig            applicationGrid The application's viewport.grid.* grid.
 * @return AreaOverlays The options to push.
 */
fun areaOverlaysFor(state: ViewportOverlayState?, applicationGrid: GridConfig): AreaOverlays =
	AreaOverlays(
		state?.gridOver(applicationGrid) ?: applicationGrid,
		FrameOverlays(
			gridLines = state?.effectiveGrid ?: true,
			axes = state == null || (state.surface == OverlaySurface.Viewport2D && state.effectiveAxes),
			meshOverlay = state?.showOverlays ?: true,
			wireframe = state == null || (state.surface == OverlaySurface.Viewport2D && state.effectiveWireframe),
			selectionTint = state == null || state.surface == OverlaySurface.UvEditor || state.effectiveSelectionTint,
			wireframeOpacity = state?.wireframeOpacity ?: 1f,
		),
	)

/**
 * Keeps the render service's copy of an area's render options current: the area's overlay state over the
 * application's grid setting, seeded as the area registers and pushed again whenever either changes.
 *
 * The seed is a composition-time push, like the registration it follows in the host, so the slot carries the
 * area's value before the resize that triggers its first render; the effect then re-pushes on a change only,
 * which the engine compares by value.  The pushed value takes the setting directly, never the state's mirror
 * of it: the mirror is the space body's ([ApplicationGridMirror]), written after composition and only on a
 * change, and reading it back here would cost a second composition and push per setting change.
 *
 * @param PuppetViewportService service The render service the area registered with.
 * @param String                areaId  The area.
 * @param ViewportOverlayState? state   The area's overlay state, or null for none (everything shown).
 */
@Composable
fun AreaOverlaysPublisher(service: PuppetViewportService, areaId: String, state: ViewportOverlayState?) {
	val gridScale by rememberDoubleSetting(ViewportSettings.GRID_SCALE_KEY, ViewportSettings.GRID_SCALE_DEFAULT)
	val gridSubdivisions by rememberIntSetting(ViewportSettings.GRID_SUBDIVISIONS_KEY, ViewportSettings.GRID_SUBDIVISIONS_DEFAULT)
	val applicationGrid = GridConfig(gridScale.toFloat(), gridSubdivisions)
	val overlays = areaOverlaysFor(state, applicationGrid)
	remember(areaId, service) { service.setAreaOverlays(areaId, overlays) }
	LaunchedEffect(areaId, service, overlays) {
		service.setAreaOverlays(areaId, overlays)
	}
}