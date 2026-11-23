package org.umamo.ui.properties

import org.umamo.edit.relation.RelationSlot
import org.umamo.ui.action.CommandMenuRow
import org.umamo.ui.action.CommandSection

/*
 * The context menus of the relation fields: their rows as the registry lists them, each aimed at the field's slot,
 * so the tables decide what each row does and when it grays, and the field only says which slot it is.
 */

/**
 * A single relation field's menu: Select what it binds, then Clear and Pick, then Copy to Selected.
 *
 * @param RelationSlot slot The field's relation.
 * @return List<CommandMenuRow> The rows.
 */
internal fun relationFieldMenu(slot: RelationSlot): List<CommandMenuRow> =
	listOf(
		CommandMenuRow.Members(CommandSection.RelationSelect, argument = slot),
		CommandMenuRow.Separator,
		CommandMenuRow.Members(CommandSection.RelationBind, argument = slot),
		CommandMenuRow.Separator,
		CommandMenuRow.Members(CommandSection.RelationCopy, argument = slot),
	)

/**
 * One relation list row's menu: Select the entry, Remove it, then Copy to Selected.
 *
 * @param RelationSlot.Mask slot The row's relation.
 * @return List<CommandMenuRow> The rows.
 */
internal fun relationEntryMenu(slot: RelationSlot.Mask): List<CommandMenuRow> =
	listOf(
		CommandMenuRow.Members(CommandSection.RelationSelect, argument = slot),
		CommandMenuRow.Members(CommandSection.RelationRemove, argument = slot),
		CommandMenuRow.Separator,
		CommandMenuRow.Members(CommandSection.RelationCopy, argument = slot),
	)