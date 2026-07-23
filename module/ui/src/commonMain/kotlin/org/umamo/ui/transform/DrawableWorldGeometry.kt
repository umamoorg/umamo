package org.umamo.ui.transform

import org.umamo.edit.Pose
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/*
 * The three spaces a drawable's geometry lives in, and the round trip between them.
 *
 * Every caller that transforms a whole drawable - the object gizmo's G / S / R, the Shift+S snaps, the
 * Properties Transform rows - needs the same capture (base, the posed local shape, and that shape through
 * the deformer chain into world) and ends with the same write-back (invert the transformed world shape to
 * local, then difference it onto base).  Both halves were open-coded at four sites before this file; the
 * capture in particular is easy to get subtly wrong, because base is NOT the displayed shape for a
 * keyformed drawable and the difference is what carries the edit across that gap.
 *
 * Lives in :ui rather than :edit because the mapping comes from :render's evaluator, and :edit and :render
 * are siblings over :runtime - :edit cannot see it.  See the plan's Context for why that layering stands.
 *
 * 描画オブジェクトの 3 つの空間（base / 表示ローカル / ワールド）と、その往復変換。
 */

/**
 * One drawable captured in all three spaces at a fixed pose.
 *
 * The arrays are snapshots, not live views: a gesture captures once at the start and transforms the
 * captured [world] every frame, so an in-flight preview never compounds on its own output.
 *
 * @property DrawableId drawableId The captured drawable.
 * @property DrawableSpaceMapping mapping The local<->world mapping for the deformer chain at this pose.
 * @property FloatArray base The stored rest positions (DrawableMesh.positions).
 * @property FloatArray displayed The posed LOCAL shape - base plus the keyform blend; equals [base] for a
 *   drawable with no keyform grid.
 * @property FloatArray world [displayed] projected through the deformer chain - what the viewport shows.
 */
internal class DrawableWorldGeometry(
	val drawableId: DrawableId,
	val mapping: DrawableSpaceMapping,
	val base: FloatArray,
	val displayed: FloatArray,
	val world: FloatArray,
) {
	/** Every vertex index of this drawable - the whole-mesh set an object-level transform moves. */
	val allIndices: Set<Int> get() = (0 until world.size / 2).toSet()

	/**
	 * Inverts a transformed WORLD shape back onto the base mesh - the write-back every whole-drawable
	 * transform ends with.
	 *
	 * Two steps, and both matter.  The world shape is mapped back to local through the deformer chain
	 * (exact only at the neutral pose, which is why callers gate on isPoseNeutral), and the result is then
	 * DIFFERENCED against the captured displayed shape rather than written directly - see [movementToBase]
	 * for why that is what leaves the keyform grid untouched.
	 *
	 * @param FloatArray transformedWorld The reshaped world positions.
	 * @param Set<Int> indices The vertices the transform touched (the whole mesh for an object transform).
	 * @return FloatArray The new base positions (a fresh array).
	 */
	fun worldToBase(transformedWorld: FloatArray, indices: Set<Int> = allIndices): FloatArray {
		val transformedLocal = mapping.worldToLocalLinearized(transformedWorld, displayed, world, indices)
		return movementToBase(base, transformedLocal, displayed)
	}
}

/**
 * Captures [drawableId] in all three spaces at [pose].
 *
 * Null when the drawable carries no mesh, or when it has no world mapping at all - which happens when an
 * ancestor is hidden.  A caller sweeping a selection should SKIP a null rather than abort, so one hidden
 * drawable does not block a gesture over the others.
 *
 * @param PuppetModel model The document model.
 * @param Pose pose The parameter values to capture at.
 * @param DrawableId drawableId The drawable to capture.
 * @return DrawableWorldGeometry? The capture, or null when the drawable has no mesh or no mapping.
 */
internal fun captureDrawableWorld(model: PuppetModel, pose: Pose, drawableId: DrawableId): DrawableWorldGeometry? {
	val base = model.drawables.firstOrNull { drawable -> drawable.id == drawableId }?.mesh?.positions ?: return null
	val mapping = drawableSpaceMapping(model, pose, drawableId) ?: return null
	val displayed = drawableLocalPosed(model, pose, drawableId) ?: base
	return DrawableWorldGeometry(drawableId, mapping, base, displayed, mapping.localToWorld(displayed))
}

/**
 * Transfers a displayed-shape movement onto the base mesh: `newBase = base + (after - before)`. The rest
 * shape a rigger sees is base + the neutral keyform blend; because the blend cancels out of the
 * subtraction, the moved rest shape re-renders exactly at `after` while only DrawableMesh.positions is
 * written - no keyform cell is touched, and blend-shape deltas (relative to base) follow the edit. For a
 * grid-less drawable `before` equals base, so this degenerates to `newBase = after`.
 *
 * Exposed alongside [DrawableWorldGeometry.worldToBase] because the Edit-mode gizmo applies it to a
 * per-vertex capture that is not a whole-drawable one.
 *
 * @param FloatArray base The rest positions captured at gesture start.
 * @param FloatArray after The transformed displayed shape.
 * @param FloatArray before The displayed shape captured at gesture start.
 * @return FloatArray The new base positions (a fresh array).
 */
internal fun movementToBase(base: FloatArray, after: FloatArray, before: FloatArray): FloatArray =
	FloatArray(base.size) { coordIndex ->
		if (coordIndex < after.size && coordIndex < before.size) {
			base[coordIndex] + after[coordIndex] - before[coordIndex]
		} else {
			base[coordIndex]
		}
	}
