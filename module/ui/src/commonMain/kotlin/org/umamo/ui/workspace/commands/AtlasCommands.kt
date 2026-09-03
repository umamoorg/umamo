package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/**
 * The document's atlas commands: operations on the packed texture pages themselves.
 *
 * The repack handler is a nullable collaborator resolved at DISPATCH time, like every table
 * collaborator: the table declares the command, the shell supplies the orchestration closure, and a
 * null handler (no document, or a platform without one) makes the dispatch a no-op behind the
 * availability gate.  The handler receives the area the pointer was over when the command fired -
 * any kind, since the repack is document-wide - which is where its operation settings strip shows
 * (Blender shows the panel wherever the operator ran); null when the pointer was over no area.
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
			repack?.invoke(routing.hoveredAreaIdAnyKind())
		},
	)