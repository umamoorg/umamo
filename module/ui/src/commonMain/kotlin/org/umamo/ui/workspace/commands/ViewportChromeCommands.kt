package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandSpaces
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.AreaToolbarHub
import org.umamo.ui.workspace.ViewportChromeState
import org.umamo.ui.workspace.sidebar.AreaSidebarHub

/**
 * The viewport chrome toggles, acting on the 2D viewport the pointer last touched: each viewport registers
 * its area's toolbar and sidebar visibility into the shared hubs, and a toggle flips the hovered area's,
 * making that the area's own choice (Blender's hovered-area routing, as the overlay commands use).  With the
 * pointer over any other area they do nothing.  An area with no choice of its own follows the application's
 * defaults, which [defaults] reads when the command runs.
 *
 * @param AreaToolbarHub areaToolbars The viewports' per-area toolbar visibility.
 * @param AreaSidebarHub areaSidebars The viewports' per-area sidebar state.
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @param Function defaults The application's chrome defaults, read when a command runs.
 * @return List<Command> The commands to register.
 */
internal fun viewportChromeCommands(
	areaToolbars: AreaToolbarHub,
	areaSidebars: AreaSidebarHub,
	routing: CommandRouting,
	defaults: () -> ViewportChromeState,
): List<Command> =
	listOf(
		Command("view.toggleToolbar", title = Res.string.cmd_view_toggle_toolbar, spaces = CommandSpaces.Viewport2D) {
			val hoveredArea = routing.hovered()?.areaId
			hoveredArea?.let(areaToolbars::forArea)?.toggle(defaults().showToolbar)
		},
		Command("view.toggleSidebar", title = Res.string.cmd_view_toggle_sidebar, spaces = CommandSpaces.Viewport2D) {
			val hoveredArea = routing.hovered()?.areaId
			hoveredArea?.let(areaSidebars::forArea)?.visibility?.toggle(defaults().showSidebar)
		},
	)