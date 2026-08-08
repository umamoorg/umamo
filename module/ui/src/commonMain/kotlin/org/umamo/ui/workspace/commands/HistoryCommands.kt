package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorSession
import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/**
 * The undo-history commands.  Both walk the session's snapshot stack, which is why they need no routing:
 * history is per document, not per area, so where the pointer is has no bearing on what they undo.
 *
 * @param EditorSession? editorSession The open document's session, or null (both commands then no-op).
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun historyCommands(editorSession: EditorSession?, availability: SessionAvailability): List<Command> =
	listOf(
		Command("edit.undo", title = Res.string.cmd_undo, availability = availability.hasDocument) { editorSession?.undo() },
		Command("edit.redo", title = Res.string.cmd_redo, availability = availability.hasDocument) { editorSession?.redo() },
	)