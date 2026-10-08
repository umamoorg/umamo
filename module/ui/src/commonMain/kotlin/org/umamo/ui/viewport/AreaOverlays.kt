package org.umamo.ui.viewport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.umamo.edit.GridConfig
import org.umamo.render.FrameOverlays
import org.umamo.ui.rememberDoubleSetting
import org.umamo.ui.rememberIntSetting

/*
 * The per-area render options the editor hands the render service: each work-surface area's grid geometry
 * and its frame's overlays (grid lines, axes, mesh overlay), derived from the area's ViewportOverlayState
 * over the application's grid setting, pushed as the area registers and again whenever the value changes.
 * The engine keeps the value on the area's slot and compares it by value for freshness, so a flip
 * re-renders that area alone and the other areas of the document never notice.
 */

/**
 * What one area asks the renderer to draw beyond its scene: its grid geometry and its frame overlays.
 *
 * @property GridConfig    grid  The grid's major spacing and subdivisions.
 * @property FrameOverlays frame Whether the frame draws the grid lines, the world axes, and the mesh overlay.
 */
data class AreaOverlays(
	val grid: GridConfig,
	val frame: FrameOverlays,
) {
	companion object {
		/**
		 * The editor's defaults: the built-in grid geometry with every overlay shown, axes included.  What an
		 * area renders before its first push, and what a capture takes when no area stands behind it.
		 */
		val Default: AreaOverlays = AreaOverlays(GridConfig(), FrameOverlays(gridLines = true, axes = true, meshOverlay = true))
	}
}

/**
 * The render options an area's overlay state asks for over a grid geometry.  A null state (no area, as in a
 * standalone shell) shows everything; a UV editor never asks for the world axes, which its surface has none
 * of; the mesh overlay follows the Show Overlays master alone, since the Edit wireframe has no row of its own.
 *
 * @param ViewportOverlayState? state            The area's overlay state, or null for none.
 * @param Float                 gridScale        The grid's major spacing in world units.
 * @param Int                   gridSubdivisions The minor lines per major cell.
 * @return AreaOverlays The options to push.
 */
fun areaOverlaysFor(state: ViewportOverlayState?, gridScale: Float, gridSubdivisions: Int): AreaOverlays =
	AreaOverlays(
		GridConfig(gridScale, gridSubdivisions),
		FrameOverlays(
			gridLines = state?.effectiveGrid ?: true,
			axes = state == null || (state.surface == OverlaySurface.Viewport2D && state.effectiveAxes),
			meshOverlay = state?.showOverlays ?: true,
		),
	)

/**
 * Keeps the render service's copy of an area's render options current: the area's overlay state over the
 * application's grid setting, seeded as the area registers and pushed again whenever either changes.
 *
 * The seed is a composition-time push, like the registration it follows in the host, so the slot carries the
 * area's value before the resize that triggers its first render; the effect then re-pushes on a change only,
 * which the engine compares by value.
 *
 * @param PuppetViewportService service The render service the area registered with.
 * @param String                areaId  The area.
 * @param ViewportOverlayState? state   The area's overlay state, or null for none (everything shown).
 */
@Composable
fun AreaOverlaysPublisher(service: PuppetViewportService, areaId: String, state: ViewportOverlayState?) {
	val gridScale by rememberDoubleSetting(ViewportSettings.GRID_SCALE_KEY, ViewportSettings.GRID_SCALE_DEFAULT)
	val gridSubdivisions by rememberIntSetting(ViewportSettings.GRID_SUBDIVISIONS_KEY, ViewportSettings.GRID_SUBDIVISIONS_DEFAULT)
	val overlays = areaOverlaysFor(state, gridScale.toFloat(), gridSubdivisions)
	remember(areaId, service) { service.setAreaOverlays(areaId, overlays) }
	LaunchedEffect(areaId, service, overlays) {
		service.setAreaOverlays(areaId, overlays)
	}
}