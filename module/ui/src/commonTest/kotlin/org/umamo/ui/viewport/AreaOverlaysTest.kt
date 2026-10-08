package org.umamo.ui.viewport

import org.umamo.edit.GridConfig
import org.umamo.render.FrameOverlays
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what an area's overlay state asks the renderer for: every effective flag under the master, the grid
 * geometry it is handed, no axes for a UV editor, and everything shown when there is no area state at all.
 */
class AreaOverlaysTest {
	/** A default 2D state over the built-in geometry is the editor's default, axes included. */
	@Test
	fun aDefaultViewportStateAsksForTheEditorsDefaults() {
		assertEquals(AreaOverlays.Default, areaOverlaysFor(ViewportOverlayState(OverlaySurface.Viewport2D), 100f, 10))
	}

	/** Each row's flag reaches its frame flag, and the geometry rides along untouched. */
	@Test
	fun eachFlagReachesTheFrame() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		state.showGrid = false
		assertEquals(AreaOverlays(GridConfig(50f, 4), FrameOverlays(gridLines = false, axes = true, meshOverlay = true)), areaOverlaysFor(state, 50f, 4))
		state.showGrid = true
		state.showAxes = false
		assertEquals(FrameOverlays(gridLines = true, axes = false, meshOverlay = true), areaOverlaysFor(state, 50f, 4).frame)
	}

	/** The master off hides every frame flag at once while the geometry stays. */
	@Test
	fun theMasterOffHidesEveryFrameFlag() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		state.showOverlays = false
		assertEquals(AreaOverlays(GridConfig(25f, 5), FrameOverlays(gridLines = false, axes = false, meshOverlay = false)), areaOverlaysFor(state, 25f, 5))
	}

	/** A UV editor never asks for the world axes, whatever its flag says. */
	@Test
	fun aUvEditorNeverAsksForAxes() {
		val state = ViewportOverlayState(OverlaySurface.UvEditor)
		assertEquals(FrameOverlays(gridLines = true, axes = false, meshOverlay = true), areaOverlaysFor(state, 100f, 10).frame)
	}

	/** No area state (a standalone shell) shows everything. */
	@Test
	fun noStateShowsEverything() {
		assertEquals(AreaOverlays.Default, areaOverlaysFor(null, 100f, 10))
	}
}