package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/**
 * The commands that make meshes from the art under them, over the Object-mode selection or the Edit-mode
 * session: Generate Mesh from Art, and Generate Quad Mesh, which puts a plain quad back over the art.
 *
 * Each handler is a nullable collaborator resolved at DISPATCH time, like the repack's: the table declares
 * the command, the shell supplies the run, and a null handler (no document, or no art store) makes the
 * dispatch a no-op behind the availability gate.  A handler receives the area its operation settings
 * strip shows in (routing.operationStripArea - the hovered work surface, else the last one touched, else
 * the workspace's first), so the command can fire from any space.
 *
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @param CommandRouting      routing      The hovered-area resolver, read at dispatch.
 * @param Function?           generate     Runs Generate Mesh from Art over the given strip area, or null when the shell has none to offer.
 * @param Function?           generateQuad Runs Generate Quad Mesh over the given strip area, or null when the shell has none to offer.
 * @return List<Command> The commands to register.
 */
internal fun meshGenerationCommands(
	availability: SessionAvailability,
	routing: CommandRouting,
	generate: ((String?) -> Unit)?,
	generateQuad: ((String?) -> Unit)?,
): List<Command> =
	listOf(
		Command(
			"mesh.generateFromArt",
			title = Res.string.cmd_mesh_generate_from_art,
			availability = availability.canGenerateMeshFromArt,
		) {
			generate?.invoke(routing.operationStripArea())
		},
		Command(
			"mesh.generateQuad",
			title = Res.string.cmd_mesh_generate_quad,
			availability = availability.canGenerateMeshFromArt,
		) {
			generateQuad?.invoke(routing.operationStripArea())
		},
	)