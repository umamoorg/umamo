package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/**
 * The document's atlas commands: operations on the packed texture pages themselves.
 *
 * The repack handler is a nullable collaborator resolved at DISPATCH time, like every table
 * collaborator: the table declares the command, the shell supplies the orchestration closure, and a
 * null handler (no document, or a platform without one) makes the dispatch a no-op behind the
 * availability gate.  The handler receives the area its operation settings strip shows in: the hovered
 * work surface, else the last one the pointer touched (routing.operationStripArea - the repack is
 * document-wide, so it can fire from anywhere, but the strip exists only in a 2D viewport or UV editor);
 * null when the pointer has touched neither.
 *
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @param CommandRouting      routing      The hovered-area resolver, read at dispatch.
 * @param Function?           repack       Launches the repack orchestration over the given area, or null
 *                                         when the shell has none to offer.
 * @return List<Command> The commands to register.
 */
internal fun atlasCommands(
	availability: SessionAvailability,
	routing: CommandRouting,
	repack: ((String?) -> Unit)?,
): List<Command> =
	listOf(
		Command(
			"document.repackAtlas",
			title = Res.string.cmd_document_repack_atlas,
			availability = availability.canRepackAtlas,
		) {
			repack?.invoke(routing.operationStripArea())
		},
	)