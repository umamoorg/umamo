package org.umamo.ui.viewport

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the grid's snap increment and its defaults, which are the application setting's. */
class GridConfigTest {
	/** The snap step is the finest visible spacing: the scale over the subdivision count. */
	@Test
	fun snapStepIsScaleOverSubdivisions() {
		assertEquals(10f, GridConfig(scale = 100f, subdivisions = 10).snapStep, "100 / 10 = 10")
		assertEquals(25f, GridConfig(scale = 100f, subdivisions = 4).snapStep, "100 / 4 = 25")
		// subdivisions is clamped to at least 1, so a degenerate 0 never divides by zero.
		assertEquals(100f, GridConfig(scale = 100f, subdivisions = 0).snapStep, "0 subdivisions clamps to 1")
	}

	/** A defaulted grid is the setting's default grid, so the built-in fallback and the preference agree. */
	@Test
	fun theDefaultsAreTheSettingsDefaults() {
		assertEquals(GridConfig(ViewportSettings.GRID_SCALE_DEFAULT.toFloat(), ViewportSettings.GRID_SUBDIVISIONS_DEFAULT), GridConfig())
		assertEquals(ViewportSettings.GRID_SCALE_DEFAULT.toFloat() / ViewportSettings.GRID_SUBDIVISIONS_DEFAULT, GridConfig().snapStep, "default step")
	}
}