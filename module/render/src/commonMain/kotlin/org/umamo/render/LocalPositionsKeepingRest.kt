package org.umamo.render

import org.umamo.render.eval.DrawableSpaceResolver
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs

/*
 * Deriving a drawable's keyform-space base (DrawableMesh.localPositions) from where its mesh sits on the
 * canvas.  The base lives in the parent deformer's space, so the derivation inverts the deformer chain - the
 * evaluator's work, which is why it lives here and not in :interop or :edit.
 */

/**
 * How far, in canvas pixels, a derived base may land from the canvas positions it was derived from.  Above the
 * warp inverse's own convergence (1e-5 of the lattice's span) for any lattice up to ten thousand pixels across,
 * and far below the whole pixels a vertex the inverse could not reach misses by.
 */
private const val LANDING_TOLERANCE_PX = 0.1f

/**
 * The bases that keep each of [drawableIds] where it rests in [before] once [after] binds it to another
 * deformer (or to none): its rest shape in [before], taken to the canvas through its old deformer chain, then
 * into its new parent's space.  Only an unkeyed drawable's rest shape is its whole shape, so callers convert
 * those alone.  A drawable either chain cannot map is left out, and the caller keeps its old base.
 *
 * @param PuppetModel            before      The model before the rebinding.
 * @param PuppetModel            after       The model after it, the drawables' bases not yet changed.
 * @param Collection<DrawableId> drawableIds The drawables to convert.
 * @return Map<DrawableId, FloatArray> The new base per drawable that could be converted.
 */
fun localPositionsKeepingRest(before: PuppetModel, after: PuppetModel, drawableIds: Collection<DrawableId>): Map<DrawableId, FloatArray> {
	if (drawableIds.isEmpty()) {
		return emptyMap()
	}
	val toParentSpace = canvasToParentSpaceFor(after)
	// One resolver per model, so each deformer chain bakes once for the whole set.
	val beforeSpaces = DrawableSpaceResolver(before, emptyMap())
	val afterSpaces = DrawableSpaceResolver(after, emptyMap())
	val converted = LinkedHashMap<DrawableId, FloatArray>()
	for (drawableId in drawableIds) {
		val mapping = beforeSpaces.mapping(drawableId) ?: continue
		val rest = beforeSpaces.localPosed(drawableId) ?: continue
		// The eval negates Y into world space; canvas space is the pre-negation Y-down convention.
		val world = mapping.localToWorld(rest)
		val canvas = FloatArray(world.size) { coordIndex -> if (coordIndex % 2 == 1) -world[coordIndex] else world[coordIndex] }
		parentSpaceOf(afterSpaces, drawableId, canvas, toParentSpace)?.let { local -> converted[drawableId] = local }
	}
	return converted
}

/**
 * [canvas] in drawable [drawableId]'s parent space in the model [spaces] resolves, or null when the chain cannot
 * map it there.  The warp inverse keeps its best estimate when it cannot reach a target, so the base is mapped
 * forward again and refused unless it lands back on [canvas]; a drawable the default pose hides is taken on the
 * inverse's word, since its chain is only defined at the clamped pose the inverse used.
 *
 * @param DrawableSpaceResolver spaces        The model whose deformer chain the base belongs to, at the rest pose.
 * @param DrawableId            drawableId    The drawable.
 * @param FloatArray            canvas        The canvas positions to map.
 * @param Function2             toParentSpace The same model's canvas-to-parent inverse.
 * @return FloatArray? The base, or null.
 */
private fun parentSpaceOf(spaces: DrawableSpaceResolver, drawableId: DrawableId, canvas: FloatArray, toParentSpace: (DrawableId, FloatArray) -> FloatArray?): FloatArray? {
	val local = toParentSpace(drawableId, canvas) ?: return null
	if (local.size != canvas.size || local.any { component -> !component.isFinite() }) {
		return null
	}
	val mapping = spaces.mapping(drawableId) ?: return local
	val landed = mapping.localToWorld(local)
	for (coordIndex in canvas.indices) {
		val landedCanvas = if (coordIndex % 2 == 1) -landed[coordIndex] else landed[coordIndex]
		if (abs(landedCanvas - canvas[coordIndex]) > LANDING_TOLERANCE_PX) {
			return null
		}
	}
	return local
}