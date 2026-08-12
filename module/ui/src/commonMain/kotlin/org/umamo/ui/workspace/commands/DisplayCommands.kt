package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorSession
import org.umamo.edit.setSourceLayerDisplay
import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/**
 * The document's display commands: how the puppet is shown, as opposed to what it is.
 *
 * Document content rather than app chrome - the source formats author it, so it is one undo step and
 * it round-trips - which is why these edit the session rather than writing settings the way the
 * toolbar / sidebar toggles do.
 *
 * The toggle records authored INTENT and is available whenever a document is open, even one whose
 * artwork the renderer cannot display from.  Honoring it is best-effort per drawable, and intent that
 * cannot be honored today still round-trips and still applies once the artwork is there.
 *
 * @param EditorSession? editorSession The open document's session, or null (the command then no-ops).
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun displayCommands(editorSession: EditorSession?, availability: SessionAvailability): List<Command> =
	listOf(
		Command(
			"document.toggleSourceArtworkDisplay",
			title = Res.string.cmd_document_toggle_source_artwork,
			availability = availability.hasDocument,
		) {
			editorSession?.let { session ->
				session.setSourceLayerDisplay(!session.model.value.rendersFromSourceLayers)
			}
		},
	)