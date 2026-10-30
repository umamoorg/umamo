package org.umamo.ui.properties

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.transform.MeshBounds
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.edit.transform.boundsScaleFactors
import org.umamo.edit.transform.meshBounds
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.transform.OwnerWorldGeometry
import org.umamo.ui.transform.setDrawableWorldCenter
import org.umamo.ui.transform.setDrawableWorldSize
import org.umamo.ui.transform.setWarpWorldCenter
import org.umamo.ui.transform.setWarpWorldSize
import org.umamo.ui.viewport.viewport2d.registerWorldAdjustment

/*
 * The Transform rows' commits with their operation-strip registration: a Position edit is a Grab and a
 * Size edit a Scale of one drawable or warp, so each registers the rows the viewport's G / S register (Move X /
 * Move Z, Scale X / Scale Z) over the same rerun, and the strip adjusts it the same way.
 *
 * Registered only when the commit changed the model, the gate the object gizmo keeps: the session pairs
 * a registration with its LAST push, so an edit that recorded nothing after an unregistered push (a
 * selection step) would otherwise register against that other step's base.  A refused or no-op plan
 * returns no geometry and never reaches the gate; the gate covers the fold's own ruling, which the
 * setters cannot see.
 */

/**
 * Moves drawable [id]'s world bounds center to ([centerX], [centerZ]) as one undo step (see [setDrawableWorldCenter])
 * and registers the move on the operation strip in [areaId], as a Grab by the delta from where the center was.
 *
 * @param DrawableId id The drawable to move.
 * @param Float centerX The world x its bounds center should land on.
 * @param Float centerZ The world z (up) its bounds center should land on.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.setDrawableWorldCenterAdjustable(id: DrawableId, centerX: Float, centerZ: Float, areaId: String?) {
	adjustWorldBounds(areaId, MeshOperatorKind.Grab, { setDrawableWorldCenter(id, centerX, centerZ) }) { bounds -> centeringParameters(bounds, centerX, centerZ) }
}

/**
 * Scales drawable [id] about its world bounds center to ([width], [height]) as one undo step (see
 * [setDrawableWorldSize]) and registers the resize on the operation strip in [areaId], as a Scale by the factors the
 * extents changed by; a degenerate axis, which the setter leaves alone, registers a factor of 1.
 *
 * @param DrawableId id The drawable to resize.
 * @param Float width The target world x extent.
 * @param Float height The target world z extent.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.setDrawableWorldSizeAdjustable(id: DrawableId, width: Float, height: Float, areaId: String?) {
	adjustWorldBounds(areaId, MeshOperatorKind.Scale, { setDrawableWorldSize(id, width, height) }) { bounds -> resizingParameters(bounds, width, height) }
}

/**
 * Moves warp [id] as a whole so its lattice's world bounds center lands on ([centerX], [centerZ]) as one undo step
 * (see [setWarpWorldCenter]) and registers the move on the operation strip in [areaId], as a Grab by the delta from
 * where the center was.
 *
 * @param DeformerId id The warp to move.
 * @param Float centerX The world x its bounds center should land on.
 * @param Float centerZ The world z (up) its bounds center should land on.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.setWarpWorldCenterAdjustable(id: DeformerId, centerX: Float, centerZ: Float, areaId: String?) {
	adjustWorldBounds(areaId, MeshOperatorKind.Grab, { setWarpWorldCenter(id, centerX, centerZ) }) { bounds -> centeringParameters(bounds, centerX, centerZ) }
}

/**
 * Scales warp [id] as a whole about its lattice's world bounds center to ([width], [height]) as one undo step (see
 * [setWarpWorldSize]) and registers the resize on the operation strip in [areaId], as a Scale by the factors the
 * extents changed by; a degenerate axis registers a factor of 1.
 *
 * @param DeformerId id The warp to resize.
 * @param Float width The target world x extent.
 * @param Float height The target world z extent.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.setWarpWorldSizeAdjustable(id: DeformerId, width: Float, height: Float, areaId: String?) {
	adjustWorldBounds(areaId, MeshOperatorKind.Scale, { setWarpWorldSize(id, width, height) }) { bounds -> resizingParameters(bounds, width, height) }
}

/**
 * Runs one Transform row commit and, when it changed the model, registers it on the operation strip in [areaId] with
 * the parameters [parametersOf] reads off the bounds the commit measured: the one path every row write takes.
 *
 * @param String? areaId The area the strip should show in, or null for nowhere.
 * @param MeshOperatorKind kind Grab for a move, Scale for a resize.
 * @param Function commit Commits the edit, answering the geometry it was planned against, or null when refused.
 * @param Function parametersOf The strip's parameters for the commit, from that geometry's world bounds.
 */
private fun EditorSession.adjustWorldBounds(
	areaId: String?,
	kind: MeshOperatorKind,
	commit: () -> OwnerWorldGeometry?,
	parametersOf: (MeshBounds) -> TransformGestureParameters,
) {
	val modelBefore = model.value
	val geometry = commit() ?: return

	if (model.value === modelBefore) {
		return
	}

	registerWorldAdjustment(this, areaId, geometry, kind, parametersOf(meshBounds(geometry.world)))
}

/**
 * A Grab's parameters: the delta from [bounds]' center to ([centerX], [centerZ]).
 *
 * @param MeshBounds bounds The bounds before the move.
 * @param Float centerX The world x the center moved to.
 * @param Float centerZ The world z the center moved to.
 * @return TransformGestureParameters The parameters.
 */
private fun centeringParameters(bounds: MeshBounds, centerX: Float, centerZ: Float): TransformGestureParameters =
	TransformGestureParameters(centerX - bounds.centerX, centerZ - bounds.centerY, 1f, 1f, 0f)

/**
 * A Scale's parameters: the factors that take [bounds] to ([width], [height]) ([boundsScaleFactors]).
 *
 * @param MeshBounds bounds The bounds before the resize.
 * @param Float width The world x extent it resized to.
 * @param Float height The world z extent it resized to.
 * @return TransformGestureParameters The parameters.
 */
private fun resizingParameters(bounds: MeshBounds, width: Float, height: Float): TransformGestureParameters {
	val (factorX, factorZ) = boundsScaleFactors(bounds, width, height)

	return TransformGestureParameters(0f, 0f, factorX, factorZ, 0f)
}