package org.umamo.ui.transform

import org.umamo.edit.Pose
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.DrawableSpaceResolver
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/*
 * The spaces a drawable's geometry lives in, and the round trip between them.
 *
 * Every caller that transforms a whole drawable - the object gizmo's G / S / R, the Shift+S snaps, the
 * Properties Transform rows - needs the same capture (the rest arrays, the posed local shape, and that shape
 * through the deformer chain into world) and ends with the same write-back (invert the transformed world
 * shape to local and difference it onto the base, then move the canvas mesh by the same world movement).
 * The capture is easy to get subtly wrong, because neither rest array is the displayed shape for a
 * keyformed drawable and the difference is what carries the edit across that gap.
 *
 * Lives in :ui rather than :edit because the mapping comes from :render's evaluator, and :edit and :render
 * are siblings over :runtime - :edit cannot see it.
 */

/**
 * One drawable captured in every space at a fixed pose.
 *
 * The arrays are snapshots, not live views: a gesture captures once at the start and transforms the
 * captured [world] every frame, so an in-flight preview never compounds on its own output.
 *
 * @property DrawableId drawableId The captured drawable.
 * @property DrawableSpaceMapping mapping The local<->world mapping for the deformer chain at this pose.
 * @property FloatArray canvas The canvas editable mesh (DrawableMesh.positions).
 * @property FloatArray local The keyform-space base the deltas are measured from (DrawableMesh.localPositions);
 *   the same instance as [canvas] when the mesh shares one array.
 * @property FloatArray displayed The posed LOCAL shape - [local] plus the keyform blend; equals [local] for a
 *   drawable with no keyform grid.
 * @property FloatArray world [displayed] projected through the deformer chain - what the viewport shows.
 */
internal class DrawableWorldGeometry(
	val drawableId: DrawableId,
	val mapping: DrawableSpaceMapping,
	val canvas: FloatArray,
	val local: FloatArray,
	val displayed: FloatArray,
	val world: FloatArray,
) {
	/** Every vertex index of this drawable - the whole-mesh set an object-level transform moves. */
	val allIndices: Set<Int> get() = (0 until world.size / 2).toSet()

	/**
	 * Inverts a transformed WORLD shape back onto the rest arrays - the write-back every whole-drawable
	 * transform ends with.
	 *
	 * The base takes two steps, and both matter.  The world shape is mapped back to local through the
	 * deformer chain (exact only at the neutral pose, which is why callers gate on isPoseNeutral), and the
	 * result is then DIFFERENCED against the captured displayed shape rather than written directly - see
	 * [movementOnto] for why that is what leaves the keyform grid untouched.  The canvas mesh moves by the
	 * world movement itself, the space it is drawn in, with the y flip undone; for a mesh that shares one
	 * array the two are the same move, and the result shares one array again.
	 *
	 * @param FloatArray transformedWorld The reshaped world positions.
	 * @param Set<Int> indices The vertices the transform touched (the whole mesh for an object transform).
	 * @return MeshRestPositions The new canvas mesh and base (fresh arrays).
	 */
	fun worldToRest(transformedWorld: FloatArray, indices: Set<Int> = allIndices): MeshRestPositions {
		val transformedLocal = mapping.worldToLocalLinearized(transformedWorld, displayed, world, indices)
		val newLocal = movementOnto(local, transformedLocal, displayed)
		if (local === canvas) {
			return MeshRestPositions.shared(newLocal)
		}
		val newCanvas = canvas.copyOf()
		for (vertexIndex in indices) {
			val xIndex = vertexIndex * 2
			if (xIndex + 1 >= newCanvas.size || xIndex + 1 >= transformedWorld.size || xIndex + 1 >= world.size) {
				continue
			}
			newCanvas[xIndex] = canvas[xIndex] + (transformedWorld[xIndex] - world[xIndex])
			newCanvas[xIndex + 1] = canvas[xIndex + 1] - (transformedWorld[xIndex + 1] - world[xIndex + 1])
		}
		return MeshRestPositions(newCanvas, newLocal)
	}

	/**
	 * A copy of this capture whose arrays nothing else holds, for a gesture that must transform a fixed
	 * snapshot.  A mesh that shares one array for its canvas mesh and base keeps sharing it in the copy,
	 * which is what lets [worldToRest] write the pair back as one array.
	 *
	 * @return DrawableWorldGeometry The copy.
	 */
	fun frozenCopy(): DrawableWorldGeometry {
		val canvasCopy = canvas.copyOf()
		val localCopy = if (local === canvas) canvasCopy else local.copyOf()
		return DrawableWorldGeometry(drawableId, mapping, canvasCopy, localCopy, displayed.copyOf(), world.copyOf())
	}
}

/**
 * Captures [drawableId] in every space at [pose].
 *
 * Null when the drawable carries no mesh, or when it has no world mapping at all - which happens when an
 * ancestor is hidden.  A caller sweeping a selection should SKIP a null rather than abort, so one hidden
 * drawable does not block a gesture over the others - and should sweep through [captureDrawableWorlds],
 * since this builds a resolver, and so bakes the deformer chain, per call.
 *
 * @param PuppetModel model The document model.
 * @param Pose pose The parameter values to capture at.
 * @param DrawableId drawableId The drawable to capture.
 * @return DrawableWorldGeometry? The capture, or null when the drawable has no mesh or no mapping.
 */
internal fun captureDrawableWorld(model: PuppetModel, pose: Pose, drawableId: DrawableId): DrawableWorldGeometry? =
	captureThrough(DrawableSpaceResolver(model, pose), drawableId)

/**
 * Captures every drawable of [drawableIds] in every space at [pose] through ONE resolver, so the
 * deformer worlds bake once for the batch rather than once per drawable (a loop over
 * [captureDrawableWorld] rebakes every world per call: 1330 x 623 on modelF, seconds per Edit-mode
 * commit or Object-mode latch).  Per drawable the answer is exactly [captureDrawableWorld]'s, including
 * the null for a mesh-less drawable or a hidden ancestor, which is dropped so a sweep over a selection
 * skips the hidden rather than aborting.  The result follows the request order.
 *
 * @param PuppetModel model The document model.
 * @param Pose pose The parameter values to capture at.
 * @param Iterable<DrawableId> drawableIds The drawables to capture.
 * @return List<DrawableWorldGeometry> The captures in request order, without the drawables that have
 *   no mesh or no mapping.
 */
internal fun captureDrawableWorlds(model: PuppetModel, pose: Pose, drawableIds: Iterable<DrawableId>): List<DrawableWorldGeometry> {
	val resolver = DrawableSpaceResolver(model, pose)
	return drawableIds.mapNotNull { drawableId -> captureThrough(resolver, drawableId) }
}

/**
 * The capture itself, over a resolver the caller built: the stored rest arrays, the mapping, the posed local
 * shape (the base itself when the pose leaves the grid), and that shape through the chain.
 *
 * @param DrawableSpaceResolver resolver The (model, pose) the capture reads.
 * @param DrawableId drawableId The drawable to capture.
 * @return DrawableWorldGeometry? The capture, or null when the drawable has no mesh or no mapping.
 */
private fun captureThrough(resolver: DrawableSpaceResolver, drawableId: DrawableId): DrawableWorldGeometry? {
	val mesh = resolver.drawable(drawableId)?.mesh ?: return null
	val mapping = resolver.mapping(drawableId) ?: return null
	val displayed = resolver.localPosed(drawableId) ?: mesh.localPositions
	return DrawableWorldGeometry(drawableId, mapping, mesh.positions, mesh.localPositions, displayed, mapping.localToWorld(displayed))
}

/**
 * Transfers a displayed-shape movement onto the base: `newBase = base + (after - before)`.  The rest shape a
 * rigger sees is base + the neutral keyform blend; because the blend cancels out of the subtraction, the
 * moved rest shape re-renders exactly at `after` while only the base is written - no keyform cell is
 * touched, and blend-shape deltas (relative to the base) follow the edit.  For a grid-less drawable
 * `before` equals the base, so this degenerates to `newBase = after`.
 *
 * The one caller is [DrawableWorldGeometry.worldToRest], which owns the second half of every whole-drawable
 * write-back; the overlays and the Properties panel all reach it through that method rather than directly.
 *
 * @param FloatArray base The base captured at gesture start.
 * @param FloatArray after The transformed displayed shape.
 * @param FloatArray before The displayed shape captured at gesture start.
 * @return FloatArray The new base (a fresh array).
 */
private fun movementOnto(base: FloatArray, after: FloatArray, before: FloatArray): FloatArray =
	FloatArray(base.size) { coordIndex ->
		if (coordIndex < after.size && coordIndex < before.size) {
			base[coordIndex] + after[coordIndex] - before[coordIndex]
		} else {
			base[coordIndex]
		}
	}