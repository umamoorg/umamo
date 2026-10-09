package org.umamo.ui.viewport

/**
 * A work-surface area's grid geometry: the major line spacing and how many minor (subdivision) lines divide
 * each major cell.  Drives both the backdrop grid the area draws and the increment its grid snaps round to.
 * Each 2D viewport and UV editor area has one - its own once edited in its overlays popover, else the
 * application's grid for its surface (viewport.grid.* on a 2D viewport, viewport.uvGrid.* on a UV editor;
 * docs/format/UMA.md § 7.3 gridGeometry) - and reads the scale in its surface's unit: world units on a 2D
 * viewport, texels on a UV editor, where the grid is anchored at the image's origin and the page frame marks
 * an edge that falls mid-cell.  The constructor defaults are the 2D viewport setting's defaults and
 * [applicationDefault] gives each surface its own, so the built-in fallback and the preference are one number
 * per surface.
 *
 * @property Float scale The major grid line spacing, in world units on a 2D viewport and texels on a UV editor.
 * @property Int subdivisions The minor lines per major cell (must be at least 1).
 */
data class GridConfig(
	val scale: Float = ViewportSettings.GRID_SCALE_DEFAULT.toFloat(),
	val subdivisions: Int = ViewportSettings.GRID_SUBDIVISIONS_DEFAULT,
) {
	/**
	 * The grid snap increment in the scale's unit: the finest visible spacing (major / subdivisions), so
	 * Selection- and Cursor-to-Grid round to the minor grid lines the backdrop draws.  Subdivisions is
	 * clamped to at least 1 so the step is always a positive, finite value.
	 */
	val snapStep: Float
		get() = scale / subdivisions.coerceAtLeast(1)

	companion object {
		/**
		 * The application grid a surface follows before any setting is read: the bundled defaults of the pair of
		 * keys [ViewportSettings.gridScaleKey] and [ViewportSettings.gridSubdivisionsKey] name for it, so an area
		 * with no settings behind it shows the same grid a fresh install does.
		 *
		 * @param OverlaySurface surface The work surface.
		 * @return GridConfig The surface's default grid.
		 */
		fun applicationDefault(surface: OverlaySurface): GridConfig =
			when (surface) {
				OverlaySurface.Viewport2D -> GridConfig(ViewportSettings.GRID_SCALE_DEFAULT.toFloat(), ViewportSettings.GRID_SUBDIVISIONS_DEFAULT)
				OverlaySurface.UvEditor -> GridConfig(ViewportSettings.UV_GRID_SCALE_DEFAULT.toFloat(), ViewportSettings.UV_GRID_SUBDIVISIONS_DEFAULT)
			}
	}
}