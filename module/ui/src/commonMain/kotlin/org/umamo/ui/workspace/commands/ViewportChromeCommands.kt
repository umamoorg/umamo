package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.AreaToolbarHub
import org.umamo.ui.workspace.ChromeVisibility
import org.umamo.ui.workspace.SpaceScope
import org.umamo.ui.workspace.ViewportChromeState
import org.umamo.ui.workspace.sidebar.AreaSidebarHub
import org.umamo.ui.workspace.sidebar.SidebarState

/**
 * The work-surface chrome toggles, acting on the area the pointer last touched: the 2D viewport registers its
 * area's toolbar and sidebar visibility into the shared hubs and the UV editor its sidebar's, and a toggle flips
 * the hovered area's, making that the area's own choice (Blender's hovered-area routing, as the overlay commands
 * use).  With the pointer over any other area, or T over a UV editor, which has no toolbar, they do nothing.  An
 * area with no choice of its own follows the application's defaults, which [defaults] reads when the command runs.
 *
 * @param AreaToolbarHub areaToolbars The 2D viewports' per-area toolbar visibility.
 * @param AreaSidebarHub areaSidebars The work surfaces' per-area sidebar state.
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @param Function defaults The application's chrome defaults, read when a command runs.
 * @return List<Command> The commands to register.
 */
internal fun viewportChromeCommands(
	areaToolbars: AreaToolbarHub,
	areaSidebars: AreaSidebarHub,
	routing: CommandRouting,
	defaults: () -> ViewportChromeState,
): List<Command> {
	/**
	 * The toolbar visibility of the area the pointer last touched, or null when none is registered under it.
	 *
	 * @return ChromeVisibility? The hovered area's toolbar visibility, or null.
	 */
	fun hoveredToolbar(): ChromeVisibility? = routing.hovered()?.areaId?.let { areaId -> areaToolbars.forArea(areaId) }

	/**
	 * The sidebar state of the area the pointer last touched, or null when none is registered under it.
	 *
	 * @return SidebarState? The hovered area's sidebar state, or null.
	 */
	fun hoveredSidebar(): SidebarState? = routing.hovered()?.areaId?.let { areaId -> areaSidebars.forArea(areaId) }

	return listOf(
		Command("view.toggleToolbar", title = Res.string.cmd_view_toggle_toolbar, spaces = SpaceScope.Viewport2D) {
			hoveredToolbar()?.toggle(defaults().showToolbar)
		},
		Command("view.toggleSidebar", title = Res.string.cmd_view_toggle_sidebar, spaces = SpaceScope.WorkSurfaces) {
			hoveredSidebar()?.visibility?.toggle(defaults().showSidebar)
		},
	)
}