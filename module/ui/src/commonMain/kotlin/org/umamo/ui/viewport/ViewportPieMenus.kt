package org.umamo.ui.viewport

import org.jetbrains.compose.resources.StringResource
import org.umamo.edit.PieMenuKind
import org.umamo.ui.kit.PieMenuEntry
import org.umamo.ui.resources.*

/**
 * The entry ring for one pie kind, in Blender slot order (W, E, S, N, then the diagonals).  Shared
 * by the shell-level pie host's rendering (ShellCursorOverlays.kt) and the shell's 1..N digit
 * shortcuts, so the picked ordinal always matches the drawn number.  Icons are authored per entry;
 * a null icon renders the placeholder square.
 *
 * @param PieMenuKind kind The open pie.
 * @return List<PieMenuEntry> The pie's entries.
 */
fun pieMenuEntriesFor(kind: PieMenuKind): List<PieMenuEntry> =
	when (kind) {
		PieMenuKind.PivotMode ->
			listOf(
				PieMenuEntry("transform.pivot.median", Res.string.cmd_transform_pivot_median),
				PieMenuEntry("transform.pivot.individual", Res.string.cmd_transform_pivot_individual),
				PieMenuEntry("transform.pivot.active", Res.string.cmd_transform_pivot_active),
				PieMenuEntry("transform.pivot.cursor", Res.string.cmd_transform_pivot_cursor),
			)

		PieMenuKind.Snap ->
			listOf(
				PieMenuEntry("snap.cursorToWorldOrigin", Res.string.cmd_snap_cursor_world_origin),
				PieMenuEntry("snap.cursorToSelected", Res.string.cmd_snap_cursor_selected),
				PieMenuEntry("snap.cursorToActive", Res.string.cmd_snap_cursor_active),
				PieMenuEntry("snap.cursorToGrid", Res.string.cmd_snap_cursor_grid),
				PieMenuEntry("snap.selectionToGrid", Res.string.cmd_snap_selection_grid),
				PieMenuEntry("snap.selectionToCursor", Res.string.cmd_snap_selection_cursor),
				PieMenuEntry("snap.selectionToCursorOffset", Res.string.cmd_snap_selection_cursor_offset),
				PieMenuEntry("snap.selectionToActive", Res.string.cmd_snap_selection_active),
			)

		PieMenuKind.UvSnap ->
			listOf(
				PieMenuEntry("uv.snap.selectionToPixels", Res.string.cmd_uv_snap_selection_pixels),
				PieMenuEntry("uv.snap.selectionToCursor", Res.string.cmd_uv_snap_selection_cursor),
				PieMenuEntry("uv.snap.selectionToCursorOffset", Res.string.cmd_uv_snap_selection_cursor_offset),
				PieMenuEntry("uv.snap.selectionToGrid", Res.string.cmd_uv_snap_selection_grid),
				PieMenuEntry("uv.snap.cursorToPixels", Res.string.cmd_uv_snap_cursor_pixels),
				PieMenuEntry("uv.snap.cursorToSelected", Res.string.cmd_uv_snap_cursor_selected),
				PieMenuEntry("uv.snap.cursorToGrid", Res.string.cmd_uv_snap_cursor_grid),
			)

		PieMenuKind.MergeTarget ->
			listOf(
				PieMenuEntry("mesh.merge.atCenter", Res.string.cmd_mesh_merge_at_center),
				PieMenuEntry("mesh.merge.atFirst", Res.string.cmd_mesh_merge_at_first),
				PieMenuEntry("mesh.merge.atLast", Res.string.cmd_mesh_merge_at_last),
			)
	}

/**
 * The centre title for one pie kind (Blender names its pies the same way).
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
	}
