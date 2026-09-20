package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.InterfaceLayout
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.Workspace
import org.umamo.ui.workspace.WorkspaceLayoutController

/**
 * The workspace-management commands.  New mirrors the "+" create path; Reset and Apply-Layout are
 * destructive, so they raise a confirm dialog rather than acting at once; Append-Workspace adds an
 * imported tab (a non-destructive add).  The file handling is its own table ([workspaceFileCommands]),
 * whose import parses a file and invokes applyLayout / appendWorkspace here with the result - keeping the
 * live layout state the single source of truth.
 *
 * @param WorkspaceLayoutController workspaces The layout state the commands rewrite.
 * @param ShellOverlayState overlays The overlay state the confirms go through.
 * @param String newWorkspaceBaseName The localized base name new workspaces are named from (deduped).
 * @return List<Command> The commands to register.
 */
internal fun workspaceCommands(
	workspaces: WorkspaceLayoutController,
	overlays: ShellOverlayState,
	newWorkspaceBaseName: String,
): List<Command> =
	listOf(
		Command("workspace.new", title = Res.string.workspace_new) { workspaces.create(newWorkspaceBaseName) },
		Command("workspace.reset", title = Res.string.cmd_workspace_reset) {
			overlays.pendingConfirm = ConfirmRequest(Res.string.confirm_reset_workspace, confirmLabel = Res.string.dialog_reset) { workspaces.resetActive() }
		},
		Command("workspace.applyLayout", title = null) { argument ->
			(argument as? InterfaceLayout)?.let { imported ->
				overlays.pendingConfirm = ConfirmRequest(Res.string.confirm_import_replace, confirmLabel = Res.string.dialog_replace) { workspaces.applyImported(imported) }
			}
		},
		Command("workspace.appendWorkspace", title = null) { argument ->
			(argument as? Workspace)?.let { imported -> workspaces.appendImported(imported, newWorkspaceBaseName) }
		},
	)

/**
 * The workspace layout's file commands: import a layout or workspace file, export the active workspace,
 * export the whole layout - in the Workspace menu's order, which is the order the palette lists them in.
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
	listOf(
		Command("workspace.import", title = Res.string.menu_workspace_import) { onImport() },
		Command("workspace.exportThis", title = Res.string.menu_workspace_export_this) { onExportThis() },
		Command("workspace.exportAll", title = Res.string.menu_workspace_export_all) { onExportAll() },
	)