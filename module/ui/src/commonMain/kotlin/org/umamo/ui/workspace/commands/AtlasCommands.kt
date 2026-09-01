package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/**
 * The document's atlas commands: operations on the packed texture pages themselves.
 *
 * The repack handler is a nullable collaborator resolved at DISPATCH time, like every table
 * collaborator: the table declares the command, the shell supplies the orchestration closure, and a
 * null handler (no document, or a platform without one) makes the dispatch a no-op behind the
 * availability gate.
 *
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @param Function? repack Launches the repack orchestration, or null when the shell has none to offer.
 * @return List<Command> The commands to register.
 */
internal fun atlasCommands(
	availability: SessionAvailability,
	repack: (() -> Unit)?,
): List<Command> =
	listOf(
		Command(
			"document.repackAtlas",
			title = Res.string.cmd_document_repack_atlas,
			availability = availability.canRepackAtlas,
		) {
			repack?.invoke()
		},
	)