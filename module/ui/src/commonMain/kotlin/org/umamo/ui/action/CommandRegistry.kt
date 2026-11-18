package org.umamo.ui.action

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The central table of named [Command]s and the single dispatch point for invoking them.  Menus, the
 * area header, the command palette, and the keymap all go through here, so an operation has exactly
 * one implementation regardless of how it was triggered.
 *
 * Insertion order is preserved (a LinkedHashMap) so the command palette can list commands in a
 * stable, registration order rather than a hash-shuffled one.
 *
 * It is also the one place a set of related commands is read from: [inGroup] lists a table, a family, or a
 * section of one, so nothing that shows such a set keeps a list of its own.
 */
class CommandRegistry {
	private val commandsById = LinkedHashMap<String, Command>()

	/**
	 * Counts every change to the table, as snapshot state - what a composable listing [all] reads so that
	 * it recomposes when the table changes.
	 *
	 * The table itself is a plain map, and the document-scoped groups re-register from effects that run
	 * AFTER the composition a document swap caused.  Without this, a listing recomposed by that swap
	 * reads the outgoing document's commands - whose availability still answers for the old session - and
	 * nothing recomposes it again once the new ones land.
	 */
	var revision: Int by mutableIntStateOf(0)
		private set

	/**
	 * Registers (or replaces) a command under its [Command.id].
	 *
	 * @param Command command The command to register.
	 */
	fun register(command: Command) {
		commandsById[command.id] = command
		revision++
	}

	/**
	 * Removes a command by id, if present.
	 *
	 * @param String id The command id to remove.
	 */
	fun unregister(id: String) {
		if (commandsById.remove(id) != null) {
			revision++
		}
	}

	/**
	 * Looks up a command by id without invoking it (e.g. to read its title for a menu).
	 *
	 * Subscribes its reader to [revision], as [inGroup] does: a row or a button standing for one command is composed
	 * before the shell's effects have registered it, and has to come back when they do.
	 *
	 * @param String id The command id.
	 * @return Command? The command, or null if none is registered.
	 */
	operator fun get(id: String): Command? {
		observeTable()

		return commandsById[id]
	}

	/**
	 * Every registered command in registration order - the source for the command palette listing.
	 *
	 * @return List the registered commands.
	 */
	fun all(): List<Command> = commandsById.values.toList()

	/**
	 * The registered commands within [group] - what a menu, a pie, or a header control listing related commands
	 * reads, so the tables that register them decide which they are and in what order.
	 *
	 * A table and a section list in registration order.  A family lists section by section, in the order its
	 * sections are declared, and in registration order inside each: a family may draw from tables that register at
	 * different times, and its order must not follow whichever happened to come first.
	 *
	 * Subscribes its reader to [revision], so a composable listing a group recomposes when the table changes
	 * without keying on the revision itself.
	 *
	 * @param CommandGroup group The table, the family, or the section to list.
	 * @return List<Command> The group's registered commands; empty while none of them is registered.
	 */
	fun inGroup(group: CommandGroup): List<Command> {
		observeTable()

		val listed = commandsById.values.filter { command -> command.table == group || command.group?.isWithin(group) == true }

		return if (group is CommandFamily) {
			// A stable sort, so each section keeps the order its table registered it in.
			listed.sortedBy { command -> command.group?.sectionIn(group)?.ordinal ?: UNSECTIONED }
		} else {
			listed
		}
	}

	/**
	 * The registered commands that name [group] itself, in registration order: a family's or a section's own rows,
	 * without the members of any family that sits inside it.
	 *
	 * Subscribes its reader to [revision], as [inGroup] does.
	 *
	 * @param CommandListing group The family or the section whose own commands are listed.
	 * @return List<Command> The commands naming [group]; empty while none of them is registered.
	 */
	fun membersOf(group: CommandListing): List<Command> {
		observeTable()

		return commandsById.values.filter { command -> command.group == group }
	}

	/**
	 * Reads [revision], which subscribes whatever is reading - a composition, a derived state - to the table.
	 *
	 * @return Int The revision read.
	 */
	private fun observeTable(): Int = revision

	/**
	 * Invokes the command bound to [id], passing [argument] to its handler.
	 *
	 * Returns whether a command was found AND currently applicable, rather than throwing, so a stray
	 * key chord or a menu item whose command was unregistered fails quietly instead of crashing the
	 * editor - and a chord bound to a command outside its context (an Edit-mode operator pressed in
	 * Object mode) falls through unconsumed rather than running a guaranteed no-op.
	 *
	 * @param String id The command id to invoke.
	 * @param Any? argument Optional context forwarded to the handler (default null).
	 * @return Boolean true if a command was found, applicable, and run; false otherwise.
	 */
	fun invoke(id: String, argument: Any? = null): Boolean {
		val command = commandsById[id] ?: return false

		if (!command.isAvailable(argument)) {
			return false
		}

		command.handler.run(argument)

		return true
	}

	private companion object {
		/** Where a family lists a command that sits in none of its sections: ahead of every section. */
		const val UNSECTIONED = -1
	}
}

/**
 * The active [CommandRegistry] for the composition.  `static` because the registry instance is stable
 * for the app's lifetime (commands are registered into it, not swapped); descendants read
 * `LocalCommands.current` to dispatch.  The default errors loudly so a missing provider is caught at
 * the first dispatch rather than silently no-op'ing.
 */
val LocalCommands =
	staticCompositionLocalOf<CommandRegistry> {
		error("LocalCommands not provided — wrap the editor in a CommandRegistry provider")
	}