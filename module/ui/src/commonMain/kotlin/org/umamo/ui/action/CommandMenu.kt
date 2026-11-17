package org.umamo.ui.action

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.menu.MenuItem

/*
 * Menus read from the command registry.  A command family is a menu: its sections are the menu's blocks, the
 * families that sit inside it are its submenus, and every row's label, glyph, accelerator, and grayed state come
 * from the row's command.  Nothing that shows a menu holds a row of its own, so the same family is the same menu on
 * the bar, under a pointer, or anywhere else it is named.
 *
 * A menu that cuts across families - what a right-click offers over a surface - is an arrangement instead: a
 * list of rows naming commands and families, built by the same code.  A command names one family, so such a menu
 * cannot be one, and what it holds is the choice and the order alone: every row is still read from its command.
 *
 * A menu is built in two steps, because the two halves of a row are known at different times.  Its labels are
 * resources, which only a composition can resolve, so they are resolved there along with which commands the registry
 * holds.  Whether a row is grayed is a live query the snapshot system cannot see, so it is asked as the menu opens,
 * by the builder the composition hands back.
 */

/** One row of an arranged menu ([rememberCommandMenu]). */
sealed interface CommandMenuRow {
	/**
	 * A command, by id, under its title.  Left out while the command is not registered.
	 *
	 * @property String commandId The command.
	 */
	data class Single(val commandId: String) : CommandMenuRow

	/**
	 * A family's or a section's rows, inline: a section's own commands and submenus, or a family's whole menu with
	 * its rules.  Each row goes by its label in the family, so this belongs where that label reads on its own
	 * ("Mirror X") or under the family's heading.
	 *
	 * @property CommandListing group The family or the section.
	 * @property Any? argument What every row runs its command with, for a menu opened on one thing among several
	 *   (a workspace's tab), or null to run each with none.  A row run with an argument shows no accelerator: the
	 *   chord runs the command with none, which may not be the same thing.
	 */
	data class Members(val group: CommandListing, val argument: Any? = null) : CommandMenuRow

	/**
	 * A family as a submenu: its whole menu, under its heading.  Left out while none of its commands is registered.
	 *
	 * @property CommandFamily family The family.
	 * @property String? openerId The command that opens the family as a pie, whose title then labels the submenu and
	 *   without which the row is left out; null for a family nothing opens, which goes under its own heading.
	 */
	data class Family(val family: CommandFamily, val openerId: String? = null) : CommandMenuRow

	/**
	 * A named run of rows as a submenu.  Left out when none of its rows is there.
	 *
	 * @property StringResource label The submenu's label.
	 * @property List<CommandMenuRow> rows The rows inside it.
	 */
	data class Submenu(val label: StringResource, val rows: List<CommandMenuRow>) : CommandMenuRow

	/** A rule between groups.  One left at an end, or beside another, by rows that are not there is dropped. */
	data object Separator : CommandMenuRow
}

/** One row of a menu as the composition resolves it: what it shows, with what it does still to be asked. */
private sealed interface ResolvedRow {
	/**
	 * A command under its label.
	 *
	 * @property Command command The command the row stands for.
	 * @property String label The resolved label.
	 * @property Any? argument What the row runs the command with, or null for none.
	 */
	data class Of(val command: Command, val label: String, val argument: Any? = null) : ResolvedRow

	/**
	 * A family as a submenu.
	 *
	 * @property String label The resolved heading.
	 * @property List<ResolvedRow> rows The family's own rows.
	 */
	data class Submenu(val label: String, val rows: List<ResolvedRow>) : ResolvedRow

	/** A rule between two blocks. */
	data object Rule : ResolvedRow
}

/**
 * The menu [family] stands for, as the builder to call when the menu opens.
 *
 * The family's sections are its blocks, in the order they are declared and with a rule before each that asks for one;
 * a family with no sections is one block.  A block lists its own commands, in their table's order, then the families
 * that sit inside it, each a submenu under its heading.  A block nothing is registered in is left out with its rule,
 * and so is a submenu with no row.
 *
 * The composition calling this is subscribed to the registry, so a table registered later reaches a menu built before
 * it.
 *
 * @param CommandFamily family The family the menu stands for.
 * @return Function The builder: the menu's entries, with each row's grayed state as it is when called.
 */
@Composable
internal fun rememberFamilyMenu(family: CommandFamily): () -> List<MenuItem> {
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	val rows = familyRows(family, commands)

	return remember(rows, commands, keymap) { { rows.map { row -> menuItemOf(row, commands, keymap) } } }
}

/**
 * An arranged menu, as the builder to call when the menu opens: [rows] top to bottom, each read from its command.
 *
 * The composition calling this is subscribed to the registry, as [rememberFamilyMenu]'s is.
 *
 * @param List<CommandMenuRow> rows The menu's rows.
 * @return Function The builder: the menu's entries, with each row's grayed state as it is when called.
 */
@Composable
internal fun rememberCommandMenu(rows: List<CommandMenuRow>): () -> List<MenuItem> {
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	val resolved = arrangedRows(rows, commands)

	return remember(resolved, commands, keymap) { { resolved.map { row -> menuItemOf(row, commands, keymap) } } }
}

/**
 * The resolved rows of an arrangement, with the rules its absent rows left stranded taken out.
 *
 * @param List<CommandMenuRow> rows The arrangement.
 * @param CommandRegistry commands The registry the rows are read from.
 * @return List<ResolvedRow> The rows, top to bottom.
 */
@Composable
private fun arrangedRows(rows: List<CommandMenuRow>, commands: CommandRegistry): List<ResolvedRow> = withoutStrayRules(rows.flatMap { row -> arrangedRow(row, commands) })

/**
 * What one row of an arrangement resolves to: nothing while what it names is not registered, several rows for a
 * family listed inline.
 *
 * @param CommandMenuRow row The row.
 * @param CommandRegistry commands The registry the row is read from.
 * @return List<ResolvedRow> The resolved rows.
 */
@Composable
private fun arrangedRow(row: CommandMenuRow, commands: CommandRegistry): List<ResolvedRow> =
	when (row) {
		is CommandMenuRow.Single -> {
			listOfNotNull(commands[row.commandId]?.let { command -> ResolvedRow.Of(command, command.title?.let { title -> stringResource(title) } ?: command.id) })
		}

		is CommandMenuRow.Members -> {
			val members =
				when (val group = row.group) {
					is CommandFamily -> familyRows(group, commands)
					is CommandSection -> listingRows(group, commands)
				}

			if (row.argument == null) {
				members
			} else {
				runWith(row.argument, members)
			}
		}

		is CommandMenuRow.Family -> {
			val label =
				if (row.openerId == null) {
					stringResource(row.family.heading)
				} else {
					commands[row.openerId]?.let { opener -> opener.title?.let { title -> stringResource(title) } ?: opener.id }
				}
			val familyRows = familyRows(row.family, commands)

			if (label == null || familyRows.isEmpty()) {
				emptyList()
			} else {
				listOf(ResolvedRow.Submenu(label, familyRows))
			}
		}

		is CommandMenuRow.Submenu -> {
			val nested = arrangedRows(row.rows, commands)

			if (nested.isEmpty()) {
				emptyList()
			} else {
				listOf(ResolvedRow.Submenu(stringResource(row.label), nested))
			}
		}

		CommandMenuRow.Separator -> {
			listOf(ResolvedRow.Rule)
		}
	}

/**
 * [rows] with every command row, at any depth, set to run its command with [argument].
 *
 * @param Any argument The argument.
 * @param List<ResolvedRow> rows The rows.
 * @return List<ResolvedRow> The rows, each command row carrying the argument.
 */
private fun runWith(argument: Any, rows: List<ResolvedRow>): List<ResolvedRow> =
	rows.map { row ->
		when (row) {
			is ResolvedRow.Of -> row.copy(argument = argument)
			is ResolvedRow.Submenu -> row.copy(rows = runWith(argument, row.rows))
			ResolvedRow.Rule -> row
		}
	}

/**
 * [rows] without the rules that separate nothing: one at either end, and all but the first of a run.
 *
 * @param List<ResolvedRow> rows The rows.
 * @return List<ResolvedRow> The rows, every rule left between two rows.
 */
private fun withoutStrayRules(rows: List<ResolvedRow>): List<ResolvedRow> {
	val kept = ArrayList<ResolvedRow>()

	for (row in rows) {
		if (row == ResolvedRow.Rule && (kept.isEmpty() || kept.last() == ResolvedRow.Rule)) {
			continue
		}

		kept.add(row)
	}

	if (kept.lastOrNull() == ResolvedRow.Rule) {
		kept.removeAt(kept.lastIndex)
	}

	return kept
}

/**
 * The rows of [family]'s menu, block by block, with a rule between two blocks that both hold a row.
 *
 * @param CommandFamily family The family.
 * @param CommandRegistry commands The registry the family's members are listed from.
 * @return List<ResolvedRow> The rows, top to bottom.
 */
@Composable
private fun familyRows(family: CommandFamily, commands: CommandRegistry): List<ResolvedRow> {
	val rows = ArrayList<ResolvedRow>()

	for (block in blocksOf(family)) {
		val blockRows = block.flatMap { listing -> listingRows(listing, commands) }

		if (blockRows.isEmpty()) {
			continue
		}

		if (rows.isNotEmpty()) {
			rows.add(ResolvedRow.Rule)
		}

		rows.addAll(blockRows)
	}

	return rows
}

/**
 * The blocks of [family]'s menu: its sections in declared order, each one that asks for a rule opening a block and
 * each one that does not joining the block before it.  A family with no sections is one block, itself.
 *
 * @param CommandFamily family The family.
 * @return List<List<CommandListing>> The blocks, each the listings whose rows it holds, in order.
 */
private fun blocksOf(family: CommandFamily): List<List<CommandListing>> {
	val sections = CommandSection.entries.filter { section -> section.parent == family }

	if (sections.isEmpty()) {
		return listOf(listOf(family))
	}

	val blocks = ArrayList<ArrayList<CommandListing>>()

	for (section in sections) {
		if (section.ruled || blocks.isEmpty()) {
			blocks.add(ArrayList())
		}

		blocks.last().add(section)
	}

	return blocks
}

/**
 * The rows [listing] itself holds: its own commands, each under its row label, then the families that sit inside it
 * as submenus, in the order they are declared.
 *
 * @param CommandListing listing A family with no sections, or a section.
 * @param CommandRegistry commands The registry the commands are listed from.
 * @return List<ResolvedRow> The rows; none while nothing under the listing is registered.
 */
@Composable
private fun listingRows(listing: CommandListing, commands: CommandRegistry): List<ResolvedRow> {
	val own =
		commands.membersOf(listing).map { command ->
			ResolvedRow.Of(command, command.rowLabel?.let { label -> stringResource(label) } ?: command.id)
		}
	val submenus =
		CommandFamily.entries.filter { nested -> nested.parent == listing }.mapNotNull { nested ->
			val nestedRows = familyRows(nested, commands)

			if (nestedRows.isEmpty()) {
				null
			} else {
				ResolvedRow.Submenu(stringResource(nested.heading), nestedRows)
			}
		}

	return own + submenus
}

/**
 * The menu entry [row] stands for, with its grayed state as it is now.
 *
 * @param ResolvedRow row The row.
 * @param CommandRegistry commands The registry the row dispatches through.
 * @param Keymap keymap The keymap the accelerator is read from.
 * @return MenuItem The entry.
 */
private fun menuItemOf(
	row: ResolvedRow,
	commands: CommandRegistry,
	keymap: Keymap,
): MenuItem =
	when (row) {
		is ResolvedRow.Of -> {
			commandMenuItem(row.command, row.label, commands, keymap, row.argument)
		}

		is ResolvedRow.Submenu -> {
			val items = row.rows.map { nested -> menuItemOf(nested, commands, keymap) }

			MenuItem.Submenu(label = row.label, items = items, enabled = items.any { item -> item.isPickable() })
		}

		ResolvedRow.Rule -> {
			MenuItem.Separator
		}
	}

/**
 * The menu entry that stands for [command]: built from the command alone, so it can never run one command and show
 * another's accelerator, glyph, or grayed state.
 *
 * A command offered with a list of arguments is a submenu of them, a row apiece under the argument's own label, and
 * cannot open while the list is empty.  Any other command is one row, with its glyph and whatever chord the keymap
 * binds to it - unless the row runs it with an argument, since the chord runs it with none.
 *
 * @param Command command The command the entry stands for.
 * @param String label The entry's label, already resolved.
 * @param CommandRegistry commands The registry the entry dispatches through.
 * @param Keymap keymap The keymap the accelerator is read from.
 * @param Any? argument What the entry runs the command with, or null for none.
 * @param Function around Runs the dispatch it is handed, for a site that has to set something up for the command to
 *   read and clear it after: a menu owns the pointer by the time a row is picked, so a command that reads what the
 *   pointer was over needs that published again around its run.
 * @return MenuItem The entry, grayed when the command does not apply as this is called.
 */
internal fun commandMenuItem(
	command: Command,
	label: String,
	commands: CommandRegistry,
	keymap: Keymap,
	argument: Any? = null,
	around: (dispatch: () -> Unit) -> Unit = { dispatch -> dispatch() },
): MenuItem {
	val available = command.availability.isAvailable()
	val choices = command.choices

	if (choices != null) {
		val items = choices.list().map { choice -> MenuItem.Action(label = choice.label, onSelect = { around { commands.invoke(command.id, choice.argument) } }) }

		return MenuItem.Submenu(label = label, items = items, enabled = available && items.isNotEmpty())
	}

	return MenuItem.Action(
		label = label,
		onSelect = { around { commands.invoke(command.id, argument) } },
		shortcut = if (argument == null) command.accelerator(keymap) else null,
		enabled = available,
		icon = command.icon,
	)
}

/**
 * Whether this entry, or a row somewhere under it, can be picked.
 *
 * @return Boolean True when it can.
 */
private fun MenuItem.isPickable(): Boolean =
	when (this) {
		is MenuItem.Action -> enabled
		is MenuItem.Submenu -> enabled
		is MenuItem.Heading, is MenuItem.Search, MenuItem.Separator -> false
	}