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
 * imported tab (a non-destructive add).  The desktop app owns the Import/Export file handling and
 * invokes applyLayout / appendWorkspace here with the parsed argument - keeping the live layout state
 * the single source of truth.
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
			overlays.pendingConfirm = ConfirmRequest(Res.string.confirm_reset_workspace) { workspaces.resetActive() }
		},
		Command("workspace.applyLayout", title = null) { argument ->
			(argument as? InterfaceLayout)?.let { imported ->
				overlays.pendingConfirm = ConfirmRequest(Res.string.confirm_import_replace) { workspaces.applyImported(imported) }
			}
		},
		Command("workspace.appendWorkspace", title = null) { argument ->
			(argument as? Workspace)?.let { imported -> workspaces.appendImported(imported, newWorkspaceBaseName) }
		},
	)