package org.umamo.ui.action

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.resources.*

/**
 * A named set of commands the registry lists together ([CommandRegistry.inGroup]): a whole command table, a family
 * a menu, a pie, or a header control shows as one list, or a section of such a family.
 *
 * A command names the most specific group it belongs to and the rest follows from [parent], so a member of a section
 * is also in the section's family, and in that family's table, without saying so.  Whatever lists related commands asks the registry for their
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
			is CommandSection -> parent.parent
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

	/** The transform pivots, one member per pivot. */
	PivotMode(Res.string.pie_title_pivot, CommandTable.Snap),

	/** The 2D viewport's world-space snaps: the cursor's, then the selection's. */
	Snap(Res.string.pie_title_snap, CommandTable.Snap),

	/** The UV editor's texture-space snaps: the selection's, then the cursor's. */
	UvSnap(Res.string.pie_title_uv_snap, CommandTable.Uv),

	/** Where a merge collapses the selected vertices to. */
	MergeTarget(Res.string.pie_title_merge, CommandTable.Topology),

	/** Which of the posed warps' tracks an insert keys. */
	InsertKey(Res.string.pie_title_insert_key, CommandTable.Keyform),

	/** The element select modes, one member per mesh domain. */
	SelectMode(Res.string.status_select_mode, CommandTable.Select),

	/** The proportional-editing falloff curves, one member per curve. */
	ProportionalFalloff(Res.string.header_proportional_falloff, CommandTable.Proportional),

	/** The editor modes, one member per mode. */
	EditorMode(Res.string.group_editor_mode, CommandTable.Mode),
}

/**
 * A headless slice of a family: members a menu may set off from the rest of their family with a rule, where a pie
 * draws the family as one ring.  A family that has sections is exactly its sections' members, so its commands name
 * their section and never the family itself.
 *
 * @property CommandFamily parent The family the section is a slice of.
 */
enum class CommandSection(override val parent: CommandFamily) : CommandGroup {
	/** The world-space snaps that move the 2D cursor. */
	SnapCursor(CommandFamily.Snap),

	/** The world-space snaps that move the selection. */
	SnapSelection(CommandFamily.Snap),

	/** The texture-space snaps that move the selection. */
	UvSnapSelection(CommandFamily.UvSnap),

	/** The texture-space snaps that move the UV editor's cursor. */
	UvSnapCursor(CommandFamily.UvSnap),
}