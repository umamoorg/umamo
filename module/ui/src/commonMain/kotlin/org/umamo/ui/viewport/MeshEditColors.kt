package org.umamo.ui.viewport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import org.umamo.ui.graphics.parseHexColor
import org.umamo.ui.rememberStringSetting

/**
 * The settings keys and bundled defaults for the Edit-mode gizmo colors, one #AARRGGBB hex string per
 * element kind (vertex / edge / face) and state (idle / selected / active / off-key).  The defaults here
 * are kept in lockstep with defaultSettings.json (the merged-settings baseline); these constants are the
 * Kotlin-side fallback for a missing or unparseable value.
 */
internal object MeshEditColorSettings {
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
}

/**
 * The resolved Edit-mode gizmo palette: one Compose color per element kind and state, parsed from the
 * user's settings.  Held as a value so the overlay's draw pass reads plain fields.
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
 */
internal data class MeshEditColors(
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
)

/**
 * Resolves the Edit-mode gizmo palette from settings, reactively: each key is bound through
 * [rememberStringSetting], so a color edited in the preferences window recomposes the overlay live.  A
 * missing or unparseable value falls back to the bundled default constant.
 *
 * @return MeshEditColors The current palette.
 */
@Composable
internal fun rememberMeshEditColors(): MeshEditColors =
	MeshEditColors(
		vertexIdle = settingColor(MeshEditColorSettings.VERTEX_IDLE_KEY, MeshEditColorSettings.VERTEX_IDLE_DEFAULT),
		vertexSelected = settingColor(MeshEditColorSettings.VERTEX_SELECTED_KEY, MeshEditColorSettings.VERTEX_SELECTED_DEFAULT),
		vertexActive = settingColor(MeshEditColorSettings.VERTEX_ACTIVE_KEY, MeshEditColorSettings.VERTEX_ACTIVE_DEFAULT),
		vertexOffKey = settingColor(MeshEditColorSettings.VERTEX_OFFKEY_KEY, MeshEditColorSettings.VERTEX_OFFKEY_DEFAULT),
		edgeIdle = settingColor(MeshEditColorSettings.EDGE_IDLE_KEY, MeshEditColorSettings.EDGE_IDLE_DEFAULT),
		edgeSelected = settingColor(MeshEditColorSettings.EDGE_SELECTED_KEY, MeshEditColorSettings.EDGE_SELECTED_DEFAULT),
		edgeActive = settingColor(MeshEditColorSettings.EDGE_ACTIVE_KEY, MeshEditColorSettings.EDGE_ACTIVE_DEFAULT),
		edgeOffKey = settingColor(MeshEditColorSettings.EDGE_OFFKEY_KEY, MeshEditColorSettings.EDGE_OFFKEY_DEFAULT),
		faceIdle = settingColor(MeshEditColorSettings.FACE_IDLE_KEY, MeshEditColorSettings.FACE_IDLE_DEFAULT),
		faceSelected = settingColor(MeshEditColorSettings.FACE_SELECTED_KEY, MeshEditColorSettings.FACE_SELECTED_DEFAULT),
		faceActive = settingColor(MeshEditColorSettings.FACE_ACTIVE_KEY, MeshEditColorSettings.FACE_ACTIVE_DEFAULT),
		faceOffKey = settingColor(MeshEditColorSettings.FACE_OFFKEY_KEY, MeshEditColorSettings.FACE_OFFKEY_DEFAULT),
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
