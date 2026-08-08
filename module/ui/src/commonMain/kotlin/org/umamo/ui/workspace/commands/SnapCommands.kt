package org.umamo.ui.workspace.commands

import org.umamo.edit.Cursor2d
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.PieMenuKind
import org.umamo.edit.SnapKind
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.snapToGrid
import org.umamo.ui.action.Command
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.SpaceKind

/**
 * The transform pivot modes (the Period pie / the palette) and the Shift+S snap operations over world
 * space.
 *
 * The pivot setters and the arithmetical cursor snaps run right here; the geometry-dependent snaps go
 * through the session's request flow to the active mode's overlay, which owns the world projections.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun snapCommands(
	editorSession: EditorSession?,
	routing: CommandRouting,
	availability: SessionAvailability,
): List<Command> =
	listOf(
		Command("transform.pivotPie", title = Res.string.cmd_transform_pivot_pie, availability = availability.hasDocument) {
			editorSession?.openPieMenu(PieMenuKind.PivotMode)
		},
		Command("transform.pivot.median", title = Res.string.cmd_transform_pivot_median, availability = availability.hasDocument) {
			editorSession?.setPivotMode(TransformPivotMode.MedianPoint)
			editorSession?.closePieMenu()
		},
		Command("transform.pivot.individual", title = Res.string.cmd_transform_pivot_individual, availability = availability.hasDocument) {
			editorSession?.setPivotMode(TransformPivotMode.IndividualOrigins)
			editorSession?.closePieMenu()
		},
		Command("transform.pivot.active", title = Res.string.cmd_transform_pivot_active, availability = availability.hasDocument) {
			editorSession?.setPivotMode(TransformPivotMode.ActiveElement)
			editorSession?.closePieMenu()
		},
		Command("transform.pivot.cursor", title = Res.string.cmd_transform_pivot_cursor, availability = availability.hasDocument) {
			editorSession?.setPivotMode(TransformPivotMode.Cursor)
			editorSession?.closePieMenu()
		},
		Command("snap.pie", title = Res.string.cmd_snap_pie, availability = availability.hasDocument) {
			// Blender's hovered-area routing (the key acts where the pointer is): over the UV editor in
			// Edit mode this opens the UV snap pie, whose entries snap texture coordinates; everywhere
			// else the world snap pie - mirroring how mesh.grab / scale / rotate route to beginUvOperator.
			editorSession?.let { live ->
				if (live.mode.value == EditorMode.Edit && routing.isHovering(SpaceKind.UvEditor)) {
					live.openPieMenu(PieMenuKind.UvSnap)
				} else {
					live.openPieMenu(PieMenuKind.Snap)
				}
			}
		},
		Command("snap.cursorToWorldOrigin", title = Res.string.cmd_snap_cursor_world_origin, availability = availability.hasDocument) {
			editorSession?.let { live ->
				live.setCursor2d(live.model.value.worldOriginX, live.model.value.worldOriginY)
				live.closePieMenu()
			}
		},
		Command("snap.cursorToGrid", title = Res.string.cmd_snap_cursor_grid, availability = availability.hasDocument) {
			editorSession?.let { live ->
				val model = live.model.value
				// An unplaced cursor snaps from the world origin (its conceptual resting place).
				val cursor = live.cursor2d.value ?: Cursor2d(model.worldOriginX, model.worldOriginY)
				val step = live.gridConfig.value.snapStep
				// Round relative to the world origin, so the snap targets the same lines the grid draws
				// (a major line crosses the origin, not an arbitrary mid-cell point).
				live.setCursor2d(
					snapToGrid(cursor.worldX, model.worldOriginX, step),
					snapToGrid(cursor.worldY, model.worldOriginY, step),
				)
				live.closePieMenu()
			}
		},
		Command("snap.cursorToSelected", title = Res.string.cmd_snap_cursor_selected, availability = availability.hasDocument) {
			editorSession?.requestSnap(SnapKind.CursorToSelected, routing.viewportArea())
			editorSession?.closePieMenu()
		},
		Command("snap.cursorToActive", title = Res.string.cmd_snap_cursor_active, availability = availability.hasDocument) {
			editorSession?.requestSnap(SnapKind.CursorToActive, routing.viewportArea())
			editorSession?.closePieMenu()
		},
		Command("snap.selectionToGrid", title = Res.string.cmd_snap_selection_grid, availability = availability.hasDocument) {
			editorSession?.requestSnap(SnapKind.SelectionToGrid, routing.viewportArea())
			editorSession?.closePieMenu()
		},
		Command("snap.selectionToCursor", title = Res.string.cmd_snap_selection_cursor, availability = availability.hasDocument) {
			editorSession?.requestSnap(SnapKind.SelectionToCursor, routing.viewportArea())
			editorSession?.closePieMenu()
		},
		Command("snap.selectionToCursorOffset", title = Res.string.cmd_snap_selection_cursor_offset, availability = availability.hasDocument) {
			editorSession?.requestSnap(SnapKind.SelectionToCursorOffset, routing.viewportArea())
			editorSession?.closePieMenu()
		},
		Command("snap.selectionToActive", title = Res.string.cmd_snap_selection_active, availability = availability.hasDocument) {
			editorSession?.requestSnap(SnapKind.SelectionToActive, routing.viewportArea())
			editorSession?.closePieMenu()
		},
	)