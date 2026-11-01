package org.umamo.edit.transform

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.NoticePlacement
import org.umamo.edit.atlas.placementDragTileIds
import org.umamo.edit.atlas.placementSelectedTileIds
import org.umamo.edit.editsElements
import org.umamo.edit.eligibleTransformTargets

/*
 * The guards between a command and a tool latch: the begin / arm entry points for the modal mesh, object,
 * and UV operators and the Box / Circle select tools.  Each checks the mode, the selection, and (for an
 * Object-mode transform) the pose before it lets the session's latches take the tool, and explains a
 * refusal with a near-cursor notice where a rigger could have meant the command.  The latches themselves,
 * and the clears the overlays call on confirm or cancel, are reached on the session through
 * SessionToolLatches.
 */

/**
 * Latches a modal mesh operator so the gizmo overlay begins the gesture.  A no-op unless a mode that edits elements
 * ([editsElements]) is active with a non-empty selection, so the bound G / S / R commands need no context guard of
 * their own and the keymap stays mode-agnostic.
 *
 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
 * @param Boolean suppressProportional True to ignore proportional editing for this gesture (the
 *   duplicate / rip auto-grabs; treated like Vertex Slide at every proportional gate).
 */
fun EditorSession.beginMeshOperator(kind: MeshOperatorKind, areaId: String, suppressProportional: Boolean = false) {
	if (!mode.value.editsElements) {
		return
	}

	val selection = meshSelection.value

	if (selection.owners.isEmpty() || selection.isEmpty) {
		return
	}

	latches.latchMeshOperator(kind, areaId, suppressProportional)
}

/**
 * Begins an Object-mode modal transform (Grab / Scale / Rotate) over the selected drawables and warps as
 * whole objects - the Object-mode counterpart to [beginMeshOperator].  Selected targets that cannot transform
 * are ignored ([eligibleTransformTargets]), so a Select All that swept them in never blocks the gesture.
 *
 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
 */
fun EditorSession.beginObjectOperator(kind: MeshOperatorKind, areaId: String) {
	if (mode.value != EditorMode.Object) {
		return
	}

	if (eligibleTransformTargets(selection.value, model.value) == null) {
		// Nothing transformable at all (empty, or only parts / rotation deformers / mesh-less drawables / warps
		// hidden at rest).
		emitNotice("notice.transform.onlyDrawables", NoticePlacement.NearCursor)

		return
	}

	if (!isPoseNeutral(model.value, pose.value)) {
		// Transforming rest geometry while the displayed pose is deformed would write garbage through
		// the deformer inverse, so refuse and tell the user to reset the parameters first.
		emitNotice("notice.transform.deformed", NoticePlacement.NearCursor)

		return
	}

	latches.latchObjectOperator(kind, areaId)
}

/**
 * Latches a modal UV operator so the UV editor's overlay begins the gesture - the UV-editor
 * counterpart to [beginMeshOperator].  Edit mode moves the selected texture coordinates and Object mode
 * the selected drawables' atlas PLACEMENTS; a gesture that could never commit is refused with a
 * near-cursor notice rather than showing a modal HUD.  Which page the overlay shows is per-area state the
 * session cannot see, so the overlay drops the latch with its own notice when its surface cannot serve
 * the gesture.
 *
 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
 * @param String areaId The initiating UV editor's area id (only its overlay drives the gesture).
 */
fun EditorSession.beginUvOperator(kind: MeshOperatorKind, areaId: String) {
	// Vertex Slide is rest-geometry math, with no meaning over texture coordinates.
	if (kind == MeshOperatorKind.VertexSlide) {
		return
	}

	val model = model.value

	when (mode.value) {
		EditorMode.Object -> {
			if (!model.atlas.storedUvsAddressPages) {
				emitNotice("notice.uv.placement.layerAddressed", NoticePlacement.NearCursor)

				return
			}

			if (model.placementDragTileIds(selection.value).isEmpty()) {
				// Placed art under the selection that still cannot move is pinned art.
				val messageKey =
					if (model.placementSelectedTileIds(selection.value).isEmpty()) {
						"notice.uv.placement.noPlacedArt"
					} else {
						"notice.uv.placement.pinned"
					}
				emitNotice(messageKey, NoticePlacement.NearCursor)

				return
			}
		}

		EditorMode.Edit -> {
			val selection = meshSelection.value

			if (selection.owners.isEmpty() || selection.isEmpty) {
				return
			}

			if (editableUvCoverage(selection, model, shownDrawableIds = null).isEmpty()) {
				emitNotice("notice.uv.noUvs", NoticePlacement.NearCursor)

				return
			}
		}

		// The UV editor is view-only in Pose mode: posing has nothing to do with the texture, so nothing moves.
		EditorMode.Pose -> {
			return
		}
	}

	latches.latchUvOperator(kind, areaId)
}

/**
 * Arms the Box-select tool (Blender's B): the gizmo overlay shows full-viewport crosshair guides and the
 * next drag boxes the session's elements in Edit and Pose mode, or whole objects in Object mode.
 *
 * @param String areaId The arming viewport's area id (only its overlay drives the drag).
 */
fun EditorSession.beginBoxSelect(areaId: String) {
	if (mode.value.editsElements && meshSelection.value.owners.isEmpty()) {
		return
	}

	latches.armBoxSelect(areaId)
}

/**
 * Arms the Circle-select tool (Blender's C) at the remembered radius, mode-agnostic like [beginBoxSelect].
 *
 * @param String areaId The arming viewport's area id (only its overlay drives the brush).
 */
fun EditorSession.beginCircleSelect(areaId: String) {
	if (mode.value.editsElements && meshSelection.value.owners.isEmpty()) {
		return
	}

	latches.armCircleSelect(areaId)
}