package org.umamo.ui.viewport

import org.umamo.render.FrameOverlays
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what an area's overlay state asks the renderer for: every effective flag under the master, the grid
 * geometry it is handed (its own over the application's), no axes and no wireframe for a UV editor, and
 * everything shown on the application's grid when there is no area state at all.
 */
class AreaOverlaysTest {
	/** The application's grid the tests hand the area. */
	private val applicationGrid = GridConfig(100f, 10)

	/** What a default 2D area asks for: everything but the wireframe, whose row is off by default. */
	private val viewportDefaults = FrameOverlays(gridLines = true, axes = true, meshOverlay = true, wireframe = false)

	/** A default 2D state asks for everything but the wireframe, on the application's grid. */
	@Test
	fun aDefaultViewportStateAsksForEverythingButTheWireframe() {
		assertEquals(AreaOverlays(applicationGrid, viewportDefaults), areaOverlaysFor(ViewportOverlayState(OverlaySurface.Viewport2D), applicationGrid))
	}

	/** Each row's flag reaches its frame flag, and the geometry rides along untouched. */
	@Test
	fun eachFlagReachesTheFrame() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		state.showGrid = false
		assertEquals(AreaOverlays(GridConfig(50f, 4), viewportDefaults.copy(gridLines = false)), areaOverlaysFor(state, GridConfig(50f, 4)))
		state.showGrid = true
		state.showAxes = false
		assertEquals(viewportDefaults.copy(axes = false), areaOverlaysFor(state, GridConfig(50f, 4)).frame)
		state.showAxes = true
		state.showWireframe = true
		assertEquals(viewportDefaults.copy(wireframe = true), areaOverlaysFor(state, GridConfig(50f, 4)).frame)
	}

	/** An area with a grid of its own asks for that grid, whatever the application's is. */
	@Test
	fun anOwnGridReplacesTheApplications() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		state.gridGeometry = GridConfig(25f, 5)
		assertEquals(GridConfig(25f, 5), areaOverlaysFor(state, applicationGrid).grid)

		val uvState = ViewportOverlayState(OverlaySurface.UvEditor)
		uvState.gridGeometry = GridConfig(25f, 5)
		assertEquals(GridConfig(100f, 5), areaOverlaysFor(uvState, applicationGrid).grid, "a UV editor's own grid is its subdivisions over the application's scale")
	}

	/** The master off hides every frame flag at once while the geometry stays. */
	@Test
	fun theMasterOffHidesEveryFrameFlag() {
		val state = ViewportOverlayState(OverlaySurface.Viewport2D)
		state.showWireframe = true
		state.showOverlays = false
		assertEquals(AreaOverlays(GridConfig(25f, 5), FrameOverlays(gridLines = false, axes = false, meshOverlay = false, wireframe = false)), areaOverlaysFor(state, GridConfig(25f, 5)))
	}

	/** A UV editor never asks for the world axes or the wireframe, whatever its flags say. */
	@Test
	fun aUvEditorNeverAsksForAxesOrTheWireframe() {
		val state = ViewportOverlayState(OverlaySurface.UvEditor)
		state.showWireframe = true
		assertEquals(FrameOverlays(gridLines = true, axes = false, meshOverlay = true, wireframe = false), areaOverlaysFor(state, applicationGrid).frame)
	}

	/** No area state (a standalone shell) shows everything, the wireframe included, on the application's grid. */
	@Test
	fun noStateShowsEverything() {
		assertEquals(AreaOverlays.Default, areaOverlaysFor(null, GridConfig()))
		assertEquals(GridConfig(50f, 4), areaOverlaysFor(null, GridConfig(50f, 4)).grid)
	}
}