package org.umamo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.HexColorField
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.SelectField
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.rememberDoubleSetting
import org.umamo.ui.rememberIntSetting
import org.umamo.ui.rememberStringSetting
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.settings_colors_group_edge
import org.umamo.ui.resources.settings_colors_group_face
import org.umamo.ui.resources.settings_colors_group_vertex
import org.umamo.ui.resources.settings_colors_mesh_edit
import org.umamo.ui.resources.settings_colors_role_active
import org.umamo.ui.resources.settings_colors_role_idle
import org.umamo.ui.resources.settings_colors_role_offkey
import org.umamo.ui.resources.settings_colors_role_selected
import org.umamo.ui.resources.settings_colors_selection_highlight
import org.umamo.ui.resources.settings_colors_viewport
import org.umamo.ui.resources.settings_interface_language
import org.umamo.ui.resources.settings_interface_theme
import org.umamo.ui.resources.settings_pen_coming_soon
import org.umamo.ui.resources.settings_theme_dark
import org.umamo.ui.resources.settings_theme_light
import org.umamo.ui.resources.settings_theme_system
import org.umamo.ui.resources.settings_viewport_grid_scale
import org.umamo.ui.resources.settings_viewport_grid_subdivisions
import org.umamo.ui.resources.settings_viewport_zoom_step
import org.umamo.ui.resources.settings_viewport_zoom_step_coarse
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.viewport.MeshEditColorSettings
import org.umamo.ui.viewport.ViewportSettings

/** The settings key + values for the UI theme mode, kept in lockstep with org.umamo.ui.theme.Theme. */
private const val THEME_KEY = "interface.theme"
private const val THEME_DEFAULT = "dark"

/** The settings key + value for the UI language, kept in lockstep with the locale used by PersistentEditorShell. */
private const val LOCALE_KEY = "localization.locale"
private const val LOCALE_DEFAULT = "en"

/**
 * The Interface section: theme and language, the two settings already wired end-to-end (writing the key
 * re-themes / re-localizes the running app live), so both auto-save with immediate visible effect.
 *
 * Theme option labels are localized chrome.  Language names are endonyms ("English" / "日本語") shown
 * verbatim regardless of the active UI language - a language's own name is identity, not chrome to
 * translate, the same reasoning that keeps format-level identifiers unlocalized.
 *
 * インターフェース設定。テーマと言語。どちらも書き込み即反映で自動保存。言語名は自言語表記（翻訳しない）。
 */
@Composable
internal fun InterfaceSection() {
	var theme by rememberStringSetting(THEME_KEY, THEME_DEFAULT)
	var locale by rememberStringSetting(LOCALE_KEY, LOCALE_DEFAULT)

	// Resolve option labels in composition (stringResource is @Composable) into ordered maps, so the
	// SelectField label lambda - which is plain (T) -> String - is a lookup, not a composable call. The
	// command palette resolves its labels the same way.
	val themeLabels =
		linkedMapOf(
			"dark" to stringResource(Res.string.settings_theme_dark),
			"light" to stringResource(Res.string.settings_theme_light),
			"system" to stringResource(Res.string.settings_theme_system),
		)
	val languageEndonyms = linkedMapOf("en" to "English", "ja" to "日本語")

	Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_ROW_SPACING)) {
		SettingRow(label = stringResource(Res.string.settings_interface_theme)) {
			SelectField(
				selected = theme,
				options = themeLabels.keys.toList(),
				label = { value -> themeLabels[value] ?: value },
				onSelect = { value -> theme = value },
			)
		}
		SettingRow(label = stringResource(Res.string.settings_interface_language)) {
			SelectField(
				selected = locale,
				options = languageEndonyms.keys.toList(),
				label = { value -> languageEndonyms[value] ?: value },
				onSelect = { value -> locale = value },
			)
		}
	}
}

/**
 * The Colors section: the viewport selection highlight plus the Edit-mode gizmo palette
 * (viewport.meshEdit.*), grouped element × state.  Every row is a [HexColorField] bound write-through
 * to its settings key, so an edit re-colors the viewport live as it is typed - the same auto-save
 * model as the rest of the window.
 *
 * カラー設定。ビューポートの選択ハイライトと編集モードのギズモ配色。各行は設定キーへの双方向バインディングで、
 * 入力中に即時反映される。
 */
@Composable
internal fun ColorsSection() {
	val typography = LocalUmamoTypography.current
	val scrollState = rememberScrollState()
	Box(modifier = Modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(SETTING_ROW_SPACING)) {
			Text(text = stringResource(Res.string.settings_colors_viewport), style = typography.titleSmall)
			ColorSettingRow(
				stringResource(Res.string.settings_colors_selection_highlight),
				ViewportSettings.SELECTION_HIGHLIGHT_KEY,
				ViewportSettings.SELECTION_HIGHLIGHT_DEFAULT,
			)

			Text(text = stringResource(Res.string.settings_colors_mesh_edit), style = typography.titleSmall)

			ColorGroupHeading(stringResource(Res.string.settings_colors_group_vertex))
			ColorSettingRow(stringResource(Res.string.settings_colors_role_idle), MeshEditColorSettings.VERTEX_IDLE_KEY, MeshEditColorSettings.VERTEX_IDLE_DEFAULT)
			ColorSettingRow(stringResource(Res.string.settings_colors_role_selected), MeshEditColorSettings.VERTEX_SELECTED_KEY, MeshEditColorSettings.VERTEX_SELECTED_DEFAULT)
			ColorSettingRow(stringResource(Res.string.settings_colors_role_active), MeshEditColorSettings.VERTEX_ACTIVE_KEY, MeshEditColorSettings.VERTEX_ACTIVE_DEFAULT)
			ColorSettingRow(stringResource(Res.string.settings_colors_role_offkey), MeshEditColorSettings.VERTEX_OFFKEY_KEY, MeshEditColorSettings.VERTEX_OFFKEY_DEFAULT)

			ColorGroupHeading(stringResource(Res.string.settings_colors_group_edge))
			ColorSettingRow(stringResource(Res.string.settings_colors_role_idle), MeshEditColorSettings.EDGE_IDLE_KEY, MeshEditColorSettings.EDGE_IDLE_DEFAULT)
			ColorSettingRow(stringResource(Res.string.settings_colors_role_selected), MeshEditColorSettings.EDGE_SELECTED_KEY, MeshEditColorSettings.EDGE_SELECTED_DEFAULT)
			ColorSettingRow(stringResource(Res.string.settings_colors_role_active), MeshEditColorSettings.EDGE_ACTIVE_KEY, MeshEditColorSettings.EDGE_ACTIVE_DEFAULT)
			ColorSettingRow(stringResource(Res.string.settings_colors_role_offkey), MeshEditColorSettings.EDGE_OFFKEY_KEY, MeshEditColorSettings.EDGE_OFFKEY_DEFAULT)

			ColorGroupHeading(stringResource(Res.string.settings_colors_group_face))
			ColorSettingRow(stringResource(Res.string.settings_colors_role_idle), MeshEditColorSettings.FACE_IDLE_KEY, MeshEditColorSettings.FACE_IDLE_DEFAULT)
			ColorSettingRow(stringResource(Res.string.settings_colors_role_selected), MeshEditColorSettings.FACE_SELECTED_KEY, MeshEditColorSettings.FACE_SELECTED_DEFAULT)
			ColorSettingRow(stringResource(Res.string.settings_colors_role_active), MeshEditColorSettings.FACE_ACTIVE_KEY, MeshEditColorSettings.FACE_ACTIVE_DEFAULT)
			ColorSettingRow(stringResource(Res.string.settings_colors_role_offkey), MeshEditColorSettings.FACE_OFFKEY_KEY, MeshEditColorSettings.FACE_OFFKEY_DEFAULT)
		}
		VerticalScrollbarOverlay(scrollState)
	}
}

/**
 * A muted group heading within the Colors section (Vertex / Edge / Face), spaced to read as a subsection.
 *
 * @param String label The already-localized group label.
 */
@Composable
private fun ColorGroupHeading(label: String) {
	Column {
		Spacer(modifier = Modifier.height(2.dp))
		Text(text = label, style = LocalUmamoTypography.current.labelMedium, color = LocalUmamoColors.current.textMuted)
	}
}

/**
 * One color row: a role label bound to a [HexColorField] editing the given settings key write-through.
 *
 * @param String label The already-localized role label (Idle / Selected / Active / Off-key).
 * @param String key The dotted settings key the row edits.
 * @param String defaultHex The bundled default used when the key is absent.
 */
@Composable
private fun ColorSettingRow(label: String, key: String, defaultHex: String) {
	var hexValue by rememberStringSetting(key, defaultHex)
	SettingRow(label = label) {
		HexColorField(value = hexValue, onValueChange = { newValue -> hexValue = newValue }, modifier = Modifier.width(150.dp))
	}
}

/**
 * The Viewport section: pointer-interaction tuning.  The zoom steps are the percent change per wheel
 * notch (fine) and per Shift-wheel notch (coarse); the grid scale is the default major-line spacing in
 * world units for files that do not store their own (CMO3).  The viewport binding reads all three keys
 * live, so a committed edit re-tunes wheel zoom / re-draws the grid immediately.  Each field commits
 * clamped to its [ViewportSettings] range (the [NumberField] contract).
 *
 * ビューポート設定。ホイール 1 ノッチあたりのズーム率（通常／Shift の粗い刻み）とグリッドの既定間隔。即時反映。
 */
@Composable
internal fun ViewportSection() {
	var zoomStep by rememberDoubleSetting(ViewportSettings.ZOOM_STEP_KEY, ViewportSettings.ZOOM_STEP_DEFAULT)
	var zoomStepCoarse by rememberDoubleSetting(ViewportSettings.ZOOM_STEP_COARSE_KEY, ViewportSettings.ZOOM_STEP_COARSE_DEFAULT)
	var gridScale by rememberDoubleSetting(ViewportSettings.GRID_SCALE_KEY, ViewportSettings.GRID_SCALE_DEFAULT)
	var gridSubdivisions by rememberIntSetting(ViewportSettings.GRID_SUBDIVISIONS_KEY, ViewportSettings.GRID_SUBDIVISIONS_DEFAULT)

	Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SETTING_ROW_SPACING)) {
		SettingRow(label = stringResource(Res.string.settings_viewport_zoom_step)) {
			NumberField(
				value = zoomStep.toFloat(),
				onValueChange = { committed -> zoomStep = committed.toDouble() },
				range = ViewportSettings.ZOOM_STEP_RANGE,
				modifier = Modifier.width(80.dp),
			)
		}
		SettingRow(label = stringResource(Res.string.settings_viewport_zoom_step_coarse)) {
			NumberField(
				value = zoomStepCoarse.toFloat(),
				onValueChange = { committed -> zoomStepCoarse = committed.toDouble() },
				range = ViewportSettings.ZOOM_STEP_RANGE,
				modifier = Modifier.width(80.dp),
			)
		}
		SettingRow(label = stringResource(Res.string.settings_viewport_grid_scale)) {
			NumberField(
				value = gridScale.toFloat(),
				onValueChange = { committed -> gridScale = committed.toDouble() },
				range = ViewportSettings.GRID_SCALE_RANGE,
				modifier = Modifier.width(80.dp),
			)
		}
		SettingRow(label = stringResource(Res.string.settings_viewport_grid_subdivisions)) {
			NumberField(
				value = gridSubdivisions,
				onValueChange = { committed -> gridSubdivisions = committed },
				range = ViewportSettings.GRID_SUBDIVISIONS_RANGE,
				modifier = Modifier.width(80.dp),
			)
		}
	}
}

/**
 * The Keybindings section: preset selection plus the per-command rebinding editor (see [KeybindingsEditor]),
 * all auto-saving to settings input.keybinding and re-resolving the live keymap.
 *
 * キーバインド設定。プリセット選択と個別コマンドの再割り当て編集（即時保存・即時反映）。
 */
@Composable
internal fun KeybindingsSection() {
	KeybindingsEditor()
}

/**
 * The Pen section: a placeholder until the pen backend (JPen / Wacom) and the radial menu land.
 *
 * ペン設定。ペンバックエンド実装までのプレースホルダ。
 */
@Composable
internal fun PenSection() {
	ComingSoon(stringResource(Res.string.settings_pen_coming_soon))
}

/**
 * A muted single-line placeholder for a section whose controls are not built yet.
 *
 * 未実装セクションの淡色の一行プレースホルダ。
 *
 * @param String message The already-localized placeholder line.
 */
@Composable
private fun ComingSoon(message: String) {
	Text(text = message, style = LocalUmamoTypography.current.bodyMedium, color = LocalUmamoColors.current.textMuted)
}
