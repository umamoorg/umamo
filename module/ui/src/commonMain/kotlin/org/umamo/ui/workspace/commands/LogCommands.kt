package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/**
 * The diagnostic-log commands.
 *
 * Registered by the app rather than the shell for the same reason as the file commands: writing the
 * retained log buffer out needs the file picker, which lives above the shell.  The Logs panel's Export
 * button dispatches the id instead of holding a handler, so the panel itself stays in commonMain with no
 * picker dependency - and the command palette reaches the same operation for free.
 *
 * @param Function onExportLog Writes the retained log buffer to a picked file.
 * @return List<Command> The commands to register.
 */
internal fun logCommands(onExportLog: () -> Unit): List<Command> =
	listOf(Command("logs.export", title = Res.string.logs_export) { onExportLog() })
