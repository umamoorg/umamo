package org.umamo.ui.viewport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.ui.rememberDoubleSetting
import org.umamo.ui.rememberIntSetting

/*
 * What the 2D viewport and the UV editor draw OVER the rigger's art, per area: ViewportOverlayColors.kt
 * holds the colors those overlays draw in, and this file holds whether each of them is shown and the grid
 * geometry the area draws and snaps to.  The state is an area's (two viewports may show different overlays
 * and different grids, as in Blender), parked on the hosting AreaScope by the space body, written into the
 * document's editor state as the `overlays` member of the area block's `viewport` or `uv` member
 * (docs/format/UMA.md § 7.3), and read by the overlays themselves through LocalAreaOverlays.
 */

/** Which work surface an overlay state belongs to; decides which overlays exist for it (a UV editor has no world axes). */
enum class OverlaySurface {
	Viewport2D,
	UvEditor,
}

/**
 * One area's overlay visibility and grid: the Show Overlays master plus one flag per overlay, the wireframe's
 * opacity, and the grid geometry the area draws and snaps to.  The master gates every overlay's EFFECT while
 * leaving each flag as the rigger set it, so switching it back on restores the set they had (Blender's
 * overlays toggle).  Consumers read the effective values, never the raw flags, so nothing downstream has to
 * know a master exists.  The grid is the area's own once edited, else the application's, resolved through
 * [grid].
 *
 * @param OverlaySurface surface The work surface this state belongs to.
 */
class ViewportOverlayState(val surface: OverlaySurface) {
	/** The Show Overlays master: false hides every overlay of the area at once. */
	var showOverlays by mutableStateOf(true)

	/** The backdrop grid lines. */
	var showGrid by mutableStateOf(true)

	/** The world axis lines (the 2D viewport only). */
	var showAxes by mutableStateOf(true)

	/** The 2D cursor marker (the UV cursor in a UV editor). */
	var showCursor by mutableStateOf(true)

	/** The informational text: the active-mesh label and the zoom readout. */
	var showInfo by mutableStateOf(true)

	/**
	 * The wireframe of every shown mesh - all of them in Object mode, those outside the edit in Edit mode, under
	 * the cage (the 2D viewport only); off by default, as in Blender.
	 */
	var showWireframe by mutableStateOf(false)

	/** The tint over the selected and active drawables (the 2D viewport only); off, the area draws the art as a capture does. */
	var showSelectionTint by mutableStateOf(true)

	/**
	 * The opacity, 0 to 1, of the mesh overlay drawn outside an edit: the wireframe on a 2D viewport, the islands
	 * on a UV editor, each drawn at its palette alpha times this.  The Edit cage keeps the palette.  At 0 none
	 * of it draws while the row that shows it stays as set.  Saved as the `wireframeOpacity` key on both surfaces.
	 */
	var wireframeOpacity by mutableStateOf(1f)

	/**
	 * The area's own grid geometry, or null while the area follows the application's viewport.grid.* setting.
	 * The first edit of a grid field in the overlays popover gives the area its own; the reset beside the fields
	 * takes it back.  Saved as the `gridGeometry` key on both surfaces: the scale is world units on a 2D viewport
	 * and texels on a UV editor, one grid model read in each surface's unit.
	 */
	var gridGeometry by mutableStateOf<GridConfig?>(null)

	/**
	 * The application's grid as [ApplicationGridMirror] last mirrored it from the settings; never saved.
	 * Mirrored here so every reader of the area - the snap handlers, the hovered-area commands, the popover's
	 * fields - resolves the one grid the renderer draws through [grid] without reaching for the settings
	 * themselves.
	 */
	var applicationGrid by mutableStateOf(GridConfig())

	/** The grid this area draws and snaps to: its own over the application's (see [gridOver]). */
	val grid: GridConfig
		get() = gridOver(applicationGrid)

	/** Whether the grid lines draw: the flag under the master. */
	val effectiveGrid: Boolean
		get() = showOverlays && showGrid

	/** Whether the world axes draw: the flag under the master. */
	val effectiveAxes: Boolean
		get() = showOverlays && showAxes

	/** Whether the cursor marker draws: the flag under the master. */
	val effectiveCursor: Boolean
		get() = showOverlays && showCursor

	/** Whether the informational text shows: the flag under the master. */
	val effectiveInfo: Boolean
		get() = showOverlays && showInfo

	/** Whether the wireframe draws: the flag under the master. */
	val effectiveWireframe: Boolean
		get() = showOverlays && showWireframe

	/** Whether the selection tint draws: the flag under the master. */
	val effectiveSelectionTint: Boolean
		get() = showOverlays && showSelectionTint

	/**
	 * Whether every flag and value sits at its default and the grid follows the application: what a fresh area
	 * shows, and what a save writes as nothing.
	 */
	val isAtDefaults: Boolean
		get() = showOverlays && showGrid && showAxes && showCursor && showInfo && !showWireframe && showSelectionTint && wireframeOpacity == 1f && gridGeometry == null

	/**
	 * The grid this area draws and snaps to over a given application grid: its own, else the application's,
	 * whole on both surfaces - a UV editor reads the scale as texels.
	 *
	 * @param GridConfig applicationGrid The application's viewport.grid.* grid.
	 * @return GridConfig The area's grid.
	 */
	fun gridOver(applicationGrid: GridConfig): GridConfig = gridGeometry ?: applicationGrid

	/**
	 * Returns every flag and value to its default and the grid to following the application.
	 */
	fun reset() {
		showOverlays = true
		showGrid = true
		showAxes = true
		showCursor = true
		showInfo = true
		showWireframe = false
		showSelectionTint = true
		wireframeOpacity = 1f
		gridGeometry = null
	}
}

/**
 * Keeps [state]'s mirror of the application's grid current from the viewport.grid.* settings, for the area's
 * readers that resolve its grid through [ViewportOverlayState.grid]: the popover's fields, the snap commands,
 * and the gizmo overlays' snap handlers.  Mounted by the space body that owns the state, with or without a
 * renderer behind the area, so a platform with no render host resolves the setting too and never the
 * built-in default.  Written after composition and only on a change, so an unchanged setting writes nothing.
 *
 * @param ViewportOverlayState state The area's overlay state.
 */
@Composable
fun ApplicationGridMirror(state: ViewportOverlayState) {
	val gridScale by rememberDoubleSetting(ViewportSettings.GRID_SCALE_KEY, ViewportSettings.GRID_SCALE_DEFAULT)
	val gridSubdivisions by rememberIntSetting(ViewportSettings.GRID_SUBDIVISIONS_KEY, ViewportSettings.GRID_SUBDIVISIONS_DEFAULT)
	val applicationGrid = GridConfig(gridScale.toFloat(), gridSubdivisions)
	SideEffect {
		if (state.applicationGrid != applicationGrid) {
			state.applicationGrid = applicationGrid
		}
	}
}

/**
 * The hosting area's overlay state, provided by the space body around its overlay stack; null outside an
 * area (a standalone shell, previews, tests), where every overlay shows.  Static: the instance is the
 * area's for its life and is never swapped, and its flags are Compose state, so a flip recomposes its readers.
 */
val LocalAreaOverlays = staticCompositionLocalOf<ViewportOverlayState?> { null }