package org.umamo.ui.action

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.workspace.SpaceScope

/**
 * Receives a command invocation.  A `fun interface` (Kotlin SAM) so call sites can pass a lambda -
 * `CommandHandler { argument -> … }` - instead of an anonymous object.
 */
fun interface CommandHandler {
	/**
	 * Runs the command.
	 *
	 * @param Any? argument Optional context the caller passes (e.g. a target area id); null for nullary commands.
	 */
	fun run(argument: Any?)
}

/**
 * Reports whether a command currently applies - the context filter behind the command palette (an
 * Edit-mode select-mode command is hidden in Object mode) and the dispatch guard in
 * [CommandRegistry.invoke].  A `fun interface` so registration sites pass a lambda closing over live
 * session state, evaluated fresh at each query rather than captured once.
 */
fun interface CommandAvailability {
	/**
	 * Reports whether the command currently applies.
	 *
	 * @return Boolean True when the command may be shown and invoked.
	 */
	fun isAvailable(): Boolean

	companion object {
		/** The default availability: always applicable (context-free commands like palette.toggle). */
		val Always: CommandAvailability = CommandAvailability { true }
	}
}

/**
 * Reports whether a command applies when run with a given argument.
 *
 * A `fun interface` so a table passes a lambda reading live state, evaluated fresh at each query.
 */
fun interface CommandArgumentAvailability {
	/**
	 * Reports whether the command applies when run with [argument].
	 *
	 * @param Any? argument What the command would run with, or null for none (a chord, the palette).
	 * @return Boolean True when it may be shown enabled and invoked so.
	 */
	fun isAvailableFor(argument: Any?): Boolean
}

/**
 * Reports whether a command is the one in effect: the pivot that is current, the select mode in use.  It is what a
 * control listing a family lights, and whose glyph its chip wears, without knowing what the family sets.
 *
 * A `fun interface` so a table passes a lambda closing over live session state, evaluated fresh at each query.
 * Like [CommandAvailability] it is a query the snapshot system cannot see, so a control that shows it also observes
 * whatever it reads.
 */
fun interface CommandState {
	/**
	 * Reports whether the command is the one in effect.
	 *
	 * @return Boolean True while it is.
	 */
	fun isOn(): Boolean
}

/**
 * Lists the arguments a command is offered with, where it is run with one of several: Open Recent's row per file.  A
 * menu shows such a command as a submenu of them, so the list is part of the command and any surface showing it
 * offers the same rows.
 *
 * A `fun interface` so a table passes a lambda reading live state, evaluated fresh each time a menu opens.
 */
fun interface CommandChoices {
	/**
	 * Lists the arguments on offer.
	 *
	 * @return List<CommandChoice> One entry per row, in the order shown; empty when there is nothing to offer.
	 */
	fun list(): List<CommandChoice>
}

/**
 * One argument a command is offered with.
 *
 * @property String label The row's label, shown as it is: user data such as a file's name, never a resource.
 * @property Any? argument What the command is run with when the row is picked.
 */
data class CommandChoice(val label: String, val argument: Any?)

/**
 * A named, invokable editor operation - the atom the action registry dispatches.  Every operation
 * (split an area, open a file, fit the view) is one of these, addressed by a stable dotted [id]
 * ("area.split.leftRight", "file.open").  Menus, the area header, the command palette, and key
 * bindings all resolve to a Command and run its [handler]; nothing hardcodes a handler, so rebinding
 * a key or relabeling a menu changes one place.
 *
 * @property String id The stable dotted identifier used to look the command up and to bind keys to it.
 * @property StringResource? title The localized menu/palette label, or null for commands never shown in UI (e.g. test-only).
 * @property CommandAvailability availability Whether the command currently applies (palette filter +
 *   dispatch guard); defaults to always.
 * @property CommandArgumentAvailability? availableFor Whether it applies run with a given argument, on top of
 *   [availability], or null for a command whose argument never changes that.  Asked with null for a chord, the
 *   palette, and the status bar, so a command that only makes sense with an argument answers false there.
 * @property SpaceScope spaces The editor spaces the command belongs to (palette filter + status bar
 *   suggestion, never a dispatch guard); defaults to everywhere.  Read off what the handler's routing
 *   actually does, never narrower: a handler that reaches back to a surface the pointer has left (the lone
 *   open keyform sheet, the last work surface touched) works from anywhere, and so it is
 *   [SpaceScope.Everywhere].  Narrowing a scope past its handler hides a command that would have run.
 * @property CommandHint? hint What the status bar suggests the command under and when, or null for a
 *   command it never suggests.  A hinted command's [availability] must depend on the document and the
 *   mode alone - see [CommandHint] for why.
 * @property CommandTable? table The table that registers the command, placed on it by the table itself (commandTable),
 *   or null for one built outside a table.
 * @property CommandListing? group The family, or the section of one, the command is shown in (a listing filter, never
 *   a dispatch guard), or null for a command no family shows.  It says nothing of the table: a family draws its
 *   members from whichever tables hold them.
 * @property StringResource? groupLabel The label of the command's row under its family's heading, where the heading
 *   already says what the family does ("Parts" under "Select All of Type"), or null to show the [title] there too.
 * @property UmamoIcon? icon The command's glyph wherever a pie slot, a menu row, or a chip stands for it, or null
 *   for a command with none drawn.
 * @property CommandState? state Whether the command is the one in effect, which a control listing its family lights
 *   (never a dispatch guard), or null for a command that is never "the current one".  Its table decides what it
 *   reports with no document open, so a control shows the same face before the first file opens.
 * @property CommandChoices? choices The arguments the command is offered with, a row apiece, or null for a command
 *   that is one row.  Never read by dispatch: the handler takes whatever argument it is run with.
 * @property CommandHandler handler The work to perform when invoked.
 */
data class Command(
	val id: String,
	val title: StringResource?,
	val availability: CommandAvailability = CommandAvailability.Always,
	val availableFor: CommandArgumentAvailability? = null,
	val spaces: SpaceScope = SpaceScope.Everywhere,
	val hint: CommandHint? = null,
	val table: CommandTable? = null,
	val group: CommandListing? = null,
	val groupLabel: StringResource? = null,
	val icon: UmamoIcon? = null,
	val state: CommandState? = null,
	val choices: CommandChoices? = null,
	val handler: CommandHandler,
) {
	init {
		require(groupLabel == null || group != null) { "Command $id has a group label and no group to show it under" }
		// A command no family shows may go unlabeled (the palette hides it); a family is a list of rows.
		require(group == null || rowLabel != null) { "Command $id is a member of $group with no label for its row" }
	}

	/** The label of the command's row in a listing of its group: its [groupLabel], else its [title]. */
	val rowLabel: StringResource? get() = groupLabel ?: title

	/**
	 * Whether the command applies when run with [argument]: its [availability], and its [availableFor] over that
	 * argument.
	 *
	 * @param Any? argument What it would run with, or null for none.
	 * @return Boolean True when it may be shown enabled and invoked so.
	 */
	fun isAvailable(argument: Any? = null): Boolean = availability.isAvailable() && availableFor?.isAvailableFor(argument) != false

	/**
	 * Reports whether the command is the one in effect.
	 *
	 * @return Boolean True while its [state] reports on; false for a command that reports no state.
	 */
	fun isOn(): Boolean = state?.isOn() == true
}