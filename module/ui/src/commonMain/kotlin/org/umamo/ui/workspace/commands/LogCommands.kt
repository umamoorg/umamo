package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/**
 * The diagnostic-log commands.
 *
 * Registered by the settings-backed shell, beside the workspace layout's file commands: the log exists
 * from startup whatever document is open, so the export is live from launch.  The Logs panel's Export
 * button dispatches the id instead of holding a handler, so the panel needs no picker of its own - and the
 * command palette reaches the same operation for free.
 *
 * @param Function onExportLog Writes the retained log buffer to a picked file.
 * @return List<Command> The commands to register.
 */
internal fun logCommands(onExportLog: () -> Unit): List<Command> =
	listOf(Command("logs.export", title = Res.string.logs_export) { onExportLog() })