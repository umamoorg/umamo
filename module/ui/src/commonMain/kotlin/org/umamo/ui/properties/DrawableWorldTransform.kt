package org.umamo.ui.properties

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshBounds
import org.umamo.edit.MeshChange
import org.umamo.edit.Pose
import org.umamo.edit.isPoseNeutral
import org.umamo.edit.meshBounds
import org.umamo.edit.movedToBoundsCenter
import org.umamo.edit.resizedAboutBoundsCenter
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.movementToBase

/*
 * The Properties Transform panel's coordinate space.
 *
 * A drawable's Drawable.mesh.positions is its BASE array, and that is NOT the geometry on screen: the
 * displayed shape is `base + Σ wᵢ·Δᵢ` from the keyform grid, then mapped through the parent deformer chain
 * into world space.  For a drawable parented to a deformer the two diverge wildly - a corpus model has an
 * art mesh whose base array is 1.9 units wide while the drawable itself is 183.8 wide - so reading or
 * writing the base array directly shows meaningless numbers and turns a small typed nudge into a huge
 * transform.
 *
 * So the panel works in WORLD space, the same space the viewport and the object gizmo use, and reuses the
 * gizmo's round trip: capture world, transform world, invert to local, difference back onto base.  The
 * inverse (worldToLocalLinearized) is exact only at the neutral pose, which is why every write here is
 * gated on isPoseNeutral exactly as EditorSession.beginObjectOperator is - the panel disables its fields
 * rather than writing geometry it cannot invert.
 */

/**
 * The world-space geometry backing one drawable's Transform rows: the bounds to display, and whether they
 * can be written back.
 *
 * @property MeshBounds bounds The drawable's axis-aligned world bounds (x horizontal, y up = the panel's Z).
 * @property Boolean editable Whether an edit can be inverted back onto the base mesh (see [drawableWorldTransform]).
 */
internal class DrawableWorldTransform(val bounds: MeshBounds, val editable: Boolean)

/**
 * Resolves the world bounds of drawable [id] at [pose] - what the Transform rows show.
 *
 * Not editable when the pose is off neutral (the deformer-chain inverse is only exact there) or when the
 * drawable has no world mapping at all, which happens when an ancestor is hidden.  In that second case the
 * posed LOCAL geometry is reported instead so the rows still show the drawable's real proportions rather
 * than the misleading base array; it is marked non-editable, so nothing can be written from it.
 *
 * @param PuppetModel model The document model.
 * @param Pose pose The live parameter values.
 * @param DrawableId id The drawable to measure.
 * @return DrawableWorldTransform? The bounds and their editability, or null when the drawable has no mesh.
 */
internal fun drawableWorldTransform(model: PuppetModel, pose: Pose, id: DrawableId): DrawableWorldTransform? {
	val base = model.drawables.firstOrNull { drawable -> drawable.id == id }?.mesh?.positions ?: return null
	if (base.size < 2) {
		return null
	}
	val displayed = drawableLocalPosed(model, pose, id) ?: base
	val mapping = drawableSpaceMapping(model, pose, id)
	if (mapping == null) {
		return DrawableWorldTransform(meshBounds(displayed), editable = false)
	}
	return DrawableWorldTransform(meshBounds(mapping.localToWorld(displayed)), editable = isPoseNeutral(model, pose))
}

/**
 * Applies [transformWorld] to drawable [id]'s world geometry and commits the result as one undo step.
 *
 * The round trip mirrors the object gizmo: project the posed local geometry to world, let the caller
 * reshape it there, invert the result back to local through the deformer chain, then difference that
 * against the posed local shape to recover the new BASE array (a keyformed drawable's base is not its
 * displayed shape, so the delta is what carries over).  Refuses off the neutral pose, on a missing mesh or
 * mapping, and on a transform that returned its input unchanged - each records nothing.
 *
 * @param DrawableId id The drawable to transform.
 * @param MeshChange change The history descriptor for the edit.
 * @param Function transformWorld Reshapes the world positions; returning the same instance means no-op.
 */
private fun EditorSession.commitWorldTransform(
	id: DrawableId,
	change: MeshChange,
	transformWorld: (FloatArray) -> FloatArray,
) {
	val currentModel = model.value
	val currentPose = pose.value
	// The same guard beginObjectOperator applies: writing a deformed capture back through the warp inverse
	// corrupts the rest mesh, so the panel refuses rather than corrupting it.
	if (!isPoseNeutral(currentModel, currentPose)) {
		return
	}
	val base = currentModel.drawables.firstOrNull { drawable -> drawable.id == id }?.mesh?.positions ?: return
	val mapping = drawableSpaceMapping(currentModel, currentPose, id) ?: return
	val displayed = drawableLocalPosed(currentModel, currentPose, id) ?: base
	val world = mapping.localToWorld(displayed)
	val targetWorld = transformWorld(world)
	if (targetWorld === world) {
		return
	}
	val allIndices = (0 until world.size / 2).toSet()
	val newLocal = mapping.worldToLocalLinearized(targetWorld, displayed, world, allIndices)
	commitObjectPositions(change, mapOf(id to movementToBase(base, newLocal, displayed)))
}

/**
 * Moves drawable [id] so its world bounds center lands on ([centerX], [centerY]) - the Transform panel's
 * Position row - as one undo step.  [centerY] is the panel's Z (world y grows upward).
 *
 * @param DrawableId id The drawable to move.
 * @param Float centerX The world x its bounds center should land on.
 * @param Float centerY The world y (panel Z) its bounds center should land on.
 */
internal fun EditorSession.setDrawableWorldCenter(id: DrawableId, centerX: Float, centerY: Float) {
	commitWorldTransform(id, MeshChange.MoveDrawables(listOf(id))) { world ->
		movedToBoundsCenter(world, centerX, centerY)
	}
}

/**
 * Scales drawable [id] about its world bounds center so its world extents become ([width], [height]) - the
 * Transform panel's Size row - as one undo step.  The bounds center is the pivot, so resizing leaves the
 * Position row's numbers unchanged.
 *
 * @param DrawableId id The drawable to resize.
 * @param Float width The target world x extent.
 * @param Float height The target world y extent.
 */
internal fun EditorSession.setDrawableWorldSize(id: DrawableId, width: Float, height: Float) {
	commitWorldTransform(id, MeshChange.ResizeDrawables(listOf(id))) { world ->
		resizedAboutBoundsCenter(world, width, height)
	}
}
