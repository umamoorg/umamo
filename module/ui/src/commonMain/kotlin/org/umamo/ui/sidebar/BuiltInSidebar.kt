package org.umamo.ui.sidebar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ActiveSelectTool
import org.umamo.ui.kit.Text
import org.umamo.ui.l10n.UiText
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.viewport.labelResource
import org.umamo.ui.workspace.SpaceKind

/** The Item tab's id: the selection's own properties.  A contributor adds a panel to it by this id. */
const val ITEM_SIDEBAR_TAB_ID = "umamo.item"

/** The Tool tab's id: the active tool's settings.  A contributor adds a panel to it by this id. */
const val TOOL_SIDEBAR_TAB_ID = "umamo.tool"

/** The View tab's id: the view's own settings and the 2D cursor.  A contributor adds a panel to it by this id. */
const val VIEW_SIDEBAR_TAB_ID = "umamo.view"

/** The surfaces Umamo's own sidebar panels appear in. */
private val VIEWPORT_ONLY = setOf(SpaceKind.Viewport2D)

/**
 * Registers Umamo's own sidebar tabs and panels: Item, Tool, and View, in that order on the rail.
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
					spaces = VIEWPORT_ONLY,
					content = { PanelHint(Res.string.sidebar_panel_placeholder) },
				),
			),
			registry.register(
				SidebarPanel(
					id = "umamo.tool.activeTool",
					tabId = TOOL_SIDEBAR_TAB_ID,
					title = UiText.Resource(Res.string.sidebar_panel_active_tool),
					order = 100,
					spaces = VIEWPORT_ONLY,
					content = { context -> ActiveToolPanel(context) },
				),
			),
			registry.register(
				SidebarPanel(
					id = "umamo.view.cursor2d",
					tabId = VIEW_SIDEBAR_TAB_ID,
					title = UiText.Resource(Res.string.overlay_row_cursor),
					order = 100,
					spaces = VIEWPORT_ONLY,
					content = { PanelHint(Res.string.sidebar_panel_placeholder) },
				),
			),
		)

	return { registrations.forEach { registration -> registration.dispose() } }
}

/**
 * The Active Tool panel: the name of the tool armed or the transform running in this sidebar's area, or a
 * note that tool settings will live here.  A tool armed in another area is that area's, so it is not named.
 *
 * @param SidebarContext context The sidebar's context.
 */
@Composable
private fun ActiveToolPanel(context: SidebarContext) {
	val session = context.session
	val selectTool = session?.activeSelectTool?.collectAsState()?.value
	val meshOperator = session?.activeMeshOperator?.collectAsState()?.value
	val objectOperator = session?.activeObjectOperator?.collectAsState()?.value
	val zoomRegionArea = session?.zoomRegionArmedArea?.collectAsState()?.value
	val activeLabel: StringResource? =
		when {
			meshOperator != null && meshOperator.areaId == context.areaId -> meshOperator.kind.labelResource()
			objectOperator != null && objectOperator.areaId == context.areaId -> objectOperator.kind.labelResource()
			selectTool is ActiveSelectTool.BoxArmed && selectTool.areaId == context.areaId -> Res.string.cmd_mesh_box_select
			selectTool is ActiveSelectTool.Circle && selectTool.areaId == context.areaId -> Res.string.cmd_mesh_circle_select
			zoomRegionArea == context.areaId -> Res.string.cmd_view_zoom_region
			else -> null
		}

	if (activeLabel == null) {
		PanelHint(Res.string.sidebar_tool_settings_hint)

		return
	}

	Text(text = stringResource(activeLabel), style = LocalUmamoTypography.current.labelMedium, color = LocalUmamoColors.current.text)
}

/**
 * A muted one-line note standing in for a panel's rows.
 *
 * @param StringResource hint The note.
 */
@Composable
private fun PanelHint(hint: StringResource) {
	Text(text = stringResource(hint), style = LocalUmamoTypography.current.labelSmall, color = LocalUmamoColors.current.textMuted)
}