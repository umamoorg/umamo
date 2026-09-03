package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.edit.UvMirrorRequest
import org.umamo.edit.UvPageKind
import org.umamo.edit.UvPageRequest
import org.umamo.edit.UvSnapKind
import org.umamo.edit.UvSnapRequest
import org.umamo.edit.placementDragTileIds
import org.umamo.edit.setAtlasPins
import org.umamo.ui.action.Command
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.SpaceKind

/**
 * The texture-coordinate commands: the axis mirrors, the UV editor's own snap pie, the texture
 * page switches, and the placement pins.
 *
 * Every snap entry runs through the session's UV snap request flow to the hovered UV editor's overlay,
 * which owns the shown surface's dimensions and display geometry.  The area is resolved HERE, at dispatch,
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

	/**
	 * Fires one mirror request at the hovered UV editor, which supplies the frame it is authoring in.
	 *
	 * @param Boolean mirrorU True to mirror horizontally, false vertically.
	 */
	fun requestUvMirror(mirrorU: Boolean) {
		editorSession?.requestUvMirror(UvMirrorRequest(mirrorU, routing.areaOf(SpaceKind.UvEditor)))
	}

	/**
	 * Fires one page-switch request at the hovered UV editor.  The kind-checked resolver hands a
	 * pointer that is NOT on a UV editor a null area, which matches no collector - the Blender
	 * hovered-area rule, resolved one step earlier than the snap helper's any-kind id.
	 *
	 * @param UvPageKind kind The page operation to perform.
	 */
	fun requestUvPage(kind: UvPageKind) {
		editorSession?.requestUvPage(UvPageRequest(kind, routing.areaOf(SpaceKind.UvEditor)))
	}

	/**
	 * Pins or unpins the placed tiles under the object selection - the tiles a placement gesture
	 * would move - so a repack keeps (or may move) them.  From a hovered UV editor only: a pin is set
	 * while looking at the page, and the key means nothing over a viewport.
	 *
	 * @param Boolean pinned True to pin, false to unpin.
	 */
	fun setPins(pinned: Boolean) {
		val session = editorSession ?: return
		if (routing.areaOf(SpaceKind.UvEditor) == null) {
			return
		}
		val tileIds = session.model.value.placementDragTileIds(session.selection.value)
		if (tileIds.isEmpty()) {
			session.emitNotice("notice.uv.placement.noPlacedArt", NoticePlacement.NearCursor)
			return
		}
		session.setAtlasPins(tileIds, pinned)
	}
	return listOf(
		// Mirror UVs (the duplicated-and-flipped texture regions workflow, e.g. both eyes sampling one
		// eye texture): axis-aligned reflections about the transform pivot, palette-discoverable and
		// unbound by default - an interactive flip is already S + axis + drag through the pivot.
		Command("uv.mirrorU", title = Res.string.cmd_uv_mirror_u, availability = availability.inEditMode) {
			requestUvMirror(mirrorU = true)
		},
		Command("uv.mirrorV", title = Res.string.cmd_uv_mirror_v, availability = availability.inEditMode) {
			requestUvMirror(mirrorU = false)
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
		// Texture page switching (the header selector's palette path): retargets the hovered UV
		// editor's per-area texture selection - cycling pins with wrap-around, follow clears the pin.
		// Mode-agnostic (reviewing pages is not an Edit-mode operation), palette-discoverable, and
		// unbound by default like the rest of the table.
		Command("uv.page.next", title = Res.string.cmd_uv_page_next, availability = availability.hasDocument) {
			requestUvPage(UvPageKind.NextPage)
		},
		Command("uv.page.previous", title = Res.string.cmd_uv_page_previous, availability = availability.hasDocument) {
			requestUvPage(UvPageKind.PreviousPage)
		},
		Command("uv.page.followSelection", title = Res.string.cmd_uv_page_follow, availability = availability.hasDocument) {
			requestUvPage(UvPageKind.FollowSelection)
		},
		// Placement pins (Blender's P / Alt+P in the UV editor): Object mode, where the selection names
		// whole drawables and so the tiles under them.
		Command("uv.pinPlacement", title = Res.string.cmd_uv_pin_placement, availability = availability.inObjectMode) {
			setPins(pinned = true)
		},
		Command("uv.unpinPlacement", title = Res.string.cmd_uv_unpin_placement, availability = availability.inObjectMode) {
			setPins(pinned = false)
		},
	)
}