package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MergeTarget
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.PieMenuKind
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.SpaceKind

/**
 * The topology operators (Blender's Shift+D / M / V / J).  Duplicate dispatches by mode like G / S / R
 * and auto-grabs the copies; merge opens its target pie; rip needs the pointer side, so it flows to the
 * Edit overlay; connect cuts between the two selected vertices in place.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun topologyCommands(
	editorSession: EditorSession?,
	routing: CommandRouting,
	availability: SessionAvailability,
): List<Command> =
	listOf(
		Command("mesh.duplicate", title = Res.string.cmd_mesh_duplicate, availability = availability.hasDocument) {
			val live = editorSession
			if (live != null) {
				if (live.mode.value == EditorMode.Edit) {
					live.duplicateSelectedElements()
					// The auto-grab places the fresh copies; proportional weights would drag the
					// original vertices around them, so the latch opts out (Blender parity).  The
					// duplicate itself never depends on a viewport - only the grab needs one, so a
					// hovered UV editor skips it (an auto-grab of rest positions over fresh topology
					// is meaningless there; the copies stay put for an explicit grab instead).
					if (!routing.isHovering(SpaceKind.UvEditor)) {
						routing.viewportArea()?.let { areaId ->
							live.beginMeshOperator(MeshOperatorKind.Grab, areaId, suppressProportional = true)
						}
					}
					// The Object branch deliberately does NOT check the hovered surface the way the Edit one
					// above does: duplicating drawables over a hovered UV editor still auto-grabs them in the
					// pointer's last viewport.  Preserved as-is - reconciling the two is a behavior decision,
					// not a tidy-up.
				} else if (live.duplicateSelectedDrawables().isNotEmpty()) {
					routing.viewportArea()?.let { areaId -> live.beginObjectOperator(MeshOperatorKind.Grab, areaId) }
				}
			}
		},
		Command("mesh.merge", title = Res.string.cmd_mesh_merge, availability = availability.inEditMode) {
			editorSession?.openPieMenu(PieMenuKind.MergeTarget)
		},
		Command("mesh.merge.atCenter", title = Res.string.cmd_mesh_merge_at_center, availability = availability.inEditMode) {
			editorSession?.mergeSelectedVertices(MergeTarget.AtCenter)
			editorSession?.closePieMenu()
		},
		Command("mesh.merge.atFirst", title = Res.string.cmd_mesh_merge_at_first, availability = availability.inEditMode) {
			editorSession?.mergeSelectedVertices(MergeTarget.AtFirst)
			editorSession?.closePieMenu()
		},
		Command("mesh.merge.atLast", title = Res.string.cmd_mesh_merge_at_last, availability = availability.inEditMode) {
			editorSession?.mergeSelectedVertices(MergeTarget.AtLast)
			editorSession?.closePieMenu()
		},
		Command(
			"mesh.rip",
			title = Res.string.cmd_mesh_rip,
			availability =
				CommandAvailability {
					editorSession?.mode?.value == EditorMode.Edit && editorSession.meshSelection.value.selectMode != MeshSelectMode.Face
				},
		) { editorSession?.requestRip() },
		Command("mesh.connect", title = Res.string.cmd_mesh_connect, availability = availability.inEditMode) {
			editorSession?.connectSelectedVertices()
		},
		Command(
			"mesh.vertexSlide",
			title = Res.string.cmd_mesh_vertex_slide,
			availability =
				CommandAvailability {
					editorSession?.mode?.value == EditorMode.Edit &&
						editorSession.meshSelection.value.activeElement?.element is MeshElement.Vertex
				},
		) {
			routing.viewportArea()?.let { areaId -> editorSession?.beginMeshOperator(MeshOperatorKind.VertexSlide, areaId) }
		},
	)
