package org.umamo.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.seed.ParameterTemplate
import org.umamo.interop.art.ArtworkAnchor
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.reimport.WatchMode
import org.umamo.ui.app.CHECK_FOR_UPDATES_KEY
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.field.FieldStack
import org.umamo.ui.kit.field.HexColorField
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyCheckboxRow
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.kit.field.SelectField
import org.umamo.ui.l10n.FALLBACK_LOCALE_TAG
import org.umamo.ui.l10n.LOCALE_SETTINGS_KEY
import org.umamo.ui.l10n.UI_LANGUAGE_ENDONYMS
import org.umamo.ui.rememberBooleanSetting
import org.umamo.ui.rememberDoubleSetting
import org.umamo.ui.rememberIntSetting
import org.umamo.ui.rememberStringSetting
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.mesh_preset_coarse
import org.umamo.ui.resources.mesh_preset_fine
import org.umamo.ui.resources.mesh_preset_standard
import org.umamo.ui.resources.settings_check_for_updates_at_startup
import org.umamo.ui.resources.settings_check_for_updates_at_startup_description
import org.umamo.ui.resources.settings_check_for_updates_heading
import org.umamo.ui.resources.settings_colors_active_selection_highlight
import org.umamo.ui.resources.settings_colors_active_selection_highlight_description
import org.umamo.ui.resources.settings_colors_lattice_back_facing_opacity
import org.umamo.ui.resources.settings_colors_lattice_back_facing_opacity_description
import org.umamo.ui.resources.settings_colors_pinned_placement
import org.umamo.ui.resources.settings_colors_pinned_placement_description
import org.umamo.ui.resources.settings_colors_role_active
import org.umamo.ui.resources.settings_colors_role_active_description
import org.umamo.ui.resources.settings_colors_role_hovered
import org.umamo.ui.resources.settings_colors_role_hovered_description
import org.umamo.ui.resources.settings_colors_role_idle
import org.umamo.ui.resources.settings_colors_role_idle_description
import org.umamo.ui.resources.settings_colors_role_selected
import org.umamo.ui.resources.settings_colors_role_selected_description
import org.umamo.ui.resources.settings_colors_role_unkeyed
import org.umamo.ui.resources.settings_colors_role_unkeyed_description
import org.umamo.ui.resources.settings_colors_selection_highlight
import org.umamo.ui.resources.settings_colors_selection_highlight_description
import org.umamo.ui.resources.settings_colors_warning
import org.umamo.ui.resources.settings_colors_warning_description
import org.umamo.ui.resources.settings_import_alignment
import org.umamo.ui.resources.settings_import_alignment_description
import org.umamo.ui.resources.settings_import_also_ignore_layer
import org.umamo.ui.resources.settings_import_also_ignore_layer_description
import org.umamo.ui.resources.settings_import_birth_mesh
import org.umamo.ui.resources.settings_import_birth_mesh_automatic
import org.umamo.ui.resources.settings_import_birth_mesh_description
import org.umamo.ui.resources.settings_import_birth_mesh_quad
import org.umamo.ui.resources.settings_import_deleting_art
import org.umamo.ui.resources.settings_import_from_world_axes
import org.umamo.ui.resources.settings_import_from_world_axes_description
import org.umamo.ui.resources.settings_import_layer_positions
import org.umamo.ui.resources.settings_import_mesh_preset
import org.umamo.ui.resources.settings_import_mesh_preset_description
import org.umamo.ui.resources.settings_import_parameter_template
import org.umamo.ui.resources.settings_import_parameter_template_description
import org.umamo.ui.resources.settings_import_parameter_template_humanoid
import org.umamo.ui.resources.settings_import_parameter_template_none
import org.umamo.ui.resources.settings_import_watch_mode
import org.umamo.ui.resources.settings_import_watch_mode_auto
import org.umamo.ui.resources.settings_import_watch_mode_description
import org.umamo.ui.resources.settings_import_watch_mode_notify
import org.umamo.ui.resources.settings_import_watch_mode_off
import org.umamo.ui.resources.settings_interface_history_steps
import org.umamo.ui.resources.settings_interface_history_steps_description
import org.umamo.ui.resources.settings_interface_language
import org.umamo.ui.resources.settings_interface_language_description
import org.umamo.ui.resources.settings_interface_repeat_control
import org.umamo.ui.resources.settings_interface_repeat_control_description
import org.umamo.ui.resources.settings_interface_theme
import org.umamo.ui.resources.settings_interface_theme_description
import org.umamo.ui.resources.settings_pen_coming_soon
import org.umamo.ui.resources.settings_repeat_control_dial
import org.umamo.ui.resources.settings_repeat_control_slider
import org.umamo.ui.resources.settings_theme_dark
import org.umamo.ui.resources.settings_theme_light
import org.umamo.ui.resources.settings_theme_system
import org.umamo.ui.resources.settings_viewport_default_sidebar
import org.umamo.ui.resources.settings_viewport_default_sidebar_description
import org.umamo.ui.resources.settings_viewport_default_toolbar
import org.umamo.ui.resources.settings_viewport_default_toolbar_description
import org.umamo.ui.resources.settings_viewport_grid_scale
import org.umamo.ui.resources.settings_viewport_grid_scale_description
import org.umamo.ui.resources.settings_viewport_grid_subdivisions
import org.umamo.ui.resources.settings_viewport_grid_subdivisions_description
import org.umamo.ui.resources.settings_viewport_show_by_default
import org.umamo.ui.resources.settings_viewport_supersample_heading
import org.umamo.ui.resources.settings_viewport_supersample_keep_while_resizing
import org.umamo.ui.resources.settings_viewport_supersample_keep_while_resizing_description
import org.umamo.ui.resources.settings_viewport_supersample_quality
import org.umamo.ui.resources.settings_viewport_supersample_quality_description
import org.umamo.ui.resources.settings_viewport_uv_grid_scale
import org.umamo.ui.resources.settings_viewport_uv_grid_scale_description
import org.umamo.ui.resources.settings_viewport_uv_grid_subdivisions
import org.umamo.ui.resources.settings_viewport_uv_grid_subdivisions_description
import org.umamo.ui.resources.settings_viewport_zoom_step
import org.umamo.ui.resources.settings_viewport_zoom_step_coarse
import org.umamo.ui.resources.settings_viewport_zoom_step_coarse_description
import org.umamo.ui.resources.settings_viewport_zoom_step_description
import org.umamo.ui.resources.unit_percent
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.viewport.ViewportColorSettings
import org.umamo.ui.viewport.ViewportSettings
import org.umamo.ui.workspace.operationstrip.artworkAnchorLabel
import kotlin.math.roundToInt

/** The settings key + values for the UI theme mode, kept in lockstep with org.umamo.ui.theme.Theme. */
private const val THEME_KEY = "interface.theme"
private const val THEME_DEFAULT = "dark"

/**
 * The Interface page: the theme and language, the editing defaults, and the update check.  Each is wired
 * end-to-end - writing the key re-themes or re-localizes the running app, re-caps the open document's undo stack,
 * or swaps the Parameters panel's controls - so each auto-saves with immediate visible effect.
 *
 * @param PreferencesFolds folds The window's fold state.
 */
@Composable
internal fun InterfaceSection(folds: PreferencesFolds) {
	var historyLimit by rememberIntSetting(HistorySettings.HISTORY_LIMIT_KEY, HistorySettings.HISTORY_LIMIT_DEFAULT)
	var repeatControlKey by rememberStringSetting(ParametersSettings.REPEAT_CONTROL_KEY, ParametersSettings.REPEAT_CONTROL_DEFAULT.key)
	val repeatControlLabels =
		linkedMapOf(
			RepeatControlSetting.Dial.key to stringResource(Res.string.settings_repeat_control_dial),
			RepeatControlSetting.Slider.key to stringResource(Res.string.settings_repeat_control_slider),
		)

	SettingsCard(folds, PreferencesSection.InterfaceDisplay) {
		ThemeSettingRow()
		LanguageSettingRow()
	}
	SettingsCard(folds, PreferencesSection.InterfaceEditing) {
		// Lowering this trims the open document's stack on commit, but never past the live step - the
		// current state and anything left to redo always survive (see EditorSession.historyLimit).
		PropertyFieldRow(
			label = stringResource(Res.string.settings_interface_history_steps),
			description = stringResource(Res.string.settings_interface_history_steps_description),
		) {
			NumberField(
				value = historyLimit,
				onValueChange = { committed -> historyLimit = committed },
				range = HistorySettings.HISTORY_LIMIT_RANGE,
				modifier = Modifier.fillMaxWidth(),
			)
		}
		// A pair with a repeating member follows the same choice: the slider choice gives it its pad.
		PropertyFieldRow(
			label = stringResource(Res.string.settings_interface_repeat_control),
			description = stringResource(Res.string.settings_interface_repeat_control_description),
		) {
			SelectField(
				selected = RepeatControlSetting.fromKey(repeatControlKey).key,
				options = repeatControlLabels.keys.toList(),
				label = { value -> repeatControlLabels[value] ?: value },
				onSelect = { value -> repeatControlKey = value },
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}

	// The card's only row is left out where the host cannot check, and a card with no rows is no card at all.
	if (LocalUpdateChecks.current) {
		SettingsCard(folds, PreferencesSection.InterfaceUpdates) {
			UpdateCheckSettingRow()
		}
	}
}

/**
 * Whether the host can check for updates: the app provides true where it passed an update transport, so the
 * setting's row shows only where the setting does something.
 */
internal val LocalUpdateChecks = staticCompositionLocalOf { false }

/**
 * The check-for-updates row, bound write-through to app.checkForUpdates: Preferences and Quick Setup share it.  It is
 * checked unless the rigger turned the check off, and left out where the host cannot check ([LocalUpdateChecks]).
 */
@Composable
internal fun UpdateCheckSettingRow() {
	if (!LocalUpdateChecks.current) {
		return
	}

	var checkForUpdates by rememberBooleanSetting(CHECK_FOR_UPDATES_KEY, true)
	PropertyCheckboxRow(
		checked = checkForUpdates,
		onCheckedChange = { checked -> checkForUpdates = checked },
		label = stringResource(Res.string.settings_check_for_updates_at_startup),
		description = stringResource(Res.string.settings_check_for_updates_at_startup_description),
		heading = stringResource(Res.string.settings_check_for_updates_heading),
	)
}

/**
 * The UI theme row, bound write-through to interface.theme: Preferences and Quick Setup share it, so both
 * offer the same modes under the same labels.  The option labels are localized chrome.
 */
@Composable
internal fun ThemeSettingRow() {
	var theme by rememberStringSetting(THEME_KEY, THEME_DEFAULT)
	// Resolve option labels in composition (stringResource is @Composable) into ordered maps, so the
	// SelectField label lambda - which is plain (T) -> String - is a lookup, not a composable call.  The
	// command palette resolves its labels the same way.
	val themeLabels =
		linkedMapOf(
			"dark" to stringResource(Res.string.settings_theme_dark),
			"light" to stringResource(Res.string.settings_theme_light),
			"system" to stringResource(Res.string.settings_theme_system),
		)
	PropertyFieldRow(
		label = stringResource(Res.string.settings_interface_theme),
		description = stringResource(Res.string.settings_interface_theme_description),
	) {
		SelectField(
			selected = theme,
			options = themeLabels.keys.toList(),
			label = { value -> themeLabels[value] ?: value },
			onSelect = { value -> theme = value },
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/**
 * The UI language row, bound write-through to localization.locale: Preferences and Quick Setup share it.
 * The row leads with the globe so a rigger who cannot read the current language can still find it, and the
 * options are endonyms (see [UI_LANGUAGE_ENDONYMS]) for the same reason.
 */
@Composable
internal fun LanguageSettingRow() {
	var locale by rememberStringSetting(LOCALE_SETTINGS_KEY, FALLBACK_LOCALE_TAG)
	PropertyFieldRow(
		label = stringResource(Res.string.settings_interface_language),
		description = stringResource(Res.string.settings_interface_language_description),
		icon = LocalUmamoIcons.language,
	) {
		SelectField(
			selected = locale,
			options = UI_LANGUAGE_ENDONYMS.keys.toList(),
			label = { value -> UI_LANGUAGE_ENDONYMS[value] ?: value },
			onSelect = { value -> locale = value },
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/** The settings key for the parameter set an artwork import seeds; the values are ParameterTemplate keys. */
internal const val IMPORT_PARAMETER_TEMPLATE_KEY = "import.parameterTemplate"

/** The settings key for where a later artwork file is anchored on the rig's canvas; the values are ArtworkAnchor keys. */
internal const val IMPORT_ALIGNMENT_KEY = "import.alignment"

/** The settings key for what a document does when a watched artwork file changes; the values are WatchMode keys. */
internal const val IMPORT_WATCH_MODE_KEY = "import.watchMode"

/** The settings key for whether Delete Art also marks the tile's layer ignored, so a reload does not mint the art back. */
internal const val IMPORT_DELETE_ART_IGNORES_LAYER_KEY = "import.deleteArtIgnoresLayer"

/** The settings key for whether the Sources layer rows measure positions from the world axes instead of the art file's top-left corner. */
internal const val IMPORT_LAYER_POSITIONS_FROM_WORLD_AXES_KEY = "import.layerPositionsFromWorldAxes"

/** The settings key for the mesh an artwork import gives each new drawable; the values are BirthMeshSetting keys. */
internal const val IMPORT_BIRTH_MESH_KEY = "import.birthMesh"

/**
 * The settings key for the preset meshes are generated at: an import's generated meshes, and the first
 * Generate Mesh from Art run of a session.  The values are ArtMeshPreset keys.
 */
internal const val MESH_GENERATION_PRESET_KEY = "meshGeneration.preset"

/**
 * The Import page: what an artwork import seeds a new model with, the meshes it gives new drawables, how later
 * artwork files are placed and watched, and the Sources area's toggles.  The enumerated choices are stored as their
 * keys, so a later entry is one more option here and one more enum entry, nothing else.  Every choice is read when
 * its consumer runs, so a change applies to the next use.
 *
 * @param PreferencesFolds folds The window's fold state.
 */
@Composable
internal fun ImportSection(folds: PreferencesFolds) {
	var templateKey by rememberStringSetting(IMPORT_PARAMETER_TEMPLATE_KEY, ParameterTemplate.Default.key)
	val templateLabels =
		linkedMapOf(
			ParameterTemplate.Humanoid.key to stringResource(Res.string.settings_import_parameter_template_humanoid),
			ParameterTemplate.None.key to stringResource(Res.string.settings_import_parameter_template_none),
		)

	var birthMeshKey by rememberStringSetting(IMPORT_BIRTH_MESH_KEY, BirthMeshSetting.Default.key)
	val birthMeshLabels =
		linkedMapOf(
			BirthMeshSetting.Automatic.key to stringResource(Res.string.settings_import_birth_mesh_automatic),
			BirthMeshSetting.Quad.key to stringResource(Res.string.settings_import_birth_mesh_quad),
		)

	var meshPresetKey by rememberStringSetting(MESH_GENERATION_PRESET_KEY, ArtMeshPreset.Default.key)
	val meshPresetLabels =
		linkedMapOf(
			ArtMeshPreset.Standard.key to stringResource(Res.string.mesh_preset_standard),
			ArtMeshPreset.Fine.key to stringResource(Res.string.mesh_preset_fine),
			ArtMeshPreset.Coarse.key to stringResource(Res.string.mesh_preset_coarse),
		)

	var alignmentKey by rememberStringSetting(IMPORT_ALIGNMENT_KEY, ArtworkAnchor.Default.key)
	val anchorLabels = ArtworkAnchor.entries.associate { anchor -> anchor.key to artworkAnchorLabel(anchor) }

	var deleteArtIgnoresLayer by rememberBooleanSetting(IMPORT_DELETE_ART_IGNORES_LAYER_KEY, false)

	var layerPositionsFromWorldAxes by rememberBooleanSetting(IMPORT_LAYER_POSITIONS_FROM_WORLD_AXES_KEY, false)

	var watchModeKey by rememberStringSetting(IMPORT_WATCH_MODE_KEY, WatchMode.Default.key)
	val watchModeLabels =
		linkedMapOf(
			WatchMode.Auto.key to stringResource(Res.string.settings_import_watch_mode_auto),
			WatchMode.Notify.key to stringResource(Res.string.settings_import_watch_mode_notify),
			WatchMode.Off.key to stringResource(Res.string.settings_import_watch_mode_off),
		)

	SettingsCard(folds, PreferencesSection.ImportNewModels) {
		KeyedSelectRow(
			label = Res.string.settings_import_parameter_template,
			description = Res.string.settings_import_parameter_template_description,
			selectedKey = ParameterTemplate.fromKey(templateKey).key,
			labels = templateLabels,
			onSelect = { value -> templateKey = value },
		)
	}
	SettingsCard(folds, PreferencesSection.ImportMeshes) {
		KeyedSelectRow(
			label = Res.string.settings_import_birth_mesh,
			description = Res.string.settings_import_birth_mesh_description,
			selectedKey = BirthMeshSetting.fromKey(birthMeshKey).key,
			labels = birthMeshLabels,
			onSelect = { value -> birthMeshKey = value },
		)
		KeyedSelectRow(
			label = Res.string.settings_import_mesh_preset,
			description = Res.string.settings_import_mesh_preset_description,
			selectedKey = ArtMeshPreset.fromKey(meshPresetKey).key,
			labels = meshPresetLabels,
			onSelect = { value -> meshPresetKey = value },
		)
	}
	SettingsCard(folds, PreferencesSection.ImportArtworkFiles) {
		// The default of the import's Align row: the art programs' own canvas-resize anchors, Center
		// being what both of them resize around unless told otherwise.
		KeyedSelectRow(
			label = Res.string.settings_import_alignment,
			description = Res.string.settings_import_alignment_description,
			selectedKey = ArtworkAnchor.fromKey(alignmentKey).key,
			labels = anchorLabels,
			onSelect = { value -> alignmentKey = value },
		)
		// Read live by the open document's watcher, so the switch applies at once.
		KeyedSelectRow(
			label = Res.string.settings_import_watch_mode,
			description = Res.string.settings_import_watch_mode_description,
			selectedKey = WatchMode.fromKey(watchModeKey).key,
			labels = watchModeLabels,
			onSelect = { value -> watchModeKey = value },
		)
	}
	SettingsCard(folds, PreferencesSection.ImportSources) {
		// Off by default: a deleted drawable's layer comes back on the next reload unless the rigger asks
		// otherwise here, or ignores the row by hand.
		PropertyCheckboxRow(
			checked = deleteArtIgnoresLayer,
			onCheckedChange = { checked -> deleteArtIgnoresLayer = checked },
			label = stringResource(Res.string.settings_import_also_ignore_layer),
			description = stringResource(Res.string.settings_import_also_ignore_layer_description),
			heading = stringResource(Res.string.settings_import_deleting_art),
		)
		// Off by default: a layer row reads its position in the art file's own frame, the numbers the art
		// program shows.  On, it reads from the world axes like every other position in the app.
		PropertyCheckboxRow(
			checked = layerPositionsFromWorldAxes,
			onCheckedChange = { checked -> layerPositionsFromWorldAxes = checked },
			label = stringResource(Res.string.settings_import_from_world_axes),
			description = stringResource(Res.string.settings_import_from_world_axes_description),
			heading = stringResource(Res.string.settings_import_layer_positions),
		)
	}
}

/**
 * One dropdown row over a setting stored as an enum key.
 *
 * @param StringResource label The row label.
 * @param StringResource description What the setting does, the label's tooltip.
 * @param String selectedKey The stored key, already resolved to a known one.
 * @param Map labels Every option's key mapped to its localized label, in display order.
 * @param Function onSelect Called with the picked option's key.
 */
@Composable
private fun KeyedSelectRow(
	label: StringResource,
	description: StringResource,
	selectedKey: String,
	labels: Map<String, String>,
	onSelect: (String) -> Unit,
) {
	PropertyFieldRow(label = stringResource(label), description = stringResource(description)) {
		SelectField(
			selected = selectedKey,
			options = labels.keys.toList(),
			label = { value -> labels[value] ?: value },
			onSelect = onSelect,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/**
 * The state a color row names, its label and tooltip.  The roles are shared by the mesh element groups and the
 * warp lattice, so each description reads true for every group the role appears in.
 *
 * @property StringResource label The row label.
 * @property StringResource description The row's tooltip.
 */
private enum class ColorRole(val label: StringResource, val description: StringResource) {
	Idle(Res.string.settings_colors_role_idle, Res.string.settings_colors_role_idle_description),
	Selected(Res.string.settings_colors_role_selected, Res.string.settings_colors_role_selected_description),
	Active(Res.string.settings_colors_role_active, Res.string.settings_colors_role_active_description),
	Unkeyed(Res.string.settings_colors_role_unkeyed, Res.string.settings_colors_role_unkeyed_description),
	Hovered(Res.string.settings_colors_role_hovered, Res.string.settings_colors_role_hovered_description),
}

/**
 * The Colors page: the viewport selection highlights and overlay colors (the collision warning, the pinned-placement
 * highlight), the Edit-mode gizmo palette (viewport.meshEdit.*) as one sub-panel per element, and the warp lattice by
 * role (viewport.deformer.*).  Every row is a [HexColorField] bound write-through to its settings key, so an edit
 * re-colors the viewport live as it is typed.
 *
 * @param PreferencesFolds folds The window's fold state.
 */
@Composable
internal fun ColorsSection(folds: PreferencesFolds) {
	SettingsCard(folds, PreferencesSection.ColorsViewport) {
		ColorSettingRow(
			Res.string.settings_colors_selection_highlight,
			Res.string.settings_colors_selection_highlight_description,
			ViewportColorSettings.SELECTION_HIGHLIGHT_KEY,
			ViewportColorSettings.SELECTION_HIGHLIGHT_DEFAULT,
		)
		ColorSettingRow(
			Res.string.settings_colors_active_selection_highlight,
			Res.string.settings_colors_active_selection_highlight_description,
			ViewportColorSettings.ACTIVE_SELECTION_HIGHLIGHT_KEY,
			ViewportColorSettings.ACTIVE_SELECTION_HIGHLIGHT_DEFAULT,
		)
		ColorSettingRow(
			Res.string.settings_colors_warning,
			Res.string.settings_colors_warning_description,
			ViewportColorSettings.WARNING_COLOR_KEY,
			ViewportColorSettings.WARNING_COLOR_DEFAULT,
		)
		ColorSettingRow(
			Res.string.settings_colors_pinned_placement,
			Res.string.settings_colors_pinned_placement_description,
			ViewportColorSettings.PINNED_PLACEMENT_COLOR_KEY,
			ViewportColorSettings.PINNED_PLACEMENT_COLOR_DEFAULT,
		)
	}
	SettingsCard(folds, PreferencesSection.ColorsMeshEditing) {
		SettingsSubsection(folds, PreferencesSection.ColorsMeshEditingVertex) {
			ColorRoleRow(ColorRole.Idle, ViewportColorSettings.VERTEX_IDLE_KEY, ViewportColorSettings.VERTEX_IDLE_DEFAULT)
			ColorRoleRow(ColorRole.Selected, ViewportColorSettings.VERTEX_SELECTED_KEY, ViewportColorSettings.VERTEX_SELECTED_DEFAULT)
			ColorRoleRow(ColorRole.Active, ViewportColorSettings.VERTEX_ACTIVE_KEY, ViewportColorSettings.VERTEX_ACTIVE_DEFAULT)
		}
		SettingsSubsection(folds, PreferencesSection.ColorsMeshEditingEdge) {
			ColorRoleRow(ColorRole.Idle, ViewportColorSettings.EDGE_IDLE_KEY, ViewportColorSettings.EDGE_IDLE_DEFAULT)
			ColorRoleRow(ColorRole.Selected, ViewportColorSettings.EDGE_SELECTED_KEY, ViewportColorSettings.EDGE_SELECTED_DEFAULT)
			ColorRoleRow(ColorRole.Active, ViewportColorSettings.EDGE_ACTIVE_KEY, ViewportColorSettings.EDGE_ACTIVE_DEFAULT)
		}
		SettingsSubsection(folds, PreferencesSection.ColorsMeshEditingFace) {
			ColorRoleRow(ColorRole.Idle, ViewportColorSettings.FACE_IDLE_KEY, ViewportColorSettings.FACE_IDLE_DEFAULT)
			ColorRoleRow(ColorRole.Selected, ViewportColorSettings.FACE_SELECTED_KEY, ViewportColorSettings.FACE_SELECTED_DEFAULT)
			ColorRoleRow(ColorRole.Active, ViewportColorSettings.FACE_ACTIVE_KEY, ViewportColorSettings.FACE_ACTIVE_DEFAULT)
		}
	}
	SettingsCard(folds, PreferencesSection.ColorsDeformers) {
		ColorRoleRow(ColorRole.Idle, ViewportColorSettings.LATTICE_IDLE_KEY, ViewportColorSettings.LATTICE_IDLE_DEFAULT)
		ColorRoleRow(ColorRole.Selected, ViewportColorSettings.LATTICE_SELECTED_KEY, ViewportColorSettings.LATTICE_SELECTED_DEFAULT)
		ColorRoleRow(ColorRole.Active, ViewportColorSettings.LATTICE_ACTIVE_KEY, ViewportColorSettings.LATTICE_ACTIVE_DEFAULT)
		ColorRoleRow(ColorRole.Unkeyed, ViewportColorSettings.LATTICE_PENDING_KEY, ViewportColorSettings.LATTICE_PENDING_DEFAULT)
		ColorRoleRow(ColorRole.Hovered, ViewportColorSettings.LATTICE_HOVER_KEY, ViewportColorSettings.LATTICE_HOVER_DEFAULT)
		BackFacingOpacityRow()
	}
}

/**
 * The lattice cage's back-facing opacity as a whole percent, 0 to 100, stored as 0 to 1.
 */
@Composable
private fun BackFacingOpacityRow() {
	var opacity by rememberDoubleSetting(ViewportColorSettings.LATTICE_BACK_FACING_OPACITY_KEY, ViewportColorSettings.LATTICE_BACK_FACING_OPACITY_DEFAULT)
	val shownPercent = (opacity * 100.0).roundToInt()
	PropertyFieldRow(
		label = stringResource(Res.string.settings_colors_lattice_back_facing_opacity),
		description = stringResource(Res.string.settings_colors_lattice_back_facing_opacity_description),
	) {
		NumberField(
			value = shownPercent,
			onValueChange = { percent ->
				if (percent != shownPercent) {
					opacity = percent / 100.0
				}
			},
			range = BACK_FACING_PERCENT_RANGE,
			unitSuffix = stringResource(Res.string.unit_percent),
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/** The percents the back-facing opacity row accepts. */
private val BACK_FACING_PERCENT_RANGE = 0..100

/**
 * One color row for a [ColorRole] of an element group.
 *
 * @param ColorRole role The state the row colors.
 * @param String key The dotted settings key the row edits.
 * @param String defaultHex The bundled default used when the key is absent.
 */
@Composable
private fun ColorRoleRow(role: ColorRole, key: String, defaultHex: String) {
	ColorSettingRow(role.label, role.description, key, defaultHex)
}

/**
 * One color row: a label bound to a [HexColorField] editing the given settings key write-through.
 *
 * @param StringResource label The row label.
 * @param StringResource description What the color paints, the label's tooltip.
 * @param String key The dotted settings key the row edits.
 * @param String defaultHex The bundled default used when the key is absent.
 */
@Composable
private fun ColorSettingRow(label: StringResource, description: StringResource, key: String, defaultHex: String) {
	var hexValue by rememberStringSetting(key, defaultHex)
	PropertyFieldRow(label = stringResource(label), description = stringResource(description)) {
		HexColorField(value = hexValue, onValueChange = { newValue -> hexValue = newValue }, modifier = Modifier.fillMaxWidth())
	}
}

/**
 * The Viewport page: wheel-zoom tuning, the application's grids, the rendering quality toggles, and the toolbar and
 * sidebar defaults.  The UV grid pair is the UV editor's own, in texels, so a page's cells land on texel boundaries
 * independent of the world grid; an area given its own grid in its overlays popover keeps it.  The viewport binding
 * and each area's render-options publisher read every key live, so a committed edit takes effect immediately.
 *
 * @param PreferencesFolds folds The window's fold state.
 */
@Composable
internal fun ViewportSection(folds: PreferencesFolds) {
	var zoomStep by rememberDoubleSetting(ViewportSettings.ZOOM_STEP_KEY, ViewportSettings.ZOOM_STEP_DEFAULT)
	var zoomStepCoarse by rememberDoubleSetting(ViewportSettings.ZOOM_STEP_COARSE_KEY, ViewportSettings.ZOOM_STEP_COARSE_DEFAULT)
	var gridScale by rememberDoubleSetting(ViewportSettings.GRID_SCALE_KEY, ViewportSettings.GRID_SCALE_DEFAULT)
	var gridSubdivisions by rememberIntSetting(ViewportSettings.GRID_SUBDIVISIONS_KEY, ViewportSettings.GRID_SUBDIVISIONS_DEFAULT)
	var uvGridScale by rememberDoubleSetting(ViewportSettings.UV_GRID_SCALE_KEY, ViewportSettings.UV_GRID_SCALE_DEFAULT)
	var uvGridSubdivisions by rememberIntSetting(ViewportSettings.UV_GRID_SUBDIVISIONS_KEY, ViewportSettings.UV_GRID_SUBDIVISIONS_DEFAULT)
	var supersample by rememberBooleanSetting(ViewportSettings.SUPERSAMPLE_KEY, ViewportSettings.SUPERSAMPLE_DEFAULT)
	var supersampleWhileResizing by
		rememberBooleanSetting(ViewportSettings.SUPERSAMPLE_WHILE_RESIZING_KEY, ViewportSettings.SUPERSAMPLE_WHILE_RESIZING_DEFAULT)
	var showToolbar by rememberBooleanSetting(ViewportSettings.SHOW_TOOLBAR_KEY, ViewportSettings.SHOW_TOOLBAR_DEFAULT)
	var showSidebar by rememberBooleanSetting(ViewportSettings.SHOW_SIDEBAR_KEY, ViewportSettings.SHOW_SIDEBAR_DEFAULT)
	val percent = stringResource(Res.string.unit_percent)

	SettingsCard(folds, PreferencesSection.ViewportNavigation) {
		PropertyFieldRow(
			label = stringResource(Res.string.settings_viewport_zoom_step),
			description = stringResource(Res.string.settings_viewport_zoom_step_description),
		) {
			NumberField(
				value = zoomStep.toFloat(),
				onValueChange = { committed -> zoomStep = committed.toDouble() },
				range = ViewportSettings.ZOOM_STEP_RANGE,
				unitSuffix = percent,
				modifier = Modifier.fillMaxWidth(),
			)
		}
		PropertyFieldRow(
			label = stringResource(Res.string.settings_viewport_zoom_step_coarse),
			description = stringResource(Res.string.settings_viewport_zoom_step_coarse_description),
		) {
			NumberField(
				value = zoomStepCoarse.toFloat(),
				onValueChange = { committed -> zoomStepCoarse = committed.toDouble() },
				range = ViewportSettings.ZOOM_STEP_RANGE,
				unitSuffix = percent,
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
	SettingsCard(folds, PreferencesSection.ViewportGrids) {
		GridSettingStack(
			scaleLabel = Res.string.settings_viewport_grid_scale,
			scaleDescription = Res.string.settings_viewport_grid_scale_description,
			scale = gridScale,
			onScaleChange = { committed -> gridScale = committed },
			subdivisionsLabel = Res.string.settings_viewport_grid_subdivisions,
			subdivisionsDescription = Res.string.settings_viewport_grid_subdivisions_description,
			subdivisions = gridSubdivisions,
			onSubdivisionsChange = { committed -> gridSubdivisions = committed },
		)
		GridSettingStack(
			scaleLabel = Res.string.settings_viewport_uv_grid_scale,
			scaleDescription = Res.string.settings_viewport_uv_grid_scale_description,
			scale = uvGridScale,
			onScaleChange = { committed -> uvGridScale = committed },
			subdivisionsLabel = Res.string.settings_viewport_uv_grid_subdivisions,
			subdivisionsDescription = Res.string.settings_viewport_uv_grid_subdivisions_description,
			subdivisions = uvGridSubdivisions,
			onSubdivisionsChange = { committed -> uvGridSubdivisions = committed },
		)
	}
	SettingsCard(folds, PreferencesSection.ViewportQuality) {
		PropertyCheckboxRow(
			checked = supersample,
			onCheckedChange = { checked -> supersample = checked },
			label = stringResource(Res.string.settings_viewport_supersample_quality),
			description = stringResource(Res.string.settings_viewport_supersample_quality_description),
			heading = stringResource(Res.string.settings_viewport_supersample_heading),
		)
		// Inert with supersampling off: everything already renders at 1x, so there is no supersample
		// to keep while resizing (the engine computes the same 1x either way).  The stored value is
		// kept, so re-enabling supersampling restores the previous choice.
		PropertyCheckboxRow(
			checked = supersampleWhileResizing,
			onCheckedChange = { checked -> supersampleWhileResizing = checked },
			label = stringResource(Res.string.settings_viewport_supersample_keep_while_resizing),
			description = stringResource(Res.string.settings_viewport_supersample_keep_while_resizing_description),
			enabled = supersample,
		)
	}
	SettingsCard(folds, PreferencesSection.ViewportDisplay) {
		PropertyCheckboxRow(
			checked = showToolbar,
			onCheckedChange = { checked -> showToolbar = checked },
			label = stringResource(Res.string.settings_viewport_default_toolbar),
			description = stringResource(Res.string.settings_viewport_default_toolbar_description),
			heading = stringResource(Res.string.settings_viewport_show_by_default),
		)
		PropertyCheckboxRow(
			checked = showSidebar,
			onCheckedChange = { checked -> showSidebar = checked },
			label = stringResource(Res.string.settings_viewport_default_sidebar),
			description = stringResource(Res.string.settings_viewport_default_sidebar_description),
		)
	}
}

/**
 * One grid's scale and subdivisions, butted into a two-field stack as a size pair is in the Properties panel.
 *
 * @param StringResource scaleLabel The scale row's label.
 * @param StringResource scaleDescription The scale row's tooltip.
 * @param Double scale The stored scale.
 * @param Function onScaleChange Called with a committed scale.
 * @param StringResource subdivisionsLabel The subdivisions row's label.
 * @param StringResource subdivisionsDescription The subdivisions row's tooltip.
 * @param Int subdivisions The stored subdivision count.
 * @param Function onSubdivisionsChange Called with a committed subdivision count.
 */
@Composable
private fun GridSettingStack(
	scaleLabel: StringResource,
	scaleDescription: StringResource,
	scale: Double,
	onScaleChange: (Double) -> Unit,
	subdivisionsLabel: StringResource,
	subdivisionsDescription: StringResource,
	subdivisions: Int,
	onSubdivisionsChange: (Int) -> Unit,
) {
	val scaleText = stringResource(scaleLabel)
	val scaleTooltip = stringResource(scaleDescription)
	val subdivisionsText = stringResource(subdivisionsLabel)
	val subdivisionsTooltip = stringResource(subdivisionsDescription)
	FieldStack(
		rows =
			listOf(
				{ position ->
					PropertyFieldRow(label = scaleText, description = scaleTooltip) {
						NumberField(
							value = scale.toFloat(),
							onValueChange = { committed -> onScaleChange(committed.toDouble()) },
							range = ViewportSettings.GRID_SCALE_RANGE,
							stackPosition = position,
							modifier = Modifier.fillMaxWidth(),
						)
					}
				},
				{ position ->
					PropertyFieldRow(label = subdivisionsText, description = subdivisionsTooltip) {
						NumberField(
							value = subdivisions,
							onValueChange = onSubdivisionsChange,
							range = ViewportSettings.GRID_SUBDIVISIONS_RANGE,
							stackPosition = position,
							modifier = Modifier.fillMaxWidth(),
						)
					}
				},
			),
	)
}

/**
 * The Keybindings page: preset selection plus the per-command rebinding editor (see [KeybindingsEditor]),
 * all auto-saving to settings input.keybinding and re-resolving the live keymap.
 *
 * @param PreferencesFolds folds The window's fold state, which the category cards persist into.
 */
@Composable
internal fun KeybindingsSection(folds: PreferencesFolds) {
	KeybindingsEditor(folds)
}

/**
 * The Pen page: a placeholder until the pen backend (JPen / Wacom) and the radial menu land.
 */
@Composable
internal fun PenSection() {
	ComingSoon(stringResource(Res.string.settings_pen_coming_soon))
}

/**
 * A muted single-line placeholder for a page whose controls are not built yet.
 *
 * @param String message The already-localized placeholder line.
 */
@Composable
private fun ComingSoon(message: String) {
	Text(text = message, style = LocalUmamoTypography.current.bodyMedium, color = LocalUmamoColors.current.textMuted)
}