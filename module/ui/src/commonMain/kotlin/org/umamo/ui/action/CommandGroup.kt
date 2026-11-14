package org.umamo.ui.action

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.resources.*

/**
 * A named set of commands the registry lists together ([CommandRegistry.inGroup]): a whole command table, or a
 * family a menu, a pie, or a header control shows as one list.
 *
 * A command names the most specific group it belongs to and the rest follows from [parent], so a member of a family
 * is also in the family's table without saying so.  Whatever lists related commands asks the registry for their
 * group, which leaves the table that registers them as the one place deciding which they are and in what order.
 */
sealed interface CommandGroup {
	/** The group this one sits inside, or null for a table, which sits inside nothing. */
	val parent: CommandGroup?
}

/**
 * The table this group belongs to: itself for a table, else the table its family was declared in.
 */
val CommandGroup.table: CommandTable
	get() =
		when (this) {
			is CommandTable -> this
			is CommandFamily -> parent
		}

/**
 * Reports whether this group is [ancestor] or sits somewhere inside it.
 *
 * @param CommandGroup ancestor The group to look for up the parent chain.
 * @return Boolean True when this group is [ancestor] or one of its descendants.
 */
fun CommandGroup.isWithin(ancestor: CommandGroup): Boolean = this == ancestor || parent?.isWithin(ancestor) == true

/**
 * Everything one command table registers: one entry per table file in the command tables package, named after it.
 * A table function builds its list through commandTable, which is what places its commands here.
 */
enum class CommandTable : CommandGroup {
	Atlas,
	Chrome,
	Display,
	Document,
	File,
	History,
	Keyform,
	Lattice,
	Log,
	MeshGeneration,
	Mode,
	Object,
	Overlay,
	Proportional,
	Select,
	Sidebar,
	Snap,
	Topology,
	Transform,
	Update,
	Uv,
	View,
	ViewportChrome,
	Workspace,
	;

	override val parent: CommandGroup? get() = null
}

/**
 * A headed list of commands a UI shows together, inside one table.  The members' order is the order their table
 * registers them in, and each is labeled by its [Command.rowLabel].
 *
 * @property StringResource heading The label over the family wherever it is shown: a submenu's row, a pie's title.
 * @property CommandTable parent The table whose commands the family is drawn from.
 */
enum class CommandFamily(val heading: StringResource, override val parent: CommandTable) : CommandGroup {
	/** Select All of Type: one member per selectable object kind. */
	SelectAllOfKind(Res.string.menu_select_all_of_type, CommandTable.Select),

	/** Invert Selection of Type: one member per selectable object kind. */
	InvertSelectionOfKind(Res.string.menu_invert_selection_of_type, CommandTable.Select),
}