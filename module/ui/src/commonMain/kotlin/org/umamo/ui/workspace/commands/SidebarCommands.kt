package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandSpaces
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.sidebar.AreaSidebarHub
import org.umamo.ui.workspace.sidebar.SidebarState

/**
 * The sidebar tab commands, stepping the sidebar of the work surface the pointer last touched to the next or
 * previous tab on its rail, wrapping past either end: the palette's and the keymap's path onto what
 * Ctrl+wheel over the rail does.  Each work surface registers its area's sidebar state into the shared hub;
 * with the pointer over no such area, or the sidebar closed, a command does nothing.  They ship unbound.
 *
 * @param AreaSidebarHub areaSidebars The work surfaces' per-area sidebar state registry.
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @return List<Command> The commands to register.
 */
internal fun sidebarCommands(areaSidebars: AreaSidebarHub, routing: CommandRouting): List<Command> {
	/**
	 * The sidebar state of the area the pointer last touched, or null when none is registered under it.
	 *
	 * @return SidebarState? The hovered area's sidebar state, or null.
	 */
	fun hoveredSidebar(): SidebarState? = routing.hovered()?.areaId?.let { areaId -> areaSidebars.forArea(areaId) }

	return listOf(
		Command("view.sidebar.nextTab", title = Res.string.cmd_view_sidebar_next_tab, spaces = CommandSpaces.WorkSurfaces) {
			hoveredSidebar()?.selectAdjacentTab(1)
		},
		Command("view.sidebar.previousTab", title = Res.string.cmd_view_sidebar_previous_tab, spaces = CommandSpaces.WorkSurfaces) {
			hoveredSidebar()?.selectAdjacentTab(-1)
		},
	)
}