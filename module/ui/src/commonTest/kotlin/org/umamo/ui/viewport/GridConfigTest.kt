package org.umamo.ui.viewport

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the grid's snap increment and its defaults, which are the application settings' per surface. */
class GridConfigTest {
	/** The snap step is the finest visible spacing: the scale over the subdivision count. */
	@Test
	fun snapStepIsScaleOverSubdivisions() {
		assertEquals(10f, GridConfig(scale = 100f, subdivisions = 10).snapStep, "100 / 10 = 10")
		assertEquals(25f, GridConfig(scale = 100f, subdivisions = 4).snapStep, "100 / 4 = 25")
		// subdivisions is clamped to at least 1, so a degenerate 0 never divides by zero.
		assertEquals(100f, GridConfig(scale = 100f, subdivisions = 0).snapStep, "0 subdivisions clamps to 1")
	}

	/** A defaulted grid is the 2D viewport setting's default grid, so the built-in fallback and the preference agree. */
	@Test
	fun theDefaultsAreTheSettingsDefaults() {
		assertEquals(GridConfig(ViewportSettings.GRID_SCALE_DEFAULT.toFloat(), ViewportSettings.GRID_SUBDIVISIONS_DEFAULT), GridConfig())
		assertEquals(ViewportSettings.GRID_SCALE_DEFAULT.toFloat() / ViewportSettings.GRID_SUBDIVISIONS_DEFAULT, GridConfig().snapStep, "default step")
	}

	/** Each surface's application default is its own setting pair's: the 2D viewport's world grid, the UV editor's texel grid. */
	@Test
	fun theApplicationDefaultIsTheSurfacesSettingPair() {
		assertEquals(GridConfig(), GridConfig.applicationDefault(OverlaySurface.Viewport2D), "the 2D viewport's is the constructor default")
		assertEquals(GridConfig(100f, 10), GridConfig.applicationDefault(OverlaySurface.Viewport2D))
		assertEquals(GridConfig(256f, 8), GridConfig.applicationDefault(OverlaySurface.UvEditor), "the UV editor's is 256 texels in 8")
		assertEquals(32f, GridConfig.applicationDefault(OverlaySurface.UvEditor).snapStep, "so a UV grid snap rounds to 32 texels")
	}

	/** The key a surface follows is its own pair, never the other surface's. */
	@Test
	fun eachSurfaceFollowsItsOwnKeys() {
		assertEquals("viewport.grid.scale", ViewportSettings.gridScaleKey(OverlaySurface.Viewport2D))
		assertEquals("viewport.grid.subdivisions", ViewportSettings.gridSubdivisionsKey(OverlaySurface.Viewport2D))
		assertEquals("viewport.uvGrid.scale", ViewportSettings.gridScaleKey(OverlaySurface.UvEditor))
		assertEquals("viewport.uvGrid.subdivisions", ViewportSettings.gridSubdivisionsKey(OverlaySurface.UvEditor))
	}
}