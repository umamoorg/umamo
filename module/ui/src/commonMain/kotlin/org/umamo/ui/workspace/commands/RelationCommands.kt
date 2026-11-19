package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.relation.RelationSlot
import org.umamo.edit.relation.relationBound
import org.umamo.edit.relation.relationPeers
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandSection
import org.umamo.ui.action.CommandTable
import org.umamo.ui.resources.*
import org.umamo.ui.transform.bindRelation
import org.umamo.ui.transform.copyRelationToSelected
import org.umamo.ui.workspace.RelationPickController

/**
 * The commands a relation's context menu lists: Select what it binds, Clear it, arm its eyedropper, Remove a list
 * row, and Copy to Selected.  Each runs with the relation slot the menu was opened on as its argument, so they gray
 * by that slot and are never offered where nothing aims them (the palette, a chord).  The operation strip a parent
 * deformer's binding offers lands where the routing puts it, as the field's own edits do.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param RelationPickController relationPick The shell's relation-pick slot, which Pick arms.
 * @param CommandRouting routing Where the operation strip shows.
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun relationCommands(
	editorSession: EditorSession?,
	relationPick: RelationPickController,
	routing: CommandRouting,
	availability: SessionAvailability,
): List<Command> {
	/**
	 * The slot a row runs with, when there is a document to act in.
	 *
	 * @param Any? argument The row's argument.
	 * @return RelationSlot? The slot, or null.
	 */
	fun slotOf(argument: Any?): RelationSlot? = (argument as? RelationSlot)?.takeIf { editorSession != null }

	/**
	 * What the slot a row runs with binds now.
	 *
	 * @param Any? argument The row's argument.
	 * @return SelectionTarget? The bound object, or null.
	 */
	fun boundOf(argument: Any?): SelectionTarget? = slotOf(argument)?.let { slot -> editorSession?.model?.value?.relationBound(slot) }

	return commandTable(
		CommandTable.Relation,
		// Properties follows the selection to the bound object, and the outliner reveals its row.
		Command(
			"relation.select",
			title = Res.string.cmd_relation_select,
			availability = availability.hasDocument,
			availableFor = { argument -> boundOf(argument) != null },
			group = CommandSection.RelationSelect,
		) { argument ->
			boundOf(argument)?.let { bound -> editorSession?.setSelection(SelectionOps.replace(bound)) }
		},
		Command(
			"relation.clear",
			title = Res.string.cmd_relation_clear,
			availability = availability.hasDocument,
			availableFor = { argument -> (slotOf(argument) is RelationSlot.OwningPart || slotOf(argument) is RelationSlot.ParentDeformer) && boundOf(argument) != null },
			group = CommandSection.RelationBind,
		) { argument ->
			slotOf(argument)?.let { slot -> editorSession?.bindRelation(slot, null, routing.operationStripArea()) }
		},
		Command(
			"relation.pick",
			title = Res.string.cmd_relation_pick,
			availability = availability.hasDocument,
			availableFor = { argument -> slotOf(argument).let { slot -> slot != null && slot !is RelationSlot.Mask } },
			group = CommandSection.RelationBind,
		) { argument ->
			val slot = slotOf(argument)

			if (slot != null && editorSession != null) {
				relationPick.armFor(slot, editorSession) { routing.operationStripArea() }
			}
		},
		Command(
			"relation.remove",
			title = Res.string.cmd_relation_remove,
			availability = availability.hasDocument,
			availableFor = { argument -> slotOf(argument) is RelationSlot.Mask && boundOf(argument) != null },
			group = CommandSection.RelationRemove,
		) { argument ->
			slotOf(argument)?.let { slot -> editorSession?.bindRelation(slot, null, routing.operationStripArea()) }
		},
		// An unbound single relation copies the unbinding; a list row copies its entry, while the owner still holds it.
		Command(
			"relation.copyToSelected",
			title = Res.string.cmd_relation_copy_to_selected,
			availability = availability.hasDocument,
			availableFor = { argument ->
				val slot = slotOf(argument)
				val hasPeers = slot != null && editorSession != null && relationPeers(slot, editorSession.selection.value).isNotEmpty()

				when (slot) {
					is RelationSlot.OwningPart, is RelationSlot.ParentDeformer -> hasPeers
					is RelationSlot.Mask -> hasPeers && boundOf(argument) != null
					is RelationSlot.Masks, null -> false
				}
			},
			group = CommandSection.RelationCopy,
		) { argument ->
			slotOf(argument)?.let { slot -> editorSession?.copyRelationToSelected(slot, routing.operationStripArea()) }
		},
	)
}