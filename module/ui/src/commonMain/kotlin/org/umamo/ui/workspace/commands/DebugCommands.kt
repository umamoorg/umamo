package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.PuppetViewportService

/**
 * The diagnostic commands: a measurement aid for the wireframe culling's cost on a real GPU, to be removed
 * once measured.  The one command toggles the render service's scrub profiler, which times every 2D frame a
 * parameter move causes while armed and logs its report when the scrub ends or when toggled off; each toggle
 * starts from the service's own state, so a new document begins disarmed.
 *
 * @param Function service The current render service, or null when none (then the command hides).
 * @return List<Command> The commands to register.
 */
internal fun debugCommands(service: () -> PuppetViewportService?): List<Command> {
	var armed = false
	return listOf(
		Command("debug.profileScrub", title = Res.string.cmd_debug_profile_scrub, availability = CommandAvailability { service() != null }) {
			val current = service() ?: return@Command
			armed = !armed
			current.setScrubProfiling(armed)
		},
	)
}