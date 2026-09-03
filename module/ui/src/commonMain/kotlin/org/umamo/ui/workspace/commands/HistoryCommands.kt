package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorSession
import org.umamo.ui.action.Command
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.OperationStripState

/**
 * The undo-history commands.  All three walk the session's snapshot stack or its adjustable record,
 * which is why they need no routing: history is per document, not per area, so where the pointer
 * is has no bearing on what they undo.
 *
 * Adjust Last Operation (Blender's F9) toggles the operation settings strip open: the strip already
 * sits where the last operation ran, so opening it is all the command has to do.
 *
 * @param EditorSession? editorSession The open document's session, or null (the commands then no-op).
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @param OperationStripState operationStrip The window's strip state the adjust command opens.
 * @return List<Command> The commands to register.
 */
internal fun historyCommands(
	editorSession: EditorSession?,
	availability: SessionAvailability,
	operationStrip: OperationStripState,
): List<Command> =
	listOf(
		Command("edit.undo", title = Res.string.cmd_undo, availability = availability.hasDocument) { editorSession?.undo() },
		Command("edit.redo", title = Res.string.cmd_redo, availability = availability.hasDocument) { editorSession?.redo() },
		Command("edit.adjustLastOperation", title = Res.string.cmd_adjust_last_operation, availability = availability.hasAdjustableOperation) {
			operationStrip.expanded = !operationStrip.expanded
		},
	)