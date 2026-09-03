package org.umamo.ui.viewport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import org.umamo.ui.graphics.parseHexColor
import org.umamo.ui.rememberStringSetting

/*
 * The settings-backed colors the viewport and UV editor draw OVER the rigger's art - the Edit-mode
 * element palette, the placement state colors (the collision warning, the pinned highlight), and the
 * selection highlights the GL viewport tints with.  User colors (Settings > Colors), never theme
 * colors: no theme can know what reads against a given piece of art.  One key table, one resolved
 * palette, one reactive binding, so a color edited in the preferences window recomposes every overlay
 * live.  A new over-art color belongs here, never in UmamoColors.
 */

/**
 * The settings keys and bundled defaults for the viewport overlay colors, one #AARRGGBB hex string
 * each: the Edit-mode element palette (vertex / edge / face by idle / selected / active / off-key),
 * the placement state colors, and the selection highlights.  The defaults here are kept in lockstep
 * with defaultSettings.json (the merged-settings baseline); these constants are the Kotlin-side
 * fallback for a missing or unparseable value, shared by the overlays, the GL viewport binding, and
 * the preferences window so none of them duplicates a key literal.
 */
internal object ViewportColorSettings {
	const val VERTEX_IDLE_KEY = "viewport.meshEdit.vertexIdle"
	const val VERTEX_SELECTED_KEY = "viewport.meshEdit.vertexSelected"
	const val VERTEX_ACTIVE_KEY = "viewport.meshEdit.vertexActive"
	const val VERTEX_OFFKEY_KEY = "viewport.meshEdit.vertexOffKey"
	const val EDGE_IDLE_KEY = "viewport.meshEdit.edgeIdle"
	const val EDGE_SELECTED_KEY = "viewport.meshEdit.edgeSelected"
	const val EDGE_ACTIVE_KEY = "viewport.meshEdit.edgeActive"
	const val EDGE_OFFKEY_KEY = "viewport.meshEdit.edgeOffKey"
	const val FACE_IDLE_KEY = "viewport.meshEdit.faceIdle"
	const val FACE_SELECTED_KEY = "viewport.meshEdit.faceSelected"
	const val FACE_ACTIVE_KEY = "viewport.meshEdit.faceActive"
	const val FACE_OFFKEY_KEY = "viewport.meshEdit.faceOffKey"
	const val WARNING_COLOR_KEY = "viewport.warningColor"
	const val PINNED_PLACEMENT_COLOR_KEY = "viewport.pinnedPlacementColor"
	const val SELECTION_HIGHLIGHT_KEY = "viewport.selectionHighlightColor"
	const val ACTIVE_SELECTION_HIGHLIGHT_KEY = "viewport.activeSelectionHighlightColor"

	const val VERTEX_IDLE_DEFAULT = "#FFFF00EC"
	const val VERTEX_SELECTED_DEFAULT = "#FFFF7A00"
	const val VERTEX_ACTIVE_DEFAULT = "#FF7DE400"
	const val VERTEX_OFFKEY_DEFAULT = "#66888888"
	const val EDGE_IDLE_DEFAULT = "#99000000"
	const val EDGE_SELECTED_DEFAULT = "#FFFF7A00"
	const val EDGE_ACTIVE_DEFAULT = "#FF7DE400"
	const val EDGE_OFFKEY_DEFAULT = "#44888888"
	const val FACE_IDLE_DEFAULT = "#22000000"
	const val FACE_SELECTED_DEFAULT = "#66FF7A00"
	const val FACE_ACTIVE_DEFAULT = "#FF7DE400"
	const val FACE_OFFKEY_DEFAULT = "#44888888"
	const val WARNING_COLOR_DEFAULT = "#FFFF5A5A"
	const val PINNED_PLACEMENT_COLOR_DEFAULT = "#FFB266FF"
	const val SELECTION_HIGHLIGHT_DEFAULT = "#FF338CFF"
	const val ACTIVE_SELECTION_HIGHLIGHT_DEFAULT = "#FF7DE400"
}

/**
 * The resolved overlay palette: one Compose color per role, parsed from the user's settings.  Held
 * as a value so an overlay's draw pass reads plain fields, and passed whole to the wireframe drawer,
 * which substitutes per-island roles by copying it.
 *
 * @property Color vertexIdle Unselected vertex dots.
 * @property Color vertexSelected Selected vertex dots.
 * @property Color vertexActive The active (last-touched) vertex dot.
 * @property Color vertexOffKey Vertex dots while the pose is between keys (read-only).
 * @property Color edgeIdle Unselected wireframe edges.
 * @property Color edgeSelected Selected (or derived-selected) edges.
 * @property Color edgeActive The active edge.
 * @property Color edgeOffKey Edges while the pose is between keys.
 * @property Color faceIdle Unselected face fills (face mode only).
 * @property Color faceSelected Selected (or derived-selected) face fills.
 * @property Color faceActive The active face's centroid dot (never a fill - it would blank the art).
 * @property Color faceOffKey Face fills while the pose is between keys.
 * @property Color warning The stroke of a placement warning affordance - a tile overlapping a neighbor
 *   or spilling off its page - on every edge role while the warning stands.
 * @property Color pinnedPlacement The edges of a pinned tile's islands in the UV editor, on every edge role.
 * @property Color selectionHighlight The tint the GL viewport mixes over selected drawables.
 * @property Color activeSelectionHighlight The tint the GL viewport mixes over the active drawable.
 */
internal data class ViewportOverlayColors(
	val vertexIdle: Color,
	val vertexSelected: Color,
	val vertexActive: Color,
	val vertexOffKey: Color,
	val edgeIdle: Color,
	val edgeSelected: Color,
	val edgeActive: Color,
	val edgeOffKey: Color,
	val faceIdle: Color,
	val faceSelected: Color,
	val faceActive: Color,
	val faceOffKey: Color,
	val warning: Color,
	val pinnedPlacement: Color,
	val selectionHighlight: Color,
	val activeSelectionHighlight: Color,
)

/**
 * Resolves the overlay palette from settings, reactively: each key is bound through
 * [rememberStringSetting], so a color edited in the preferences window recomposes the overlays live.
 * A missing or unparseable value falls back to the bundled default constant.
 *
 * @return ViewportOverlayColors The current palette.
 */
@Composable
internal fun rememberViewportOverlayColors(): ViewportOverlayColors =
	ViewportOverlayColors(
		vertexIdle = settingColor(ViewportColorSettings.VERTEX_IDLE_KEY, ViewportColorSettings.VERTEX_IDLE_DEFAULT),
		vertexSelected = settingColor(ViewportColorSettings.VERTEX_SELECTED_KEY, ViewportColorSettings.VERTEX_SELECTED_DEFAULT),
		vertexActive = settingColor(ViewportColorSettings.VERTEX_ACTIVE_KEY, ViewportColorSettings.VERTEX_ACTIVE_DEFAULT),
		vertexOffKey = settingColor(ViewportColorSettings.VERTEX_OFFKEY_KEY, ViewportColorSettings.VERTEX_OFFKEY_DEFAULT),
		edgeIdle = settingColor(ViewportColorSettings.EDGE_IDLE_KEY, ViewportColorSettings.EDGE_IDLE_DEFAULT),
		edgeSelected = settingColor(ViewportColorSettings.EDGE_SELECTED_KEY, ViewportColorSettings.EDGE_SELECTED_DEFAULT),
		edgeActive = settingColor(ViewportColorSettings.EDGE_ACTIVE_KEY, ViewportColorSettings.EDGE_ACTIVE_DEFAULT),
		edgeOffKey = settingColor(ViewportColorSettings.EDGE_OFFKEY_KEY, ViewportColorSettings.EDGE_OFFKEY_DEFAULT),
		faceIdle = settingColor(ViewportColorSettings.FACE_IDLE_KEY, ViewportColorSettings.FACE_IDLE_DEFAULT),
		faceSelected = settingColor(ViewportColorSettings.FACE_SELECTED_KEY, ViewportColorSettings.FACE_SELECTED_DEFAULT),
		faceActive = settingColor(ViewportColorSettings.FACE_ACTIVE_KEY, ViewportColorSettings.FACE_ACTIVE_DEFAULT),
		faceOffKey = settingColor(ViewportColorSettings.FACE_OFFKEY_KEY, ViewportColorSettings.FACE_OFFKEY_DEFAULT),
		warning = settingColor(ViewportColorSettings.WARNING_COLOR_KEY, ViewportColorSettings.WARNING_COLOR_DEFAULT),
		pinnedPlacement = settingColor(ViewportColorSettings.PINNED_PLACEMENT_COLOR_KEY, ViewportColorSettings.PINNED_PLACEMENT_COLOR_DEFAULT),
		selectionHighlight = settingColor(ViewportColorSettings.SELECTION_HIGHLIGHT_KEY, ViewportColorSettings.SELECTION_HIGHLIGHT_DEFAULT),
		activeSelectionHighlight = settingColor(ViewportColorSettings.ACTIVE_SELECTION_HIGHLIGHT_KEY, ViewportColorSettings.ACTIVE_SELECTION_HIGHLIGHT_DEFAULT),
	)

/**
 * Binds one color setting reactively and parses it, falling back to the bundled default when the stored
 * text is not a valid hex color (the default constants always parse).
 *
 * @param String key The dotted settings key.
 * @param String defaultHex The bundled default #AARRGGBB string.
 * @return Color The parsed color.
 */
@Composable
private fun settingColor(key: String, defaultHex: String): Color {
	val hexText by rememberStringSetting(key, defaultHex)
	return parseHexColor(hexText) ?: parseHexColor(defaultHex)!!
}

/**
 * Parses a selection-highlight hex color (#RRGGBB or #AARRGGBB, the # optional) into its three 0..1
 * sRGB components, falling back to the built-in highlight color when the string is absent or
 * malformed.  Both digit counts are accepted because a hand-typed or older user setting may hold
 * #RRGGBB while the preferences HexColorField commits canonical #AARRGGBB; the alpha byte is
 * ignored - the GL highlight mix has no alpha term.  The components match what the highlight shader
 * mixes (Compose-style 0..1 sRGB), so the parse divides each byte by 255.  Not a Composable, because
 * the viewport binding reads the setting outside composition.
 *
 * @param String? hex The configured hex color, or null when unset.
 * @return Triple The (red, green, blue) components, each 0..1.
 */
internal fun parseSelectionHighlightColor(hex: String?): Triple<Float, Float, Float> {
	val cleaned = (hex ?: ViewportColorSettings.SELECTION_HIGHLIGHT_DEFAULT).trim().removePrefix("#")
	val packed =
		when (cleaned.length) {
			6 -> cleaned.toLongOrNull(16)
			8 -> cleaned.toLongOrNull(16)?.and(0xFFFFFF)
			else -> null
		} ?: ViewportColorSettings.SELECTION_HIGHLIGHT_DEFAULT.removePrefix("#").toLong(16)
	return Triple(
		((packed shr 16) and 0xFF) / 255f,
		((packed shr 8) and 0xFF) / 255f,
		(packed and 0xFF) / 255f,
	)
}