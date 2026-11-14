package org.umamo.ui.action

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.menu.MenuItem

/*
 * Menus read from the command registry.  A command family is a menu: its sections are the menu's blocks, the
 * families that sit inside it are its submenus, and every row's label, glyph, accelerator, and grayed state come
 * from the row's command.  Nothing that shows a menu holds a row of its own, so the same family is the same menu on
 * the bar, under a pointer, or anywhere else it is named.
 *
 * A menu is built in two steps, because the two halves of a row are known at different times.  Its labels are
 * resources, which only a composition can resolve, so they are resolved there along with which commands the registry
 * holds.  Whether a row is grayed is a live query the snapshot system cannot see, so it is asked as the menu opens,
 * by the builder the composition hands back.
 */

/** One row of a menu as the composition resolves it: what it shows, with what it does still to be asked. */
private sealed interface ResolvedRow {
	/**
	 * A command under its label.
	 *
	 * @property Command command The command the row stands for.
	 * @property String label The resolved label.
	 */
	data class Of(val command: Command, val label: String) : ResolvedRow

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
			commandMenuItem(row.command, row.label, commands, keymap)
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
 * binds to it.
 *
 * @param Command command The command the entry stands for.
 * @param String label The entry's label, already resolved.
 * @param CommandRegistry commands The registry the entry dispatches through.
 * @param Keymap keymap The keymap the accelerator is read from.
 * @return MenuItem The entry, grayed when the command does not apply as this is called.
 */
internal fun commandMenuItem(
	command: Command,
	label: String,
	commands: CommandRegistry,
	keymap: Keymap,
): MenuItem {
	val available = command.availability.isAvailable()
	val choices = command.choices

	if (choices != null) {
		val items = choices.list().map { choice -> MenuItem.Action(label = choice.label, onSelect = { commands.invoke(command.id, choice.argument) }) }

		return MenuItem.Submenu(label = label, items = items, enabled = available && items.isNotEmpty())
	}

	return MenuItem.Action(
		label = label,
		onSelect = { commands.invoke(command.id) },
		shortcut = keymap.chordFor(command.id)?.let { chord -> formatAccelerator(chord) },
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