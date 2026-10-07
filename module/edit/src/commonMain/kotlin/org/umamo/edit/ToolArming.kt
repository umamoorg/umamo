package org.umamo.edit

/*
 * The guards between a command and a tool latch: the begin / arm entry points for the modal mesh, object,
 * and UV operators and the Box / Circle select tools.  Each checks the mode, the selection, and (for an
 * Object-mode transform) the pose before it lets the session's latches take the tool, and explains a
 * refusal with a near-cursor notice where a rigger could have meant the command.  The latches themselves,
 * and the clears the overlays call on confirm or cancel, are reached on the session through
 * SessionToolLatches.
 */

/**
 * Latches a modal mesh operator so the gizmo overlay begins the gesture. A no-op unless Edit mode is
 * active with a drawable and a non-empty selection — so the bound G / S / R commands need no context
 * guard of their own and the keymap stays mode-agnostic. For an edge or face selection the gesture
 * moves the union of vertices the selected elements cover (resolved in the overlay).
 *
 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
 * @param Boolean suppressProportional True to ignore proportional editing for this gesture (the
 *   duplicate / rip auto-grabs; treated like Vertex Slide at every proportional gate).
 */
fun EditorSession.beginMeshOperator(kind: MeshOperatorKind, areaId: String, suppressProportional: Boolean = false) {
	if (mode.value != EditorMode.Edit) {
		return
	}
	val selection = meshSelection.value
	if (selection.drawableIds.isEmpty() || selection.isEmpty) {
		return
	}
	latches.latchMeshOperator(kind, areaId, suppressProportional)
}

/**
 * Begins an Object-mode modal transform (Grab / Scale / Rotate) over the selected drawables' whole
 * geometry - the Object-mode counterpart to [beginMeshOperator]. A no-op unless Object mode is active
 * with an eligible selection: at least one selected target must be a drawable that carries a mesh (see
 * [eligibleTransformDrawables]; parts, deformers, and mesh-less drawables in the selection are silently
 * ignored, so a Select All that swept them in never blocks the gesture). The gesture is BLOCKED with a
 * near-cursor notice when the pose is not at parameter defaults: the object overlay captures at the live
 * pose, and writing a deformed capture back through the warp inverse corrupts the rest meshes - the
 * Blender-style guard tells the user to reset the parameters first. Clears any other latched tool /
 * operator (mutual exclusion) before latching.
 *
 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
 */
fun EditorSession.beginObjectOperator(kind: MeshOperatorKind, areaId: String) {
	if (mode.value != EditorMode.Object) {
		return
	}
	if (eligibleTransformDrawables(selection.value, model.value) == null) {
		// Nothing transformable at all (empty, or only parts / deformers / mesh-less drawables).
		emitNotice("notice.transform.onlyDrawables", NoticePlacement.NearCursor)
		return
	}
	if (!isPoseNeutral(model.value, pose.value)) {
		// Transforming rest geometry while the displayed pose is deformed would write garbage through
		// the deformer inverse; refuse and tell the user how to proceed (see the docblock).
		emitNotice("notice.transform.deformed", NoticePlacement.NearCursor)
		return
	}
	latches.latchObjectOperator(kind, areaId)
}

/**
 * Latches a modal UV operator so the UV editor's overlay begins the gesture - the UV-editor
 * counterpart to [beginMeshOperator].  What the gesture moves follows the mode: in Edit mode the
 * selected texture coordinates, in Object mode the selected drawables' atlas PLACEMENTS (the art
 * itself on its page, with the coordinates over it re-derived on commit).  Either way the bound
 * G / S / R commands stay mode-agnostic.  Vertex Slide is refused in both (it is rest-geometry math;
 * Blender's UV editor has no slide either).
 *
 * Edit mode is a silent no-op on an empty selection and BLOCKED with a near-cursor notice when no
 * covered mesh carries an editable UV array (imports may leave uvs empty), since latching would show
 * a modal HUD that can never commit anything.  Object mode is blocked with a notice when the stored
 * coordinates address the art rather than the pages (a placement is meaningless there), when
 * nothing selected is bound to packed art, and when every placed tile under the selection is
 * pinned (a pin holds against a hand move too).  Which page the overlay is showing - and whether it is
 * showing a page at all rather than a source layer - is per-area state the session cannot see, so
 * the overlay that owns the latch drops it with its own notice when its surface cannot serve the
 * gesture.  Clears any other latched tool / operator (mutual exclusion) before latching.
 *
 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
 * @param String areaId The initiating UV editor's area id (only its overlay drives the gesture).
 */
fun EditorSession.beginUvOperator(kind: MeshOperatorKind, areaId: String) {
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
			if (selection.drawableIds.isEmpty() || selection.isEmpty) {
				return
			}
			if (editableUvCoverage(selection, model, shownDrawableIds = null).isEmpty()) {
				emitNotice("notice.uv.noUvs", NoticePlacement.NearCursor)
				return
			}
		}
	}
	latches.latchUvOperator(kind, areaId)
}

/**
 * Arms the Box-select tool (Blender's B): the gizmo overlay shows full-viewport crosshair guides and the
 * next drag boxes.  Mode-agnostic - in Edit mode it needs an active drawable (the box selects that mesh's
 * elements); in Object mode it arms unconditionally (the box selects whole drawables).  A no-op in Edit
 * mode without a drawable.  Clears any other latched tool / operator (mutual exclusion).
 *
 * @param String areaId The arming viewport's area id (only its overlay drives the drag).
 */
fun EditorSession.beginBoxSelect(areaId: String) {
	if (mode.value == EditorMode.Edit && meshSelection.value.drawableIds.isEmpty()) {
		return
	}
	latches.armBoxSelect(areaId)
}

/**
 * Arms the Circle-select tool (Blender's C) at the remembered radius.  Mode-agnostic like [beginBoxSelect]:
 * needs an active drawable in Edit mode, arms unconditionally in Object mode.  Clears any other latched
 * tool / operator (mutual exclusion).
 *
 * @param String areaId The arming viewport's area id (only its overlay drives the brush).
 */
fun EditorSession.beginCircleSelect(areaId: String) {
	if (mode.value == EditorMode.Edit && meshSelection.value.drawableIds.isEmpty()) {
		return
	}
	latches.armCircleSelect(areaId)
}