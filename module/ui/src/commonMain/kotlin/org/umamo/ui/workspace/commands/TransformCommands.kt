package org.umamo.ui.workspace.commands

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.ui.action.Command
import org.umamo.ui.resources.*

/**
 * The modal transform commands (Blender G / S / R) and their cancel.
 *
 * One binding serves every surface and mode: the operator dispatches by where the pointer is and which
 * mode is active, so the keymap binds a bare letter with no context of its own.  Each session method
 * guards its own preconditions, so an out-of-mode or ineligible press is a safe no-op.
 *
 * @param EditorSession? editorSession The open document's session, or null (every command then no-ops).
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @param SessionAvailability availability The shared document-scoped availability tiers.
 * @return List<Command> The commands to register.
 */
internal fun transformCommands(
	editorSession: EditorSession?,
	routing: CommandRouting,
	availability: SessionAvailability,
): List<Command> =
	listOf(
		Command("mesh.grab", title = Res.string.cmd_mesh_grab, availability = availability.hasDocument) {
			beginTransform(editorSession, MeshOperatorKind.Grab, routing)
		},
		Command("mesh.scale", title = Res.string.cmd_mesh_scale, availability = availability.hasDocument) {
			beginTransform(editorSession, MeshOperatorKind.Scale, routing)
		},
		Command("mesh.rotate", title = Res.string.cmd_mesh_rotate, availability = availability.hasDocument) {
			beginTransform(editorSession, MeshOperatorKind.Rotate, routing)
		},
		// Untitled and ungated: the ladder and the overlays dispatch it as a cleanup signal, and exactly
		// one of the three latches can be live, so clearing all three unconditionally is the whole job.
		Command("mesh.modalCancel", title = null) {
			editorSession?.clearMeshOperator()
			editorSession?.clearObjectOperator()
			editorSession?.clearUvOperator()
		},
	)

/**
 * Begins a modal transform on [session] for the surface the pointer last touched: a UV operator over
 * texture coordinates in a hovered UV editor (Blender's hovered-area routing - the key acts where the
 * pointer is), else an Edit-mode mesh operator over the selected vertices or an Object-mode operator over
 * the selected drawables' whole geometry.
 *
 * @param EditorSession? session The active session, or null when no document is open.
 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
 * @param CommandRouting routing Resolves the target surface at dispatch time.
 * @note The UV branch does not fall back to a viewport when the session declines it (beginUvOperator
 *   refuses an Edit-mode selection with no editable UVs and an Object-mode selection with no packed
 *   art to place).  Refusing outright is the point: the alternative would grab geometry in an area the
 *   pointer is nowhere near.
 */
private fun beginTransform(session: EditorSession?, kind: MeshOperatorKind, routing: CommandRouting) {
	if (session == null) {
		return
	}
	when (val target = routing.transformTarget()) {
		is TransformTarget.Uv -> session.beginUvOperator(kind, target.areaId)
		is TransformTarget.Viewport ->
			if (session.mode.value == EditorMode.Edit) {
				session.beginMeshOperator(kind, target.areaId)
			} else {
				session.beginObjectOperator(kind, target.areaId)
			}
		// The pointer has never touched a viewport and is not over a UV editor: nowhere to run it.
		null -> Unit
	}
}