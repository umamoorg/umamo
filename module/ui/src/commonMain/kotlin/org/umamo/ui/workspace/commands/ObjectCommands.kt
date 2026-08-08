package org.umamo.ui.workspace.commands

import org.umamo.edit.DrawableChange
import org.umamo.edit.EditorSession
import org.umamo.edit.PartChange
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.visibilityOf
import org.umamo.edit.withSelectionVisibility
import org.umamo.ui.action.Command
import org.umamo.ui.model.SelectionHandle
import org.umamo.ui.resources.*

/**
 * The commands acting on the object selection itself: the visibility toggle and the outliner's
 * select-hierarchy.  Both work off the selection rather than the pointer, so they need no routing - a
 * selection is already a statement about what is meant, wherever the pointer happens to sit.
 *
 * @param EditorSession? editorSession The open document's session, or null (both commands then no-op).
 * @param SelectionHandle? selection The object-selection handle, or null with no document.
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun objectCommands(
	editorSession: EditorSession?,
	selection: SelectionHandle?,
	availability: SessionAvailability,
): List<Command> =
	listOf(
		// The first real model mutation: flips the selected parts'/drawables' eyeball as one undo step.
		Command("object.toggleVisibility", title = Res.string.cmd_toggle_visibility, availability = availability.hasDocument) {
			val current = selection?.selection
			val active = current?.active
			if (editorSession != null && active != null && !current.isEmpty) {
				// Flip relative to the active target's current state, applied to the whole selection, so a
				// mixed selection toggles consistently rather than each entity independently.
				val newVisible = !editorSession.model.value.visibilityOf(active)
				val change =
					when (active) {
						is SelectionTarget.Part -> PartChange.SetVisibility(active.id, newVisible)
						is SelectionTarget.Drawable -> DrawableChange.SetVisibility(active.id, newVisible)
						// A deformer has no visibility flag; nothing to toggle.
						is SelectionTarget.Deformer -> null
					}
				if (change != null) {
					editorSession.mutate(change) { model -> model.withSelectionVisibility(current.targets, newVisible) }
				}
			}
		},
		// Select Hierarchy: with a target argument (the outliner context menu passes its row) the row's
		// subtree replaces the selection; without one (the palette) the current selection expands in place.
		Command("outliner.selectHierarchy", title = Res.string.cmd_outliner_select_hierarchy, availability = availability.hasDocument) { argument ->
			val live = editorSession
			if (live != null) {
				val seed = (argument as? SelectionTarget)?.let { target -> Selection(setOf(target), target) } ?: live.selection.value
				live.setSelection(SelectionOps.selectHierarchy(seed, live.model.value))
			}
		},
	)