package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.AreaDragController
import org.umamo.ui.workspace.RowDragCancelController
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.SplitterDragCancelController
import org.umamo.ui.workspace.WorkspaceLayoutController

/**
 * The shell-chrome commands: overlay toggles (palette, preferences, Help), the drag cancels, and
 * workspace tab navigation.  All are real registry commands so a key binding (or a future menu)
 * drives them through the one dispatch point - no key handling hardcoded in the shell.
 *
 * @param ShellOverlayState overlays The overlay flags the toggles flip.
 * @param AreaDragController dragController The area CORNER drag state area.dragCancel aborts.
 * @param SplitterDragCancelController splitterDragCancel The divider-drag seam area.dragCancel also aborts;
 *   that drag's session lives in SplitContainer's own state, so it can only be reached through this.
 * @param RowDragCancelController rowDragCancel The panel row-drag seam row.dragCancel invokes.
 * @param WorkspaceLayoutController workspaces The layout state workspace.prev/next shift.
 * @return List<Command> The commands to register.
 */
internal fun chromeCommands(
	overlays: ShellOverlayState,
	dragController: AreaDragController,
	splitterDragCancel: SplitterDragCancelController,
	rowDragCancel: RowDragCancelController,
	workspaces: WorkspaceLayoutController,
): List<Command> =
	listOf(
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
		// Ctrl/Cmd+, binding, and the command palette all reach it through the one dispatch point. Titled,
		// so it surfaces in the palette ("Settings") for free.
		Command("edit.preferences", title = Res.string.cmd_preferences) { overlays.settingsVisible = !overlays.settingsVisible },
		// The Help dialogs open through the registry too, so the Help menu and the palette (both titled,
		// so they surface there for free) reach them through the one dispatch point.
		Command("help.about", title = Res.string.menu_about) { overlays.aboutVisible = !overlays.aboutVisible },
		Command("help.credits", title = Res.string.menu_credits) { overlays.creditsVisible = !overlays.creditsVisible },
		// Workspace navigation: titled so they surface in the palette and resolve a shortcut hint in the tab
		// context menu. The tab strip's Previous/Next rows dispatch the same ids, so menu and key share a path.
		Command("workspace.prev", title = Res.string.cmd_workspace_prev) { workspaces.switchBy(-1) },
		Command("workspace.next", title = Res.string.cmd_workspace_next) { workspaces.switchBy(1) },
	)
