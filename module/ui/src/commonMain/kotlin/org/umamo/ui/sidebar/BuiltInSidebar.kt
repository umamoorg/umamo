package org.umamo.ui.sidebar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.BrushMode
import org.umamo.edit.BrushSettings
import org.umamo.edit.EditorSession
import org.umamo.edit.MAX_BRUSH_RADIUS_WORLD
import org.umamo.edit.MAX_BRUSH_STRENGTH
import org.umamo.edit.MIN_BRUSH_RADIUS_WORLD
import org.umamo.edit.MIN_BRUSH_STRENGTH
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.transform.setBrushRadius
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.kit.field.SelectField
import org.umamo.ui.l10n.UiText
import org.umamo.ui.properties.MutedNote
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.viewport.falloffLabel
import org.umamo.ui.viewport.labelResource
import org.umamo.ui.workspace.SpaceScope

/** The Item tab's id: the selection's own properties.  A contributor adds a panel to it by this id. */
const val ITEM_SIDEBAR_TAB_ID = "umamo.item"

/** The Tool tab's id: the active tool's settings.  A contributor adds a panel to it by this id. */
const val TOOL_SIDEBAR_TAB_ID = "umamo.tool"

/** The View tab's id: the view's own settings and the 2D cursor.  A contributor adds a panel to it by this id. */
const val VIEW_SIDEBAR_TAB_ID = "umamo.view"

/**
 * Registers Umamo's own sidebar tabs and panels: Item, Tool, and View, in that order on the rail.  Item and View
 * have one panel per work surface, each scoped to its space, and Tool's panel serves both.
 *
 * @param SidebarRegistry registry The registry to register into.
 * @return Function Withdraws everything this call registered.
 */
fun registerBuiltInSidebar(registry: SidebarRegistry): () -> Unit {
	val registrations =
		listOf(
			registry.register(SidebarTab(ITEM_SIDEBAR_TAB_ID, UiText.Resource(Res.string.sidebar_tab_item), order = 100)),
			registry.register(SidebarTab(TOOL_SIDEBAR_TAB_ID, UiText.Resource(Res.string.sidebar_tab_tool), order = 200)),
			registry.register(SidebarTab(VIEW_SIDEBAR_TAB_ID, UiText.Resource(Res.string.sidebar_tab_view), order = 300)),
			registry.register(
				SidebarPanel(
					id = "umamo.item.transform",
					tabId = ITEM_SIDEBAR_TAB_ID,
					title = UiText.Resource(Res.string.properties_section_transform),
					order = 100,
					spaces = SpaceScope.Viewport2D,
					content = { context -> ItemTransformPanel(context) },
				),
			),
			registry.register(
				SidebarPanel(
					id = "umamo.item.uvTransform",
					tabId = ITEM_SIDEBAR_TAB_ID,
					title = UiText.Resource(Res.string.properties_section_transform),
					order = 100,
					spaces = SpaceScope.UvEditor,
					content = { context -> UvItemTransformPanel(context) },
				),
			),
			registry.register(
				SidebarPanel(
					id = "umamo.tool.activeTool",
					tabId = TOOL_SIDEBAR_TAB_ID,
					title = UiText.Resource(Res.string.sidebar_panel_active_tool),
					order = 100,
					spaces = SpaceScope.WorkSurfaces,
					content = { context -> ActiveToolPanel(context) },
				),
			),
			registry.register(
				SidebarPanel(
					id = "umamo.view.cursor2d",
					tabId = VIEW_SIDEBAR_TAB_ID,
					title = UiText.Resource(Res.string.overlay_row_cursor),
					order = 100,
					spaces = SpaceScope.Viewport2D,
					content = { context -> CursorPanel(context) },
				),
			),
			registry.register(
				SidebarPanel(
					id = "umamo.view.uvCursor",
					tabId = VIEW_SIDEBAR_TAB_ID,
					title = UiText.Resource(Res.string.overlay_row_cursor),
					order = 100,
					spaces = SpaceScope.UvEditor,
					content = { context -> UvCursorPanel(context) },
				),
			),
		)

	return { registrations.forEach { registration -> registration.dispose() } }
}

/**
 * The Active Tool panel: the name of the tool armed or the transform running in this sidebar's area, or a
 * note that tool settings will live here.  A tool armed in another area is that area's, so it is not named.
 *
 * The Deform brush is the one armed tool that carries settings of its own, so its panel is the settings
 * rather than the name.
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
private fun ActiveToolPanel(context: SidebarContext) {
	val session = context.session

	if (session == null) {
		MutedNote(Res.string.sidebar_tool_settings_hint)

		return
	}

	val selectTool = session.activeSelectTool.collectAsState().value
	val meshOperator = session.activeMeshOperator.collectAsState().value
	val uvOperator = session.activeUvOperator.collectAsState().value
	val objectOperator = session.activeObjectOperator.collectAsState().value
	val zoomRegionArea = session.zoomRegionArmedArea.collectAsState().value
	val activeBrush = session.activeBrush.collectAsState().value
	val brushSettings = session.brushSettings.collectAsState().value

	if (activeBrush != null && activeBrush.areaId == context.areaId) {
		BrushSettingsPanel(session, brushSettings)

		return
	}

	val activeLabel: StringResource? =
		when {
			meshOperator != null && meshOperator.areaId == context.areaId -> meshOperator.kind.labelResource()
			uvOperator != null && uvOperator.areaId == context.areaId -> uvOperator.kind.labelResource()
			objectOperator != null && objectOperator.areaId == context.areaId -> objectOperator.kind.labelResource()
			selectTool is ActiveSelectTool.BoxArmed && selectTool.areaId == context.areaId -> Res.string.cmd_mesh_box_select
			selectTool is ActiveSelectTool.Circle && selectTool.areaId == context.areaId -> Res.string.cmd_mesh_circle_select
			zoomRegionArea == context.areaId -> Res.string.cmd_view_zoom_region
			else -> null
		}

	if (activeLabel == null) {
		MutedNote(Res.string.sidebar_tool_settings_hint)

		return
	}

	Text(text = stringResource(activeLabel), style = LocalUmamoTypography.current.labelMedium, color = LocalUmamoColors.current.text)
}

/** The Strength field's scrub step. */
private const val BRUSH_STRENGTH_STEP = 0.05f

/** The Radius field's scrub step, in world units. */
private const val BRUSH_RADIUS_STEP = 1f

/**
 * The Deform brush's settings, as the Tool tab shows them while the brush is armed in this sidebar's area:
 * the mode it moves the points it covers by, the strength it carries them with, the radius it reaches, and
 * the falloff curve shaping its influence.
 *
 * Every row writes the session's settings, so the next stroke starts from what the rigger dialled in.
 *
 * @param EditorSession session The session holding the brush and its settings.
 * @param BrushSettings settings The settings the rows show and write.
 */
@Composable
private fun BrushSettingsPanel(session: EditorSession, settings: BrushSettings) {
	val modeLabels = BrushMode.entries.associate { mode -> mode to brushModeLabel(mode) }
	val falloffLabels = ProportionalFalloff.entries.associate { falloff -> falloff to falloffLabel(falloff) }

	Text(
		text = stringResource(Res.string.cmd_mesh_deform_brush),
		style = LocalUmamoTypography.current.labelMedium,
		color = LocalUmamoColors.current.text,
	)

	PropertyFieldRow(
		label = stringResource(Res.string.brush_options_mode),
		description = stringResource(Res.string.brush_options_mode_description),
	) {
		SelectField(
			selected = settings.mode,
			options = BrushMode.entries,
			label = { mode -> modeLabels.getValue(mode) },
			onSelect = { mode -> session.setBrushSettings(settings.copy(mode = mode)) },
			modifier = Modifier.fillMaxWidth(),
		)
	}

	PropertyFieldRow(
		label = stringResource(Res.string.brush_options_strength),
		description = stringResource(Res.string.brush_options_strength_description),
	) {
		NumberField(
			value = settings.strength,
			onValueChange = { strength -> session.setBrushSettings(settings.copy(strength = strength)) },
			range = MIN_BRUSH_STRENGTH..MAX_BRUSH_STRENGTH,
			modifier = Modifier.fillMaxWidth(),
			decimals = 2,
			step = BRUSH_STRENGTH_STEP,
		)
	}

	PropertyFieldRow(
		label = stringResource(Res.string.brush_options_radius),
		description = stringResource(Res.string.brush_options_radius_description),
	) {
		NumberField(
			value = settings.radiusWorld,
			onValueChange = { radiusWorld -> session.setBrushRadius(radiusWorld) },
			range = MIN_BRUSH_RADIUS_WORLD..MAX_BRUSH_RADIUS_WORLD,
			modifier = Modifier.fillMaxWidth(),
			step = BRUSH_RADIUS_STEP,
			unitSuffix = stringResource(Res.string.unit_pixels),
		)
	}

	PropertyFieldRow(
		label = stringResource(Res.string.brush_options_falloff),
		description = stringResource(Res.string.brush_options_falloff_description),
	) {
		SelectField(
			selected = settings.falloff,
			options = ProportionalFalloff.entries,
			label = { falloff -> falloffLabels.getValue(falloff) },
			onSelect = { falloff -> session.setBrushSettings(settings.copy(falloff = falloff)) },
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/**
 * The localized name of a brush mode: the one the operation strip's Mode row and this panel both show.
 *
 * @param BrushMode mode The mode to name.
 * @return String The localized mode name.
 */
@Composable
private fun brushModeLabel(mode: BrushMode): String =
	when (mode) {
		BrushMode.Drag -> stringResource(Res.string.brush_mode_drag)
		BrushMode.Flow -> stringResource(Res.string.brush_mode_flow)
	}