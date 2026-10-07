package org.umamo.ui.properties

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.edit.transform.meshBounds
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.transform.setDrawableWorldCenter
import org.umamo.ui.transform.setDrawableWorldSize
import org.umamo.ui.viewport.viewport2d.registerDrawableWorldAdjustment

/*
 * The Transform rows' commits with their operation-strip registration: a Position edit is a Grab and a
 * Size edit a Scale of one drawable, so each registers the rows the viewport's G / S register (Move X /
 * Move Z, Scale X / Scale Z) over the same rerun, and the strip adjusts it the same way.
 *
 * Registered only when the commit changed the model, the gate the object gizmo keeps: the session pairs
 * a registration with its LAST push, so an edit that recorded nothing after an unregistered push (a
 * selection step) would otherwise register against that other step's base.  A refused or no-op plan
 * returns no geometry and never reaches the gate; the gate covers the fold's own ruling, which the
 * setters cannot see.
 */

/**
 * Moves drawable [id]'s world bounds center to ([centerX], [centerZ]) as one undo step (see
 * [setDrawableWorldCenter]) and registers the move on the operation strip in [areaId], as a Grab by the
 * delta from where the center was.
 *
 * @param DrawableId id The drawable to move.
 * @param Float centerX The world x its bounds center should land on.
 * @param Float centerZ The world z (up) its bounds center should land on.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.setDrawableWorldCenterAdjustable(id: DrawableId, centerX: Float, centerZ: Float, areaId: String?) {
	val modelBefore = model.value
	val geometry = setDrawableWorldCenter(id, centerX, centerZ) ?: return
	if (model.value === modelBefore) {
		return
	}
	// The delta the setter moved by, from the same bounds it measured.
	val bounds = meshBounds(geometry.world)
	val parameters = TransformGestureParameters(centerX - bounds.centerX, centerZ - bounds.centerY, 1f, 1f, 0f)
	registerDrawableWorldAdjustment(this, areaId, geometry, MeshOperatorKind.Grab, parameters)
}

/**
 * Scales drawable [id] about its world bounds center to ([width], [height]) as one undo step (see
 * [setDrawableWorldSize]) and registers the resize on the operation strip in [areaId], as a Scale by the
 * factors the extents changed by.  A degenerate axis has no extent to scale, which the setter leaves alone,
 * so its factor registers as 1.
 *
 * @param DrawableId id The drawable to resize.
 * @param Float width The target world x extent.
 * @param Float height The target world y extent.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.setDrawableWorldSizeAdjustable(id: DrawableId, width: Float, height: Float, areaId: String?) {
	val modelBefore = model.value
	val geometry = setDrawableWorldSize(id, width, height) ?: return
	if (model.value === modelBefore) {
		return
	}
	val bounds = meshBounds(geometry.world)
	val factorX = if (bounds.width > 0f) width / bounds.width else 1f
	val factorZ = if (bounds.height > 0f) height / bounds.height else 1f
	val parameters = TransformGestureParameters(0f, 0f, factorX, factorZ, 0f)
	registerDrawableWorldAdjustment(this, areaId, geometry, MeshOperatorKind.Scale, parameters)
}