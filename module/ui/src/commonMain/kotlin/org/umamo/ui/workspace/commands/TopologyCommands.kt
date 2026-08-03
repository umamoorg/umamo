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
				// The duplicate itself needs no area - it is a model edit - so it runs wherever the pointer
				// is.  Only the auto-grab needs a viewport, and it simply does not happen when the pointer
				// is on a UV editor or a panel: starting a grab in an area the user is not pointing at is
				// worse than leaving the copies where they landed for an explicit one.  Both modes now read
				// the same rule from viewportArea(), which is null off a viewport.
				if (live.mode.value == EditorMode.Edit) {
					live.duplicateSelectedElements()
					// Proportional weights would drag the ORIGINAL vertices along with the fresh copies, so
					// the auto-grab latch opts out of them (Blender parity).
					routing.viewportArea()?.let { areaId ->
						live.beginMeshOperator(MeshOperatorKind.Grab, areaId, suppressProportional = true)
					}
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
		) { editorSession?.requestRip(routing.viewportArea()) },
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
