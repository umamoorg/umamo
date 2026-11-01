package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_check_for_updates

/**
 * Help > Check for Updates: asks GitHub whether a newer release is published and says what it found.
 * Umamo tells the rigger, and never updates itself.
 *
 * Registered by the app only when its host can make the request, so on a platform without one neither the menu row
 * nor the palette ever offers it.
 *
 * @param Function onCheck Runs the check.
 * @return List<Command> The commands to register.
 */
internal fun updateCommands(onCheck: () -> Unit): List<Command> =
	listOf(Command("help.checkForUpdates", title = Res.string.menu_check_for_updates) { onCheck() })