package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorSession
import org.umamo.edit.UvSnapKind
import org.umamo.edit.UvSnapRequest
import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/**
 * The texture-coordinate commands: the axis mirrors and the UV editor's own snap pie.
 *
 * Every snap entry runs through the session's UV snap request flow to the hovered UV editor's overlay,
 * which owns the shown page's dimensions and display geometry.  The area is resolved HERE, at dispatch,
 * into the payload (like Select Linked), so the collector gates deterministically on its own area id.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun uvCommands(
	editorSession: EditorSession?,
	routing: CommandRouting,
	availability: SessionAvailability,
): List<Command> {
	/**
	 * Fires one UV snap request at the hovered area.
	 *
	 * @param UvSnapKind kind The snap to perform.
	 */
	fun requestUvSnap(kind: UvSnapKind) {
		editorSession?.requestUvSnap(UvSnapRequest(kind, routing.hoveredAreaIdAnyKind()))
		editorSession?.closePieMenu()
	}
	return listOf(
		// Mirror UVs (the duplicated-and-flipped texture regions workflow, e.g. both eyes sampling one
		// eye texture): axis-aligned reflections about the transform pivot, palette-discoverable and
		// unbound by default - an interactive flip is already S + axis + drag through the pivot.
		Command("uv.mirrorU", title = Res.string.cmd_uv_mirror_u, availability = availability.inEditMode) {
			editorSession?.mirrorSelectedUvs(mirrorU = true)
		},
		Command("uv.mirrorV", title = Res.string.cmd_uv_mirror_v, availability = availability.inEditMode) {
			editorSession?.mirrorSelectedUvs(mirrorU = false)
		},
		Command("uv.snap.selectionToPixels", title = Res.string.cmd_uv_snap_selection_pixels, availability = availability.inEditMode) {
			requestUvSnap(UvSnapKind.SelectionToPixels)
		},
		Command("uv.snap.selectionToCursor", title = Res.string.cmd_uv_snap_selection_cursor, availability = availability.inEditMode) {
			requestUvSnap(UvSnapKind.SelectionToCursor)
		},
		Command("uv.snap.selectionToCursorOffset", title = Res.string.cmd_uv_snap_selection_cursor_offset, availability = availability.inEditMode) {
			requestUvSnap(UvSnapKind.SelectionToCursorOffset)
		},
		Command("uv.snap.selectionToGrid", title = Res.string.cmd_uv_snap_selection_grid, availability = availability.inEditMode) {
			requestUvSnap(UvSnapKind.SelectionToGrid)
		},
		Command("uv.snap.cursorToPixels", title = Res.string.cmd_uv_snap_cursor_pixels, availability = availability.inEditMode) {
			requestUvSnap(UvSnapKind.CursorToPixels)
		},
		Command("uv.snap.cursorToSelected", title = Res.string.cmd_uv_snap_cursor_selected, availability = availability.inEditMode) {
			requestUvSnap(UvSnapKind.CursorToSelected)
		},
		Command("uv.snap.cursorToGrid", title = Res.string.cmd_uv_snap_cursor_grid, availability = availability.inEditMode) {
			requestUvSnap(UvSnapKind.CursorToGrid)
		},
	)
}