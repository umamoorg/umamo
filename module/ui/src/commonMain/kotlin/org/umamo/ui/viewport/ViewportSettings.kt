package org.umamo.ui.viewport

/**
 * The settings keys and bundled defaults for the viewport interaction settings: the wheel-zoom
 * increments, the grid, and the rendering toggles.  The overlay colors live in
 * [ViewportColorSettings].  The defaults are kept in lockstep with defaultSettings.json (the
 * merged-settings baseline); these constants are the Kotlin-side fallback for a missing or
 * unparseable value, shared by the viewport binding and the preferences window so neither duplicates
 * key literals.
 */
internal object ViewportSettings {
	const val ZOOM_STEP_KEY = "viewport.zoomStepPercent"
	const val ZOOM_STEP_COARSE_KEY = "viewport.zoomStepCoarsePercent"
	const val GRID_SCALE_KEY = "viewport.grid.scale"
	const val GRID_SUBDIVISIONS_KEY = "viewport.grid.subdivisions"
	const val SUPERSAMPLE_KEY = "viewport.rendering.supersample"
	const val SUPERSAMPLE_WHILE_RESIZING_KEY = "viewport.rendering.supersampleWhileResizing"

	const val ZOOM_STEP_DEFAULT = 1.0
	const val ZOOM_STEP_COARSE_DEFAULT = 5.0

	/** Supersampling on by default; off renders everything at 1x (the weak-GPU escape hatch). */
	const val SUPERSAMPLE_DEFAULT = true

	/** On by default: frames rendered while an area is actively resizing keep the supersample. */
	const val SUPERSAMPLE_WHILE_RESIZING_DEFAULT = true

	const val GRID_SCALE_DEFAULT = 100.0
	const val GRID_SUBDIVISIONS_DEFAULT = 10

	/**
	 * The commit clamp for the zoom-step preference fields: 0.1 % (ultra-fine) up to 100 % (a
	 * doubling per wheel notch).  This bounds the per-notch step only; the camera's own zoom range
	 * clamp is separate and still applies downstream.
	 */
	val ZOOM_STEP_RANGE = 0.1f..100f

	/** The commit clamp for the grid major spacing (world units): 1 up to 100000, so the grid stays finite. */
	val GRID_SCALE_RANGE = 1f..100_000f

	/** The commit clamp for the grid subdivision count: 1 (no minor lines) up to 100 per major cell. */
	val GRID_SUBDIVISIONS_RANGE = 1..100
}