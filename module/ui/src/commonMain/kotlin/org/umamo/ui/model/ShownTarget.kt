package org.umamo.ui.model

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.hasTarget
import org.umamo.runtime.model.PuppetModel

/**
 * The item the panels that show one item at a time show (the Properties panel's Object and Data tabs, the
 * sidebar's Item tab), as Blender's do: the active item, even when several are selected; else the item last
 * made active, which stays shown after a deselect; else any selected item.  Every candidate must still be in
 * [model], since deleting an object leaves the selection naming it.
 *
 * @param Selection selection The object selection.
 * @param SelectionTarget? lastActive The item last made active, remembered across a deselect, or null.
 * @param PuppetModel model The open document's model.
 * @return SelectionTarget? The item to show, or null when there is none.
 */
fun shownTargetOf(selection: Selection, lastActive: SelectionTarget?, model: PuppetModel): SelectionTarget? =
	selection.active?.takeIf(model::hasTarget)
		?: lastActive?.takeIf(model::hasTarget)
		?: selection.targets.firstOrNull(model::hasTarget)