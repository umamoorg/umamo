package org.umamo.ui.action

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.resources.*

/**
 * A named set of commands the registry lists together ([CommandRegistry.inGroup]): everything one command table
 * registers, a family a menu, a pie, or a header control shows as one list, or a section of such a family.
 *
 * Two things are said of a command, and they are independent.  Its table is who registers it, which follows the
 * state its handler closes over.  Its listing is where it is shown, which follows what the rigger sees together: a
 * menu draws its rows from as many tables as it needs.
 */
sealed interface CommandGroup {
	/** The group this one sits inside, or null for one that sits inside nothing. */
	val parent: CommandGroup?
}

/**
 * A group a command names as the place it is shown ([Command.group]): a family, or a section of one.  A table is
 * never named - a command is in its table by being registered through it.
 *
 * A command names only the most specific listing it belongs to and the rest follows from [parent], so a member of a
 * section is also in the section's family, and in whatever that family is a submenu of, without saying so.
 */
sealed interface CommandListing : CommandGroup {
	override val parent: CommandListing?
}

/**
 * Reports whether this group is [ancestor] or sits somewhere inside it.
 *
 * @param CommandGroup ancestor The group to look for up the parent chain.
 * @return Boolean True when this group is [ancestor] or one of its descendants.
 */
fun CommandGroup.isWithin(ancestor: CommandGroup): Boolean = this == ancestor || parent?.isWithin(ancestor) == true

/**
 * The section of [family] this listing is shown in: itself when it is one of the family's sections, else the section
 * a family it sits inside is a submenu of.
 *
 * @param CommandFamily family The family whose sections are looked through.
 * @return CommandSection? The section, or null for a listing directly in [family] or outside it.
 */
internal fun CommandListing.sectionIn(family: CommandFamily): CommandSection? {
	var listing: CommandListing? = this

	while (listing != null) {
		if (listing is CommandSection && listing.parent == family) {
			return listing
		}

		listing = listing.parent
	}

	return null
}

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
 * A headed list of commands a UI shows together: a menu, a submenu, a pie, a row of buttons.  Each member is labeled
 * by its [Command.rowLabel].
 *
 * A family that sits inside another is that one's submenu, and one that sits inside a section is a submenu in that
 * block of it.  A family with neither is a root: a menu of the bar, a pie, a header control.
 *
 * A family's own commands - the ones that name it, or one section of it - are registered by one table function and
 * listed in that function's order.  What spans tables is the family as a whole, section by section.
 *
 * @property StringResource heading The label over the family wherever it is shown: a menu's name, a submenu's row, a
 *   pie's title.
 * @property Function parentOf Names the listing the family sits inside.  Read on first use and never while the entries
 *   are built, since a section names its family and a family may name a section.
 */
enum class CommandFamily(val heading: StringResource, private val parentOf: () -> CommandListing? = { null }) : CommandListing {
	/** The File menu: the puppet's own file, with the Import and Export submenus, then leaving the application. */
	File(Res.string.menu_file),

	/** File > Import: what comes in from another format, artwork first. */
	FileImport(Res.string.menu_import, { CommandSection.FileDocument }),

	/** File > Export: what leaves for another format. */
	FileExport(Res.string.menu_export, { CommandSection.FileDocument }),

	/** The Edit menu: the undo history, then the settings. */
	Edit(Res.string.menu_edit),

	/** The Select menu: the selection as a whole, then the by-kind submenus. */
	Select(Res.string.menu_select),

	/** Select All of Type: one member per selectable object kind. */
	SelectAllOfKind(Res.string.menu_select_all_of_type, { CommandSection.SelectByKind }),

	/** Invert Selection of Type: one member per selectable object kind. */
	InvertSelectionOfKind(Res.string.menu_invert_selection_of_type, { CommandSection.SelectByKind }),

	/** The Workspace menu: moving between workspaces, making and resetting them, and their trips to and from a file. */
	Workspace(Res.string.menu_workspace),

	/** The Help menu: the project's links, setup and upkeep, and what Umamo is. */
	Help(Res.string.menu_help),

	/** The transform pivots, one member per pivot. */
	PivotMode(Res.string.pie_title_pivot),

	/** The 2D viewport's world-space snaps: the cursor's, then the selection's. */
	Snap(Res.string.pie_title_snap),

	/** The UV editor's texture-space snaps: the selection's, then the cursor's. */
	UvSnap(Res.string.pie_title_uv_snap),

	/** The UV editor's mirrors, one member per axis. */
	UvMirror(Res.string.group_uv_mirror),

	/** Where a merge collapses the selected vertices to. */
	MergeTarget(Res.string.pie_title_merge),

	/** Which of the posed warps' tracks an insert keys. */
	InsertKey(Res.string.pie_title_insert_key),

	/** Inserting and deleting the key under the pointer: the pair every keyable row and lane offers. */
	KeyformKey(Res.string.group_keyform_key),

	/** The element select modes, one member per mesh domain. */
	SelectMode(Res.string.status_select_mode),

	/** The proportional-editing falloff curves, one member per curve. */
	ProportionalFalloff(Res.string.header_proportional_falloff),

	/** The editor modes, one member per mode. */
	EditorMode(Res.string.group_editor_mode),
	;

	override val parent: CommandListing? get() = parentOf()
}

/**
 * A headless slice of a family, and the unit a family's order is declared in: a family lists its sections in the
 * order they are declared here, whichever table registered first.  A menu sets a section off from the one before it
 * with a rule, where a pie draws the family as one ring.
 *
 * A family that has sections is exactly its sections' members, so its commands name their section and never the
 * family itself.
 *
 * @property CommandFamily parent The family the section is a slice of.
 * @property Boolean ruled Whether a menu puts a rule before the section.  False joins it to the block before it, which
 *   is how one block draws from more than one table function and still has a declared order.
 */
enum class CommandSection(override val parent: CommandFamily, val ruled: Boolean = true) : CommandListing {
	/** The puppet's own file: New, Open, Open Recent, the saves, and the Import and Export submenus. */
	FileDocument(CommandFamily.File),

	/** Leaving the application. */
	FileSession(CommandFamily.File),

	/** The import that adds artwork to the open puppet. */
	ImportArtwork(CommandFamily.FileImport),

	/** The imports that open another format as a puppet. */
	ImportPuppet(CommandFamily.FileImport, ruled = false),

	/** The exports of the puppet to another format. */
	ExportPuppet(CommandFamily.FileExport),

	/** The export of the framed view as an image. */
	ExportImage(CommandFamily.FileExport, ruled = false),

	/** Undo and Redo. */
	EditHistory(CommandFamily.Edit),

	/** The settings window. */
	EditSettings(CommandFamily.Edit),

	/** The commands that act on the selection as a whole. */
	SelectEverything(CommandFamily.Select),

	/** The by-kind submenus, which filter the object selection. */
	SelectByKind(CommandFamily.Select),

	/** Moving to the workspace before or after the active one. */
	WorkspaceSwitch(CommandFamily.Workspace),

	/** Making a workspace and resetting the active one. */
	WorkspaceManage(CommandFamily.Workspace),

	/** What is done to one workspace: the rows of a tab's own menu, which act on the active workspace from the bar. */
	WorkspaceTab(CommandFamily.Workspace),

	/** The layout's trips to and from a file. */
	WorkspaceFiles(CommandFamily.Workspace),

	/** The project's links. */
	HelpLinks(CommandFamily.Help),

	/** Quick Setup, the first of the setup and upkeep rows. */
	HelpSetup(CommandFamily.Help),

	/** Check for Updates, where the host can make the request. */
	HelpUpdates(CommandFamily.Help, ruled = false),

	/** Open Log Folder, where the host can show a folder. */
	HelpLogs(CommandFamily.Help, ruled = false),

	/** The Credits and About dialogs. */
	HelpAbout(CommandFamily.Help),

	/** The world-space snaps that move the 2D cursor. */
	SnapCursor(CommandFamily.Snap),

	/** The world-space snaps that move the selection. */
	SnapSelection(CommandFamily.Snap),

	/** The texture-space snaps that move the selection. */
	UvSnapSelection(CommandFamily.UvSnap),

	/** The texture-space snaps that move the UV editor's cursor. */
	UvSnapCursor(CommandFamily.UvSnap),
}