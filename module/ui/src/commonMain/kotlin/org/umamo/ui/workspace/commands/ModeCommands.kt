package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.SelectionOps
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandHint
import org.umamo.ui.action.CommandState
import org.umamo.ui.action.CommandTable
import org.umamo.ui.action.KeymapCategory
import org.umamo.ui.model.EditorModeHandle
import org.umamo.ui.model.SelectionHandle
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons

/**
 * The selection-clear and editor-mode commands.  Escape-to-clear is handled in the modal key ladder
 * (so it yields to an in-flight drag's area.dragCancel), not bound here; mode.toggleEdit is bound to
 * Tab in the default and Blender presets (the Cubism preset leaves it unbound), and mode.togglePose to
 * a literal Ctrl+Tab in all three.
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
	// The Edit toggle runs from every mode, and is suggested only on the way IN: the label reads "Edit Mode",
	// which from inside Edit mode names where the user already is.  The Pose toggle carries no hint: the status
	// bar cannot tell a viewport from a UV editor when it suggests, and over a UV editor Pose mode offers
	// nothing (and would push its Object-mode list past the strip's limit); the header's mode menu shows it.
	val outsideEditMode = CommandAvailability { editorMode != null && editorMode.mode != EditorMode.Edit }

	/**
	 * Reports whether [mode] is the editor's mode.  With no document open the mode reads as Object, the one a
	 * session starts in, so the header's mode chip shows the same face before the first file opens.
	 *
	 * @param EditorMode mode The mode to report on.
	 * @return CommandState The state of the command that sets [mode].
	 */
	fun modeState(mode: EditorMode): CommandState = CommandState { (editorMode?.mode ?: EditorMode.Object) == mode }

	return commandTable(
		CommandTable.Mode,
		Command(
			"select.clear",
			title = Res.string.cmd_select_clear,
			availability = hasSelection,
			keymapCategory = KeymapCategory.Select,
		) {
			selection?.set(SelectionOps.clear())
		},
		Command(
			"mode.toggleEdit",
			title = Res.string.cmd_mode_toggle_edit,
			availability = hasMode,
			hint = CommandHint(Res.string.status_bind_edit_mode, suggestedWhen = outsideEditMode),
		) {
			editorMode?.toggleEdit()
		},
		Command(
			"mode.togglePose",
			title = Res.string.cmd_mode_toggle_pose,
			availability = hasMode,
		) {
			editorMode?.togglePose()
		},
		// Explicit set-mode commands, the family the viewport header's mode dropdown lists (and the palette).
		// setMode no-ops when already in the requested mode, so re-selecting the current row records no undo step.
		Command(
			"mode.object",
			title = Res.string.cmd_mode_object,
			availability = hasMode,
			group = CommandFamily.EditorMode,
			icon = LocalUmamoIcons.editorModeObject,
			state = modeState(EditorMode.Object),
		) {
			editorMode?.set(EditorMode.Object)
		},
		Command(
			"mode.edit",
			title = Res.string.cmd_mode_edit,
			availability = hasMode,
			group = CommandFamily.EditorMode,
			icon = LocalUmamoIcons.editorModeEdit,
			state = modeState(EditorMode.Edit),
		) {
			editorMode?.set(EditorMode.Edit)
		},
		Command(
			"mode.pose",
			title = Res.string.cmd_mode_pose,
			availability = hasMode,
			group = CommandFamily.EditorMode,
			icon = LocalUmamoIcons.editorModePose,
			state = modeState(EditorMode.Pose),
		) {
			editorMode?.set(EditorMode.Pose)
		},
	)
}