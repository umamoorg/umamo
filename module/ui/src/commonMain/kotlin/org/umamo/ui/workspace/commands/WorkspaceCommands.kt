package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.action.CommandSection
import org.umamo.ui.action.CommandTable
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.layout.InterfaceLayout
import org.umamo.ui.workspace.layout.Workspace
import org.umamo.ui.workspace.layout.WorkspaceLayoutController
import org.umamo.ui.workspace.layout.WorkspaceNames

/**
 * The workspace-management commands.  New is what the tab strip's "+" button runs; Reset and Apply-Layout are
 * destructive, so they raise a confirm dialog rather than acting at once; Append-Workspace adds an
 * imported tab (a non-destructive add).  The file handling is its own table ([workspaceFileCommands]),
 * whose import parses a file and invokes applyLayout / appendWorkspace here with the result - keeping the
 * live layout state the single source of truth.
 *
 * Rename, Duplicate, and Delete act on one workspace: the one whose id they are run with, which is how a tab's
 * own menu aims them at that tab, or the active one when run with none, from the Workspace menu, the palette, or
 * a key.  Under the Workspace heading and on a tab they go by the verb alone.
 *
 * @param WorkspaceLayoutController workspaces The layout state the commands rewrite.
 * @param ShellOverlayState overlays The overlay state the confirms go through.
 * @param Function names Reads the localized chrome workspace names are made from.  Read when a command runs, so a
 *   language switch reaches it without re-registering.
 * @return List<Command> The commands to register.
 */
internal fun workspaceCommands(
	workspaces: WorkspaceLayoutController,
	overlays: ShellOverlayState,
	names: () -> WorkspaceNames,
): List<Command> {
	/**
	 * The workspace a command run with [argument] acts on: the one the argument names, else the active one.
	 *
	 * @param Any? argument A workspace id, or anything else for the active workspace.
	 * @return Workspace? The workspace, or null when the id names none the layout holds.
	 */
	fun targetOf(argument: Any?): Workspace? {
		val targetId = argument as? String ?: workspaces.layout.activeWorkspaceId

		return workspaces.layout.workspaces.firstOrNull { workspace -> workspace.id == targetId }
	}

	return commandTable(
		CommandTable.Workspace,
		Command("workspace.new", title = Res.string.workspace_new, group = CommandSection.WorkspaceManage) { workspaces.create(names().newBaseName) },
		Command("workspace.reset", title = Res.string.cmd_workspace_reset, group = CommandSection.WorkspaceManage, groupLabel = Res.string.menu_workspace_reset) {
			overlays.pendingConfirm = ConfirmRequest(Res.string.confirm_reset_workspace, confirmLabel = Res.string.dialog_reset) { workspaces.resetActive() }
		},
		// Opens the tab's in-place editor; the name is set when the editor commits.
		Command("workspace.rename", title = Res.string.cmd_workspace_rename, group = CommandSection.WorkspaceTab, groupLabel = Res.string.workspace_rename) { argument ->
			targetOf(argument)?.let { target -> workspaces.beginRename(target.id) }
		},
		Command("workspace.duplicate", title = Res.string.cmd_workspace_duplicate, group = CommandSection.WorkspaceTab, groupLabel = Res.string.workspace_duplicate) { argument ->
			targetOf(argument)?.let { target -> workspaces.duplicate(target.id, names().copyNameOf(target)) }
		},
		// The strip never goes empty, so the last workspace cannot be deleted.
		Command(
			"workspace.delete",
			title = Res.string.cmd_workspace_delete,
			availability = CommandAvailability { workspaces.layout.workspaces.size > 1 },
			group = CommandSection.WorkspaceTab,
			groupLabel = Res.string.workspace_delete,
		) { argument ->
			targetOf(argument)?.let { target -> workspaces.delete(target.id) }
		},
		Command("workspace.applyLayout", title = null) { argument ->
			(argument as? InterfaceLayout)?.let { imported ->
				overlays.pendingConfirm = ConfirmRequest(Res.string.confirm_import_replace, confirmLabel = Res.string.dialog_replace) { workspaces.applyImported(imported) }
			}
		},
		Command("workspace.appendWorkspace", title = null) { argument ->
			(argument as? Workspace)?.let { imported -> workspaces.appendImported(imported, names().newBaseName) }
		},
	)
}

/**
 * The workspace layout's file commands: import a layout or workspace file, export the active workspace,
 * export the whole layout - the Workspace menu's last block, in its order, which is the order the palette lists
 * them in.
 *
 * A table of its own rather than more rows on [workspaceCommands] because the two register in different
 * places: these read the persisted layout from settings and open a file dialog, so the settings-backed
 * shell registers them, while the shell proper stays Settings-free.  The actions arrive as plain lambdas,
 * like every other table's, so the table builds with no picker and no settings.
 *
 * None carries a default chord: they are occasional operations, reachable from the menu and the palette
 * and bindable in Preferences.
 *
 * @param Function onImport     Picks a layout or workspace file and applies it.
 * @param Function onExportThis Exports the active workspace to a file.
 * @param Function onExportAll  Exports the whole layout to a file.
 * @return List<Command> The commands to register.
 */
internal fun workspaceFileCommands(
	onImport: () -> Unit,
	onExportThis: () -> Unit,
	onExportAll: () -> Unit,
): List<Command> =
	commandTable(
		CommandTable.Workspace,
		Command("workspace.import", title = Res.string.menu_workspace_import, group = CommandSection.WorkspaceFiles) { onImport() },
		Command("workspace.exportThis", title = Res.string.menu_workspace_export_this, group = CommandSection.WorkspaceFiles) { onExportThis() },
		Command("workspace.exportAll", title = Res.string.menu_workspace_export_all, group = CommandSection.WorkspaceFiles) { onExportAll() },
	)