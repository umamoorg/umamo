package org.umamo.ui.viewport

/**
 * The settings keys and bundled defaults for the viewport interaction settings: the wheel-zoom
 * increments and the selection highlight tint.  The defaults are kept in lockstep with
 * defaultSettings.json (the merged-settings baseline); these constants are the Kotlin-side fallback
 * for a missing or unparseable value, shared by the viewport binding and the preferences window so
 * neither duplicates key literals (the same split as [MeshEditColorSettings]).
 */
internal object ViewportSettings {
	const val ZOOM_STEP_KEY = "viewport.zoomStepPercent"
	const val ZOOM_STEP_COARSE_KEY = "viewport.zoomStepCoarsePercent"
	const val SELECTION_HIGHLIGHT_KEY = "viewport.selectionHighlightColor"
	const val GRID_SCALE_KEY = "viewport.grid.scale"
	const val GRID_SUBDIVISIONS_KEY = "viewport.grid.subdivisions"

	const val ZOOM_STEP_DEFAULT = 1.0
	const val ZOOM_STEP_COARSE_DEFAULT = 5.0
	const val SELECTION_HIGHLIGHT_DEFAULT = "#338CFF"
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

/**
 * Parses a selection-highlight hex color (#RRGGBB or #AARRGGBB, the # optional) into its three 0..1
 * sRGB components, falling back to the built-in highlight color when the string is absent or
 * malformed.  Both digit counts are accepted because defaultSettings.json seeds #RRGGBB while the
 * preferences HexColorField commits canonical #AARRGGBB; the alpha byte is ignored - the GL highlight
 * mix has no alpha term.  The components match what the highlight shader mixes (Compose-style 0..1
 * sRGB), so the parse divides each byte by 255.
 *
 * @param String? hex The configured hex color, or null when unset.
 * @return Triple The (red, green, blue) components, each 0..1.
 */
internal fun parseSelectionHighlightColor(hex: String?): Triple<Float, Float, Float> {
	val cleaned = (hex ?: ViewportSettings.SELECTION_HIGHLIGHT_DEFAULT).trim().removePrefix("#")
	val packed =
		when (cleaned.length) {
			6 -> cleaned.toLongOrNull(16)
			8 -> cleaned.toLongOrNull(16)?.and(0xFFFFFF)
			else -> null
		} ?: ViewportSettings.SELECTION_HIGHLIGHT_DEFAULT.removePrefix("#").toLong(16)
	return Triple(
		((packed shr 16) and 0xFF) / 255f,
		((packed shr 8) and 0xFF) / 255f,
		(packed and 0xFF) / 255f,
	)
}
