package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.SelectionOps
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.model.EditorModeHandle
import org.umamo.ui.model.SelectionHandle
import org.umamo.ui.resources.*

/**
 * The selection-clear and editor-mode commands.  Escape-to-clear is handled in the modal key ladder
 * (so it yields to an in-flight drag's area.dragCancel), not bound here; mode.toggleEdit is bound to
 * Tab in the default and Blender presets (the Cubism preset leaves it unbound).
 *
 * Its own table rather than part of the session groups because it closes over the shell's selection and
 * mode handles instead of the session itself, so it re-registers on a different trigger.
 *
 * @param SelectionHandle? selection The selection handle, or null with no document.
 * @param EditorModeHandle? editorMode The mode handle, or null with no document.
 * @return List<Command> The commands to register.
 */
internal fun modeCommands(selection: SelectionHandle?, editorMode: EditorModeHandle?): List<Command> {
	// Selection and mode commands need an open document (its selection / mode holders exist).
	val hasSelection = CommandAvailability { selection != null }
	val hasMode = CommandAvailability { editorMode != null }
	return listOf(
		Command("select.clear", title = Res.string.cmd_select_clear, availability = hasSelection) { selection?.set(SelectionOps.clear()) },
		Command("mode.toggleEdit", title = Res.string.cmd_mode_toggle_edit, availability = hasMode) {
			editorMode?.let { it.set(if (it.mode == EditorMode.Object) EditorMode.Edit else EditorMode.Object) }
		},
		// Explicit set-mode commands for the viewport header's mode dropdown (and the palette).  setMode
		// no-ops when already in the requested mode, so re-selecting the current row records no undo step.
		Command("mode.object", title = Res.string.cmd_mode_object, availability = hasMode) { editorMode?.set(EditorMode.Object) },
		Command("mode.edit", title = Res.string.cmd_mode_edit, availability = hasMode) { editorMode?.set(EditorMode.Edit) },
	)
}
