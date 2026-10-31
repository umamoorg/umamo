package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.resources.*
import org.umamo.ui.transform.subdivideLatticesAdjustable
import org.umamo.ui.transform.subdivisionTargets

/**
 * The commands that rewrite a warp's lattice at rest: Subdivide Lattice, which splits every cell in two along both axes,
 * keeping a quad-mode warp's map exactly.
 *
 * @param EditorSession? editorSession The open document's session, or null (the command then no-ops).
 * @param CommandRouting routing The hovered-area resolver, read at dispatch.
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun latticeCommands(
	editorSession: EditorSession?,
	routing: CommandRouting,
	availability: SessionAvailability,
): List<Command> =
	listOf(
		Command(
			"lattice.subdivide",
			title = Res.string.cmd_lattice_subdivide,
			availability = availability.inEditMode,
		) {
			editorSession?.let { session -> session.subdivideLatticesAdjustable(session.subdivisionTargets(), routing.operationStripArea()) }
		},
	)