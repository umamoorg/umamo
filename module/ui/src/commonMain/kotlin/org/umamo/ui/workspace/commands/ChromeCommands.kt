package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandSection
import org.umamo.ui.action.CommandTable
import org.umamo.ui.help.ProjectInfo
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.area.AreaDragController
import org.umamo.ui.workspace.area.SplitterDragCancelController
import org.umamo.ui.workspace.layout.WorkspaceLayoutController
import org.umamo.ui.workspace.rowdrag.RowDragCancelController

/**
 * The shell-chrome commands: overlay toggles (palette, preferences, Help), the project links, the drag
 * cancels, and workspace tab navigation.  All are real registry commands so a key binding (or a menu)
 * drives them through the one dispatch point - no key handling hardcoded in the shell.
 *
 * Several are rows of the bar's menus and name the block they sit in: the settings close the Edit menu, the
 * tab navigation opens the Workspace menu, and the links, Quick Setup, and the two dialogs are three blocks of
 * Help.  Each block lists its rows in the order they are registered here.
 *
 * @param ShellOverlayState overlays The overlay flags the toggles flip.
 * @param AreaDragController dragController The area CORNER drag state area.dragCancel aborts.
 * @param SplitterDragCancelController splitterDragCancel The divider-drag seam area.dragCancel also aborts;
 *   that drag's session lives in SplitContainer's own state, so it can only be reached through this.
 * @param RowDragCancelController rowDragCancel The panel row-drag seam row.dragCancel invokes.
 * @param WorkspaceLayoutController workspaces The layout state workspace.prev/next shift.
 * @param Function openLink Opens a project URL through the platform's handler.
 * @return List<Command> The commands to register.
 */
internal fun chromeCommands(
	overlays: ShellOverlayState,
	dragController: AreaDragController,
	splitterDragCancel: SplitterDragCancelController,
	rowDragCancel: RowDragCancelController,
	workspaces: WorkspaceLayoutController,
	openLink: (url: String) -> Unit,
): List<Command> =
	commandTable(
		CommandTable.Chrome,
		Command("palette.toggle", title = null) { overlays.paletteVisible = !overlays.paletteVisible },
		// Cancels whichever kind of area drag is in flight - the two are separate mechanisms (a corner
		// drag splits/joins, a divider drag re-ratios) and only ever one is live, so both are addressed
		// here rather than splitting the one Escape-bound command in two.
		Command("area.dragCancel", title = null) {
			dragController.cancelDrag()
			splitterDragCancel.cancel?.invoke()
		},
		// The panel row-drag cancel (outliner / parameters rows), dispatched from the shell's Escape
		// precedence (mirroring area.dragCancel).
		Command("row.dragCancel", title = null) { rowDragCancel.cancel?.invoke() },
		// The preferences window opens through the registry like everything else, so the Edit menu, the
		// Ctrl/Cmd+, binding, and the command palette all reach it through the one dispatch point.  Titled,
		// so it surfaces in the palette ("Settings") for free.
		Command("edit.preferences", title = Res.string.cmd_preferences, group = CommandSection.EditSettings, groupLabel = Res.string.menu_preferences) {
			overlays.settingsVisible = !overlays.settingsVisible
		},
		// The Help dialogs open through the registry too, so the Help menu and the palette (both titled,
		// so they surface there for free) reach them through the one dispatch point.
		Command("help.credits", title = Res.string.menu_credits, group = CommandSection.HelpAbout) { overlays.creditsVisible = !overlays.creditsVisible },
		Command("help.about", title = Res.string.menu_about, group = CommandSection.HelpAbout) { overlays.aboutVisible = !overlays.aboutVisible },
		// Quick Setup opens by itself on a first run; this reopens it (the Help menu and the palette).
		Command("help.quickSetup", title = Res.string.menu_quick_setup, group = CommandSection.HelpSetup) { overlays.quickSetupVisible = !overlays.quickSetupVisible },
		// The project links are commands as well, so the Help menu's rows dispatch like every other row and
		// the palette finds the documentation by name.  The URLs come from ProjectInfo, the same source the
		// About dialog shows.
		Command("help.sourceCode", title = Res.string.menu_source_code, group = CommandSection.HelpLinks) { openLink(ProjectInfo.SOURCE_CODE_URL) },
		Command("help.webSite", title = Res.string.menu_web_site, group = CommandSection.HelpLinks) { openLink(ProjectInfo.WEB_SITE_URL) },
		Command("help.documentation", title = Res.string.menu_documentation, group = CommandSection.HelpLinks) { openLink(ProjectInfo.DOCUMENTATION_URL) },
		// Workspace navigation: titled so they surface in the palette, and the Workspace menu's first block, which a
		// tab's own menu lists too.
		Command("workspace.prev", title = Res.string.cmd_workspace_prev, group = CommandSection.WorkspaceSwitch) { workspaces.switchBy(-1) },
		Command("workspace.next", title = Res.string.cmd_workspace_next, group = CommandSection.WorkspaceSwitch) { workspaces.switchBy(1) },
	)