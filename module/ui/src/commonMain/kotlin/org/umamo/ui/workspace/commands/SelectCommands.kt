package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelectMode
import org.umamo.ui.action.Command
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.KeyformSheetViews
import org.umamo.ui.workspace.SpaceKind

/**
 * The selection commands: the Edit-mode element domains, select all / invert, the Box and Circle marquee
 * tools, and the pointer-driven Select Linked pair.
 *
 * Several of these dispatch by mode so one binding serves both - mesh elements in Edit mode, whole
 * entities in Object mode - and the session methods no-op in the wrong mode, so the keymap binds them
 * context-free.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @param KeyformSheetViews keyformSheets The shell's open-sheet registry; Box Select arms the hovered
 *   sheet's own marquee through it instead of the viewport's tool.
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun selectCommands(
	editorSession: EditorSession?,
	routing: CommandRouting,
	keyformSheets: KeyformSheetViews,
	availability: SessionAvailability,
): List<Command> =
	listOf(
		// Edit-mode select modes (Blender 1 / 2 / 3). The session guards them to no-op outside Edit mode,
		// so the keymap can bind bare digits context-free; the availability tier additionally hides them
		// from the palette in Object mode (there is no element domain to switch there).
		Command("mesh.selectMode.vertex", title = Res.string.cmd_mesh_select_mode_vertex, availability = availability.inEditMode) {
			editorSession?.setMeshSelectMode(MeshSelectMode.Vertex)
		},
		Command("mesh.selectMode.edge", title = Res.string.cmd_mesh_select_mode_edge, availability = availability.inEditMode) {
			editorSession?.setMeshSelectMode(MeshSelectMode.Edge)
		},
		Command("mesh.selectMode.face", title = Res.string.cmd_mesh_select_mode_face, availability = availability.inEditMode) {
			editorSession?.setMeshSelectMode(MeshSelectMode.Face)
		},
		// Select All / Invert dispatch by mode so one binding (A / Ctrl+I) serves both: mesh elements in Edit
		// mode, whole selectable entities in Object mode. Each session method no-ops in the wrong mode.
		Command("select.all", title = Res.string.cmd_select_all, availability = availability.hasDocument) {
			if (editorSession?.mode?.value == EditorMode.Edit) {
				editorSession.selectAllMeshElements()
			} else {
				editorSession?.selectAllObjects()
			}
		},
		Command("select.invert", title = Res.string.cmd_select_invert, availability = availability.hasDocument) {
			if (editorSession?.mode?.value == EditorMode.Edit) {
				editorSession.invertMeshSelection()
			} else {
				editorSession?.invertObjectSelection()
			}
		},
		// Box (Blender B) and Circle (Blender C) select tools latch on the session; the arming surface's
		// overlay drives the gesture (both modes in the viewport; Edit mode in the UV editor, whose
		// overlay only composes there). The circle grow / shrink commands (numpad +/-) apply only
		// while a Circle brush is live, so they hide from the palette otherwise.
		// ONE Box Select that acts on whatever is under the pointer, rather than a second command fighting
		// for the same chord: a hovered keyform sheet arms its own marquee, a hovered viewport (or UV
		// editor in Edit mode) arms the session's tool, and anything else arms nothing.  Resolved at
		// dispatch time like every other hovered-surface command.
		Command("mesh.boxSelect", title = Res.string.cmd_mesh_box_select, availability = availability.hasDocument) {
			// Only reach for a sheet when the pointer actually names one.  The registry's lookup falls back
			// to the lone open sheet when handed no area, so asking it unconditionally would hijack B in the
			// viewport for any layout that happens to have a keyform sheet open.
			val hoveredSheetArea = routing.areaOf(SpaceKind.KeyformSheet)
			if (hoveredSheetArea != null) {
				keyformSheets.resolve(hoveredSheetArea)?.armBoxSelect?.invoke()
			} else {
				routing.selectToolArea(editorSession)?.let { areaId -> editorSession?.beginBoxSelect(areaId) }
			}
		},
		// No sheet branch here, unlike Box Select: the keyform sheet has no circle brush to arm, so C over a
		// sheet arms nothing at all - selectToolArea answers only for a viewport or an Edit-mode UV editor,
		// and arming a viewport the pointer has left is worse than doing nothing.
		Command("mesh.circleSelect", title = Res.string.cmd_mesh_circle_select, availability = availability.hasDocument) {
			routing.selectToolArea(editorSession)?.let { areaId -> editorSession?.beginCircleSelect(areaId) }
		},
		Command("mesh.circleSelect.grow", title = Res.string.cmd_mesh_circle_grow, availability = availability.circleToolLive) {
			editorSession?.growCircleRadius()
		},
		Command("mesh.circleSelect.shrink", title = Res.string.cmd_mesh_circle_shrink, availability = availability.circleToolLive) {
			editorSession?.shrinkCircleRadius()
		},
		// Select Linked (Blender's L / Ctrl+L) and the Alt+Q edited-mesh switch: the pointer position and
		// the projected geometry live in the overlays, so these fire session request flows they collect.
		// The executing area resolves HERE, at dispatch, into the request payload (the hovered surface -
		// viewport or UV editor alike), so the collectors gate deterministically on their own area id.
		Command("mesh.selectLinkedAtCursor", title = Res.string.cmd_mesh_select_linked_cursor, availability = availability.inEditMode) {
			editorSession?.requestSelectLinked(fromSelection = false, areaId = routing.hoveredAreaIdAnyKind())
		},
		Command("mesh.selectLinked", title = Res.string.cmd_mesh_select_linked, availability = availability.inEditMode) {
			editorSession?.requestSelectLinked(fromSelection = true, areaId = routing.hoveredAreaIdAnyKind())
		},
		Command("edit.switchObjectUnderCursor", title = Res.string.cmd_switch_object_under_cursor, availability = availability.inEditMode) {
			editorSession?.requestSwitchObjectUnderCursor(routing.viewportArea())
		},
	)
