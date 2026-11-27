package org.umamo.ui.workspace.spaces.viewport2d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandMenuRow
import org.umamo.ui.action.CommandSection
import org.umamo.ui.action.rememberCommandMenu
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_select

/*
 * The 2D viewport's context menu: what an idle right-click over the surface offers in each mode, as an arrangement
 * of existing commands.  A context menu is contextual, so it holds the mode's operations and nothing of the area's
 * (those stay on the header, as in the UV editor).  This file says which commands and families a mode offers and in
 * what order, and nothing more: every row's label, accelerator, glyph, and grayed state are read from its command.
 *
 * A command family comes in as a submenu, its rows and its rules listed from the registry, so the command tables are
 * what say which commands the submenu offers.  A family a pie draws goes under the title of the command that opens
 * the pie, and since the pie lists the same family, a grayed chip is a grayed row.  The selection rows group under a
 * Select submenu, which is the Select menu itself in Object mode and its first block elsewhere, and grays out only
 * when nothing under it can be picked.  Operations driven by the pointer's position (Rip, Vertex Slide, the object
 * switch) stay off it: from a menu, the pointer is on the menu.
 */

/**
 * A Select submenu for a mode with no object kinds to filter by: Select All and Invert, then [extraRows].
 *
 * @param List<CommandMenuRow> extraRows The mode's own selection rows.
 * @return CommandMenuRow The submenu.
 */
private fun selectSubmenu(extraRows: List<CommandMenuRow>): CommandMenuRow =
	CommandMenuRow.Submenu(
		Res.string.menu_select,
		listOf(CommandMenuRow.Members(CommandSection.SelectEverything)) + extraRows,
	)

/**
 * Object mode's rows: whole-object operations, the Select menu, the snaps, the art meshes, and wrapping the selection
 * in a warp.
 */
private val OBJECT_ROWS =
	listOf(
		CommandMenuRow.Single("mesh.duplicate"),
		CommandMenuRow.Single("object.toggleVisibility"),
		CommandMenuRow.Single("object.hideUnselected"),
		CommandMenuRow.Single("object.unhideAll"),
		CommandMenuRow.Separator,
		CommandMenuRow.Family(CommandFamily.Select),
		CommandMenuRow.Separator,
		CommandMenuRow.Family(CommandFamily.Snap, openerId = "snap.pie"),
		CommandMenuRow.Separator,
		CommandMenuRow.Single("mesh.generateFromArt"),
		CommandMenuRow.Single("mesh.generateQuad"),
		CommandMenuRow.Single("deformer.createWarp"),
		CommandMenuRow.Separator,
		CommandMenuRow.Single("view.frameSelected"),
	)

/** Edit mode's rows: the topology operations, the element selection, the snaps, the art meshes, and the lattice resolution. */
private val EDIT_ROWS =
	listOf(
		CommandMenuRow.Family(CommandFamily.MergeTarget, openerId = "mesh.merge"),
		CommandMenuRow.Single("mesh.connect"),
		CommandMenuRow.Single("mesh.duplicate"),
		CommandMenuRow.Separator,
		selectSubmenu(listOf(CommandMenuRow.Single("mesh.selectLinked"))),
		CommandMenuRow.Separator,
		CommandMenuRow.Family(CommandFamily.Snap, openerId = "snap.pie"),
		CommandMenuRow.Separator,
		CommandMenuRow.Single("mesh.generateFromArt"),
		CommandMenuRow.Single("mesh.generateQuad"),
		CommandMenuRow.Single("lattice.subdivide"),
		CommandMenuRow.Separator,
		CommandMenuRow.Single("view.frameSelected"),
	)

/** Pose mode's rows: keying the posed warps, clearing what is held, the element selection, and the snaps. */
private val POSE_ROWS =
	listOf(
		CommandMenuRow.Members(CommandFamily.KeyformKey),
		CommandMenuRow.Family(CommandFamily.InsertKey, openerId = "keyform.insertPie"),
		CommandMenuRow.Single("keyform.clearPoseEdits"),
		CommandMenuRow.Separator,
		selectSubmenu(emptyList()),
		CommandMenuRow.Separator,
		CommandMenuRow.Family(CommandFamily.Snap, openerId = "snap.pie"),
		CommandMenuRow.Separator,
		CommandMenuRow.Single("view.frameSelected"),
	)

/**
 * The rows [mode] offers.
 *
 * @param EditorMode mode The mode.
 * @return List<CommandMenuRow> The rows, top to bottom.
 */
private fun rowsFor(mode: EditorMode): List<CommandMenuRow> =
	when (mode) {
		EditorMode.Object -> OBJECT_ROWS
		EditorMode.Edit -> EDIT_ROWS
		EditorMode.Pose -> POSE_ROWS
	}

/**
 * The viewport's context menu for the session's mode, as the builder to call when the menu opens, so a row's grayed
 * state is whatever its command says at that moment.  Null with no session, which has no menu.
 *
 * @param EditorSession? session The open document's session, or null.
 * @return Function? The builder, or null.
 */
@Composable
internal fun rememberViewportContextMenu(session: EditorSession?): (() -> List<MenuItem>)? {
	if (session == null) {
		return null
	}

	return rememberCommandMenu(rowsFor(session.mode.collectAsState().value))
}