package org.umamo.ui.workspace.commands

import org.jetbrains.compose.resources.StringResource
import org.umamo.edit.PieMenuKind
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.kit.menu.PieMenuEntry
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons

/**
 * The entry ring for one pie kind, in Blender slot order (W, E, S, N, then the diagonals), each entry enabled exactly
 * when its command is registered and available.  Shared by the shell-level pie host's rendering
 * (ShellCursorOverlays.kt), the shell's 1..N digit shortcuts, and the 2D viewport's context menu, so the picked
 * ordinal always matches the drawn number and a greyed chip is a greyed row.  Icons are authored per entry; a null
 * icon renders the placeholder square.
 *
 * @param PieMenuKind kind The open pie.
 * @param CommandRegistry commands The registry the entries dispatch through, which says which are available.
 * @return List<PieMenuEntry> The pie's entries.
 */
fun pieMenuEntriesFor(
	kind: PieMenuKind,
	commands: CommandRegistry,
): List<PieMenuEntry> =
	authoredEntriesFor(kind).map { entry ->
		entry.copy(enabled = entry.enabled && commands[entry.commandId]?.availability?.isAvailable() == true)
	}

/**
 * The entry ring one pie kind is authored with, before availability greys any of it.
 *
 * @param PieMenuKind kind The pie.
 * @return List<PieMenuEntry> The authored entries.
 */
private fun authoredEntriesFor(kind: PieMenuKind): List<PieMenuEntry> =
	when (kind) {
		PieMenuKind.PivotMode -> {
			listOf(
				PieMenuEntry("transform.pivot.median", Res.string.cmd_transform_pivot_median, icon = LocalUmamoIcons.pivotMedian),
				PieMenuEntry("transform.pivot.individual", Res.string.cmd_transform_pivot_individual, icon = LocalUmamoIcons.pivotIndividual),
				PieMenuEntry("transform.pivot.active", Res.string.cmd_transform_pivot_active, icon = LocalUmamoIcons.pivotActive),
				PieMenuEntry("transform.pivot.cursor", Res.string.cmd_transform_pivot_cursor, icon = LocalUmamoIcons.pivotCursor),
			)
		}

		PieMenuKind.Snap -> {
			listOf(
				PieMenuEntry("snap.cursorToWorldOrigin", Res.string.cmd_snap_cursor_world_origin, icon = LocalUmamoIcons.cursor),
				PieMenuEntry("snap.cursorToSelected", Res.string.cmd_snap_cursor_selected, icon = LocalUmamoIcons.cursor),
				PieMenuEntry("snap.cursorToActive", Res.string.cmd_snap_cursor_active, icon = LocalUmamoIcons.cursor),
				PieMenuEntry("snap.cursorToGrid", Res.string.cmd_snap_cursor_grid, icon = LocalUmamoIcons.cursor),
				PieMenuEntry("snap.selectionToGrid", Res.string.cmd_snap_selection_grid, icon = LocalUmamoIcons.selection),
				PieMenuEntry("snap.selectionToCursor", Res.string.cmd_snap_selection_cursor, icon = LocalUmamoIcons.selection),
				PieMenuEntry("snap.selectionToCursorOffset", Res.string.cmd_snap_selection_cursor_offset, icon = LocalUmamoIcons.selection),
				PieMenuEntry("snap.selectionToActive", Res.string.cmd_snap_selection_active, icon = LocalUmamoIcons.selection),
			)
		}

		PieMenuKind.UvSnap -> {
			listOf(
				PieMenuEntry("uv.snap.selectionToPixels", Res.string.cmd_uv_snap_selection_pixels, icon = LocalUmamoIcons.selection),
				PieMenuEntry("uv.snap.selectionToCursor", Res.string.cmd_uv_snap_selection_cursor, icon = LocalUmamoIcons.selection),
				PieMenuEntry("uv.snap.selectionToCursorOffset", Res.string.cmd_uv_snap_selection_cursor_offset, icon = LocalUmamoIcons.selection),
				PieMenuEntry("uv.snap.selectionToGrid", Res.string.cmd_uv_snap_selection_grid, icon = LocalUmamoIcons.selection),
				PieMenuEntry("uv.snap.cursorToPixels", Res.string.cmd_uv_snap_cursor_pixels, icon = LocalUmamoIcons.pivotCursor),
				PieMenuEntry("uv.snap.cursorToSelected", Res.string.cmd_uv_snap_cursor_selected, icon = LocalUmamoIcons.pivotCursor),
				PieMenuEntry("uv.snap.cursorToGrid", Res.string.cmd_uv_snap_cursor_grid, icon = LocalUmamoIcons.pivotCursor),
			)
		}

		PieMenuKind.MergeTarget -> {
			listOf(
				PieMenuEntry("mesh.merge.atCenter", Res.string.cmd_mesh_merge_at_center),
				PieMenuEntry("mesh.merge.atFirst", Res.string.cmd_mesh_merge_at_first),
				PieMenuEntry("mesh.merge.atLast", Res.string.cmd_mesh_merge_at_last),
			)
		}

		PieMenuKind.InsertKey -> {
			listOf(
				PieMenuEntry("keyform.insert.lattice", Res.string.pie_insert_key_lattice),
				PieMenuEntry("keyform.insert.opacity", Res.string.pie_insert_key_opacity),
				PieMenuEntry("keyform.insert.colors", Res.string.pie_insert_key_colors),
				PieMenuEntry("keyform.insert.all", Res.string.pie_insert_key_all),
			)
		}
	}

/**
 * The center title for one pie kind (Blender names its pies the same way).
 *
 * @param PieMenuKind kind The open pie.
 * @return StringResource The localized title.
 */
fun pieMenuTitleFor(kind: PieMenuKind): StringResource =
	when (kind) {
		PieMenuKind.PivotMode -> Res.string.pie_title_pivot
		PieMenuKind.Snap -> Res.string.pie_title_snap
		PieMenuKind.UvSnap -> Res.string.pie_title_uv_snap
		PieMenuKind.MergeTarget -> Res.string.pie_title_merge
		PieMenuKind.InsertKey -> Res.string.pie_title_insert_key
	}