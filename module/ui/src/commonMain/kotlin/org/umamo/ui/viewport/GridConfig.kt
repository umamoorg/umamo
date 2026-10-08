package org.umamo.ui.viewport

/**
 * A work-surface area's grid geometry: the major line spacing in world units and how many minor
 * (subdivision) lines divide each major cell.  Drives both the backdrop grid the area draws and the
 * increment its grid snaps round to.  Each 2D viewport and UV editor area has one - its own once edited in
 * its overlays popover, else the application's viewport.grid.* setting (docs/format/UMA.md § 7.3
 * gridGeometry); a UV editor reads the subdivisions alone, since its major spacing is the shown image.
 * The defaults are the setting's defaults, so the built-in fallback and the preference are one number.
 *
 * @property Float scale The major grid line spacing, in world units.
 * @property Int subdivisions The minor lines per major cell (must be at least 1).
 */
data class GridConfig(
	val scale: Float = ViewportSettings.GRID_SCALE_DEFAULT.toFloat(),
	val subdivisions: Int = ViewportSettings.GRID_SUBDIVISIONS_DEFAULT,
) {
	/**
	 * The grid snap increment in world units: the finest visible spacing (major / subdivisions), so
	 * Selection- and Cursor-to-Grid round to the minor grid lines the backdrop draws.  Subdivisions is
	 * clamped to at least 1 so the step is always a positive, finite value.
	 */
	val snapStep: Float
		get() = scale / subdivisions.coerceAtLeast(1)
}