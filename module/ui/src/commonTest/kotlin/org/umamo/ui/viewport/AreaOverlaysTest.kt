package org.umamo.ui.viewport

import org.umamo.edit.GridConfig
import org.umamo.render.FrameOverlays
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what an area's overlay state asks the renderer for: every effective flag under the master, the grid
 * geometry it is handed, no axes and no wireframe for a UV editor, and everything shown when there is no
 * area state at all.
 */
class AreaOverlaysTest {
	/** What a default 2D area asks for: everything but the wireframe, whose row is off by default. */
	private val viewportDefaults = FrameOverlays(gridLines = true, axes = true, meshOverlay = true, wireframe = false)

	/** A default 2D state over the built-in geometry asks for everything but the wireframe. */
	@Test
	fun aDefaultViewportStateAsksForEverythingButTheWireframe() {
		assertEquals(AreaOverlays(GridConfig(), viewportDefaults), areaOverlaysFor(ViewportOverlayState(OverlaySurface.Viewport2D), 100f, 10))
	}

	/** Each row's flag reaches its frame flag, and the geometry rides along untouched. */
	@Test
	fun eachFlagReachesTheFrame() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		state.showGrid = false
		assertEquals(AreaOverlays(GridConfig(50f, 4), viewportDefaults.copy(gridLines = false)), areaOverlaysFor(state, 50f, 4))
		state.showGrid = true
		state.showAxes = false
		assertEquals(viewportDefaults.copy(axes = false), areaOverlaysFor(state, 50f, 4).frame)
		state.showAxes = true
		state.showWireframe = true
		assertEquals(viewportDefaults.copy(wireframe = true), areaOverlaysFor(state, 50f, 4).frame)
	}

	/** The master off hides every frame flag at once while the geometry stays. */
	@Test
	fun theMasterOffHidesEveryFrameFlag() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		state.showWireframe = true
		state.showOverlays = false
		assertEquals(AreaOverlays(GridConfig(25f, 5), FrameOverlays(gridLines = false, axes = false, meshOverlay = false, wireframe = false)), areaOverlaysFor(state, 25f, 5))
	}

	/** A UV editor never asks for the world axes or the wireframe, whatever its flags say. */
	@Test
	fun aUvEditorNeverAsksForAxesOrTheWireframe() {
		val state = ViewportOverlayState(OverlaySurface.UvEditor)
		state.showWireframe = true
		assertEquals(FrameOverlays(gridLines = true, axes = false, meshOverlay = true, wireframe = false), areaOverlaysFor(state, 100f, 10).frame)
	}

	/** No area state (a standalone shell) shows everything, the wireframe included. */
	@Test
	fun noStateShowsEverything() {
		assertEquals(AreaOverlays.Default, areaOverlaysFor(null, 100f, 10))
	}
}