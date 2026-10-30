package org.umamo.ui.transform

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.Pose
import org.umamo.edit.mesh.commitObjectTransform
import org.umamo.edit.transform.MeshBounds
import org.umamo.edit.transform.boundsScaleFactors
import org.umamo.edit.transform.meshBounds
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.scaleAboutAffine
import org.umamo.runtime.model.translationAffine

/*
 * The Properties Transform panel's rows for a warp, in the same world space the drawable rows use
 * (DrawableWorldTransform.kt has the why): the lattice's world box at the shown pose, and edits that move the warp
 * as a whole - every form it carries, its art following - exactly as an Object-mode G / S does.  Every write is
 * gated on the neutral pose, as the viewport's transform is.
 */

/**
 * Resolves the world bounds of warp [id]'s lattice at [pose] - what the Transform rows show.
 *
 * @param PuppetModel model The document model.
 * @param Pose pose The shown pose (EditorMode.shownPose): the rig's pose, or the rest pose in Edit mode.
 * @param DeformerId id The warp to measure.
 * @param EditorMode mode The editor mode.
 * @return ItemWorldTransform? The bounds and their editability ([writesWholeObjects]), or null when the pose does not
 *   draw the warp.
 */
internal fun warpWorldTransform(model: PuppetModel, pose: Pose, id: DeformerId, mode: EditorMode): ItemWorldTransform? {
	val lattice = warpLatticeAt(model, pose, id) ?: return null

	return ItemWorldTransform(meshBounds(lattice.world), editable = writesWholeObjects(model, pose, mode))
}

/**
 * One planned whole-warp move: the lattice it was planned against and the model it lands.
 *
 * @property LatticeWorldGeometry lattice The warp's lattice at rest, as captured.
 * @property PuppetModel moved The model with the warp moved.
 */
private class WarpTransformPlan(val lattice: LatticeWorldGeometry, val moved: PuppetModel)

/**
 * Plans moving warp [id] by the world affine [affineFor] gives for its lattice's bounds, without writing anything.
 * Refuses where the rows may not write ([writesWholeObjects]), on a warp the rest pose does not draw, and when
 * [affineFor] has nothing to move.
 *
 * @param PuppetModel model The document model.
 * @param Pose pose The shown pose.
 * @param EditorMode mode The editor mode.
 * @param DeformerId id The warp.
 * @param Function affineFor The world affine for the lattice's bounds, or null for no move.
 * @return WarpTransformPlan? The plan, or null when the edit is refused or a no-op.
 */
private fun planWarpTransform(model: PuppetModel, pose: Pose, mode: EditorMode, id: DeformerId, affineFor: (MeshBounds) -> FloatArray?): WarpTransformPlan? {
	if (!writesWholeObjects(model, pose, mode)) {
		return null
	}

	val lattice = captureLatticeWorld(model, id) ?: return null
	val affine = affineFor(meshBounds(lattice.world)) ?: return null
	val moved = model.withWarpsTransformed(mapOf(id to affine))

	return if (moved === model) null else WarpTransformPlan(lattice, moved)
}

/**
 * The world affine that moves a box's center to ([centerX], [centerZ]), or null when it is there already.
 *
 * @param MeshBounds bounds The box.
 * @param Float centerX The world x the center should land on.
 * @param Float centerZ The world z (up) the center should land on.
 * @return FloatArray? The translation.
 */
private fun centeringAffine(bounds: MeshBounds, centerX: Float, centerZ: Float): FloatArray? {
	val deltaX = centerX - bounds.centerX
	val deltaZ = centerZ - bounds.centerY

	return if (deltaX == 0f && deltaZ == 0f) null else translationAffine(deltaX, deltaZ)
}

/**
 * The world affine that scales a box about its center to ([width], [height]), or null when it is that size
 * already.  A degenerate axis has no extent to scale and keeps a factor of 1.
 *
 * @param MeshBounds bounds The box.
 * @param Float width The target world x extent.
 * @param Float height The target world z extent.
 * @return FloatArray? The scale about the box's center.
 */
private fun resizingAffine(bounds: MeshBounds, width: Float, height: Float): FloatArray? {
	val (factorX, factorZ) = boundsScaleFactors(bounds, width, height)

	if (factorX == 1f && factorZ == 1f) {
		return null
	}

	return scaleAboutAffine(bounds.centerX, bounds.centerY, factorX, factorZ)
}

/**
 * Moves warp [id] as a whole so its lattice's world bounds center lands on ([centerX], [centerZ]) - the Transform
 * panel's Position row - as one undo step.  Both are WORLD coordinates: the row converts its origin-relative value
 * to world before calling this.
 *
 * @param DeformerId id The warp to move.
 * @param Float centerX The world x its bounds center should land on.
 * @param Float centerZ The world z (up) its bounds center should land on.
 * @return LatticeWorldGeometry? The lattice the move was planned against, or null when it was refused.
 */
internal fun EditorSession.setWarpWorldCenter(id: DeformerId, centerX: Float, centerZ: Float): LatticeWorldGeometry? {
	val plan = planWarpTransform(model.value, shownPose, mode.value, id) { bounds -> centeringAffine(bounds, centerX, centerZ) } ?: return null
	commitObjectTransform(MeshChange.TransformObjects(emptyList(), MeshOperatorKind.Grab, listOf(id)), plan.moved)

	return plan.lattice
}

/**
 * The model [setWarpWorldCenter] would record for the same arguments, unrecorded - the Position row's scrub preview.
 *
 * @param DeformerId id The warp to move.
 * @param Float centerX The world x its bounds center should land on.
 * @param Float centerZ The world z (up) its bounds center should land on.
 * @return PuppetModel? The previewed model, or null when the move would record nothing.
 */
internal fun EditorSession.previewWarpWorldCenter(id: DeformerId, centerX: Float, centerZ: Float): PuppetModel? =
	planWarpTransform(model.value, shownPose, mode.value, id) { bounds -> centeringAffine(bounds, centerX, centerZ) }?.moved

/**
 * Scales warp [id] as a whole about its lattice's world bounds center so the extents become ([width], [height]) -
 * the Transform panel's Size row - as one undo step.  The bounds center is the pivot, so resizing leaves the
 * Position row's numbers unchanged.
 *
 * @param DeformerId id The warp to resize.
 * @param Float width The target world x extent.
 * @param Float height The target world z extent.
 * @return LatticeWorldGeometry? The lattice the resize was planned against, or null when it was refused.
 */
internal fun EditorSession.setWarpWorldSize(id: DeformerId, width: Float, height: Float): LatticeWorldGeometry? {
	val plan = planWarpTransform(model.value, shownPose, mode.value, id) { bounds -> resizingAffine(bounds, width, height) } ?: return null
	// Scale, though no modal operator ran: the kind names what the transform WAS, not how it was driven.
	commitObjectTransform(MeshChange.TransformObjects(emptyList(), MeshOperatorKind.Scale, listOf(id)), plan.moved)

	return plan.lattice
}

/**
 * The model [setWarpWorldSize] would record for the same arguments, unrecorded - the Size row's scrub preview.
 *
 * @param DeformerId id The warp to resize.
 * @param Float width The target world x extent.
 * @param Float height The target world z extent.
 * @return PuppetModel? The previewed model, or null when the resize would record nothing.
 */
internal fun EditorSession.previewWarpWorldSize(id: DeformerId, width: Float, height: Float): PuppetModel? =
	planWarpTransform(model.value, shownPose, mode.value, id) { bounds -> resizingAffine(bounds, width, height) }?.moved