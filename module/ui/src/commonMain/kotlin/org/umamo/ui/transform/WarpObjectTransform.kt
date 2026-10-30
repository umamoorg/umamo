package org.umamo.ui.transform

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.SelectionTarget
import org.umamo.edit.editOwnerOf
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.edit.mesh.commitObjectPositions
import org.umamo.edit.mesh.commitObjectTransform
import org.umamo.edit.mesh.withMeshPositions
import org.umamo.edit.transform.MeshTransforms
import org.umamo.edit.transform.allPointIndices
import org.umamo.render.eval.ParentSpaceMapping
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.keyform.withEveryLatticeMapped
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.applyAffine

/*
 * Whole warps moved as objects, lowered onto the model.  Object mode moves a warp the way it moves a drawable: as
 * one thing.  Every grid cell and every blend-shape form maps through the same world transform, so the keyed motion
 * goes with the warp, and the art inside it follows because its points live in the warp's lattice.  Lives in :ui
 * because each form maps through the warp's parent, which needs :render's evaluator.
 */

/**
 * Warp [warpId] moved as a whole by [worldAffine]: every point of every form it carries is taken through its parent
 * at rest into world space, moved, and brought back.  Under a root or rotation parent that is the parent-space form
 * of the affine itself, so at every pose the evaluated lattice in the parent's space is that affine of the old one -
 * its keyed motion goes with it, and a posed parent carries it as it did before (at rest it lands on [worldAffine]
 * exactly).  Under a warp parent it is a per-point inverse through the parent's rest lattice.
 *
 * @param PuppetModel model The model.
 * @param DeformerId warpId The warp.
 * @param FloatArray worldAffine The 2x3 row-major world affine `[a, b, c, d, e, f]`, x right and z up.
 * @return Deformer.Warp? The moved warp, or null when the model holds no such warp or the rest pose does not draw it.
 */
internal fun landedWarp(model: PuppetModel, warpId: DeformerId, worldAffine: FloatArray): Deformer.Warp? {
	val warp = model.deformers.firstOrNull { deformer -> deformer.id == warpId } as? Deformer.Warp ?: return null
	val mapping = warpLatticeAt(model, emptyMap(), warpId)?.parentMapping ?: return null

	return warp.withEveryLatticeMapped { points -> movedThroughParent(points, mapping, worldAffine) }
}

/**
 * [model] with every warp in [worldAffineById] moved as a whole ([landedWarp]).  The warps must not nest - an
 * Object-mode transform moves only the outermost selected items - so each one maps through a parent that stays put.
 *
 * @param PuppetModel model The model.
 * @param Map<DeformerId, FloatArray> worldAffineById Each moved warp's world affine.
 * @return PuppetModel The model with the warps moved; the same instance when none could be.
 */
internal fun PuppetModel.withWarpsTransformed(worldAffineById: Map<DeformerId, FloatArray>): PuppetModel {
	val landedById = worldAffineById.mapNotNull { (warpId, affine) -> landedWarp(this, warpId, affine)?.let { landed -> warpId to landed } }.toMap()

	return withWarpsReplaced(landedById)
}

/**
 * [model] with each warp in [landedById] swapped for its moved form.
 *
 * @param Map<DeformerId, Deformer.Warp> landedById The moved warps.
 * @return PuppetModel The model; the same instance when there is nothing to swap.
 */
internal fun PuppetModel.withWarpsReplaced(landedById: Map<DeformerId, Deformer.Warp>): PuppetModel {
	if (landedById.isEmpty()) {
		return this
	}

	return copy(deformers = deformers.map { deformer -> landedById[deformer.id] ?: deformer })
}

/**
 * What a drag shows of a moved warp before it lands: its grid lattice at the default pose, in its parent's space -
 * what the renderer's lattice override stands in for.  Read off the moved warp itself, so the preview is exactly what
 * the confirm commits, a default lying between two keys included.
 *
 * @param PuppetModel model The model the warp moves in.
 * @param Deformer.Warp landed The moved warp.
 * @return WarpLatticeForm? The lattice, or null when the rest pose does not draw it.
 */
internal fun warpPreviewOf(model: PuppetModel, landed: Deformer.Warp): WarpLatticeForm? =
	warpLatticeAt(model.withWarpsReplaced(mapOf(landed.id to landed)), emptyMap(), landed.id)?.let { lattice -> WarpLatticeForm(lattice.gridLocal) }

/**
 * [points] in a parent's space, moved by [worldAffine] in world space and brought back through [mapping].
 *
 * @param FloatArray points Interleaved x, y points in the parent's space.
 * @param ParentSpaceMapping mapping Between the parent's space and world space at rest.
 * @param FloatArray worldAffine The world affine.
 * @return FloatArray The moved points in the parent's space (a new array).
 */
private fun movedThroughParent(points: FloatArray, mapping: ParentSpaceMapping, worldAffine: FloatArray): FloatArray {
	val world = mapping.localToWorld(points)

	return mapping.worldToLocalLinearized(applyAffine(world, worldAffine), points, allPointIndices(points))
}

/**
 * The centroid of [active]'s own geometry among [geometries]: what an Object-mode transform's Active Element pivot and
 * a Snap to Active read.  Null when nothing is active or the active item is not among them.
 *
 * @param SelectionTarget? active The object selection's active item.
 * @param List<OwnerWorldGeometry> geometries The captured drawables and lattices.
 * @return Pair<Float, Float>? The centroid, x and z, or null.
 */
internal fun activeObjectCentroid(active: SelectionTarget?, geometries: List<OwnerWorldGeometry>): Pair<Float, Float>? {
	val activeOwner = editOwnerOf(active) ?: return null

	return geometries.firstOrNull { geometry -> geometry.owner == activeOwner }?.let { geometry -> MeshTransforms.medianPivot(geometry.world, geometry.allIndices) }
}

/**
 * Commits an Object-mode move as one undo step: each drawable's new rest shape, and each warp moved whole by its world
 * affine with its art following ([withWarpsTransformed]).  What a confirmed G / R / S and a selection snap land through.
 *
 * @param List<DrawableId> drawableIds The drawables the move named.
 * @param Map<DrawableId, MeshRestPositions> restByDrawable Their new rest shapes.
 * @param Map<DeformerId, FloatArray> warpAffines Each moved warp's world affine.
 * @param MeshOperatorKind kind The operator the move ran as.
 */
internal fun EditorSession.commitObjectMove(
	drawableIds: List<DrawableId>,
	restByDrawable: Map<DrawableId, MeshRestPositions>,
	warpAffines: Map<DeformerId, FloatArray>,
	kind: MeshOperatorKind,
) {
	if (warpAffines.isNotEmpty()) {
		val landed = model.value.withMeshPositions(restByDrawable).withWarpsTransformed(warpAffines)
		commitObjectTransform(MeshChange.TransformObjects(drawableIds, kind, warpAffines.keys.toList()), landed)
	} else if (restByDrawable.isNotEmpty()) {
		commitObjectPositions(MeshChange.TransformObjects(drawableIds, kind), restByDrawable)
	}
}