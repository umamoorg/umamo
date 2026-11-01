package org.umamo.ui.workspace.spaces.outliner

import org.umamo.edit.SelectionTarget
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.kit.menu.MenuItem

/** The command Select Hierarchy dispatches, so the palette and a future binding share the edit. */
private const val SELECT_HIERARCHY_COMMAND = "outliner.selectHierarchy"

/** The command Create Warp Deformer dispatches, so the palette, the viewport's menu, and this row share the edit. */
private const val CREATE_WARP_COMMAND = "deformer.createWarp"

/**
 * Builds the context-menu entries for a real outliner row, by entity kind: parts and drawables get a
 * visibility toggle (deformers have none); every kind gets selectability, rename, and delete.  In Object mode
 * drawables and deformers also get Create Warp Deformer, which wraps the selection and is enabled exactly when
 * its command is; it is an Object-mode operation, so no other mode lists it.  A part's "Delete" ungroups (keeps
 * the contents, splicing them into its parent) and a second "Delete Hierarchy" cascades (removes the whole
 * subtree); a drawable or deformer has the single delete.  Selectability gates only viewport picking, so every
 * entry stays enabled on an unselectable row.
 *
 * A plain function over the resolved labels: the row builds it as its menu opens, so the mode and the command's
 * availability are read as they are then, and commonTest pins the entries directly.
 *
 * @param SelectionTarget target The row's entity.
 * @param OutlinerLabels labels The outliner's localized chrome.
 * @param CommandRegistry commands The registry Select Hierarchy (with the row as its argument) and Create Warp Deformer
 *   dispatch through.
 * @param Boolean inObjectMode Whether the session is in Object mode, the only mode Create Warp Deformer is listed in.
 * @param Function onToggleVisibility Flips the entity's visibility.
 * @param Function onToggleSelectable Flips the entity's selectability.
 * @param Function onStartRename Opens the inline rename.
 * @param Function onRequestDelete Requests a delete (true = cascade / the sole delete, false = ungroup).
 * @return List The menu entries.
 */
internal fun outlinerRowMenuItems(
	target: SelectionTarget,
	labels: OutlinerLabels,
	commands: CommandRegistry,
	inObjectMode: Boolean,
	onToggleVisibility: () -> Unit,
	onToggleSelectable: () -> Unit,
	onStartRename: () -> Unit,
	onRequestDelete: (cascade: Boolean) -> Unit,
): List<MenuItem> =
	buildList {
		add(MenuItem.Action(labels.selectHierarchy, onSelect = { commands.invoke(SELECT_HIERARCHY_COMMAND, target) }))
		add(MenuItem.Separator)

		if (target is SelectionTarget.Part || target is SelectionTarget.Drawable) {
			add(MenuItem.Action(labels.visibility, onSelect = onToggleVisibility))
		}

		add(MenuItem.Action(labels.selectable, onSelect = onToggleSelectable))
		add(MenuItem.Action(labels.rename, onSelect = onStartRename))

		if (inObjectMode && (target is SelectionTarget.Drawable || target is SelectionTarget.Deformer)) {
			add(MenuItem.Separator)
			add(MenuItem.Action(labels.createWarp, onSelect = { commands.invoke(CREATE_WARP_COMMAND) }, enabled = commands[CREATE_WARP_COMMAND]?.availability?.isAvailable() == true))
		}

		add(MenuItem.Separator)
		// "Delete" removes just this item (a part keeps its contents, which rise to the parent); "Delete
		// Hierarchy" removes a part's whole subtree.  A drawable / deformer has no hierarchy, so just "Delete".
		add(MenuItem.Action(labels.delete, onSelect = { onRequestDelete(false) }))

		if (target is SelectionTarget.Part) {
			add(MenuItem.Action(labels.deleteHierarchy, onSelect = { onRequestDelete(true) }))
		}
	}