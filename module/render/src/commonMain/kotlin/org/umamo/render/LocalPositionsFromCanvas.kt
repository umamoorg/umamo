package org.umamo.render

import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
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
 * A model whose drawables took their bases from their canvas meshes, and the ones that could not.
 *
 * @property PuppetModel      model       The model.
 * @property List<DrawableId> unconverted The requested drawables whose base was left as it was: no mesh, or
 *   a deformer chain that cannot map them there.
 */
class LocalPositionsFromCanvas(
	val model: PuppetModel,
	val unconverted: List<DrawableId>,
)

/**
 * Gives each of [drawableIds] the base that puts its rest shape where its canvas mesh is: the canvas mesh
 * mapped through the parent deformer chain at the rest pose, the same inverse a MOC3 export's canvas seam
 * takes.  For an unkeyed drawable that base IS the rest shape, so it renders exactly over its canvas mesh.
 * The canvas mesh and every delta are left as they are.
 *
 * @param PuppetModel          model       The model.
 * @param Collection<DrawableId> drawableIds The drawables to derive.
 * @return LocalPositionsFromCanvas The model, and the drawables the chain could not map.
 */
fun withLocalPositionsFromCanvas(model: PuppetModel, drawableIds: Collection<DrawableId>): LocalPositionsFromCanvas {
	if (drawableIds.isEmpty()) {
		return LocalPositionsFromCanvas(model, emptyList())
	}
	val wanted = drawableIds.toSet()
	val toParentSpace = canvasToParentSpaceFor(model)
	val unconverted = ArrayList<DrawableId>()
	val drawables =
		model.drawables.map { drawable ->
			if (drawable.id !in wanted) {
				return@map drawable
			}
			val mesh = drawable.mesh
			val local = mesh?.let { canvasMesh -> parentSpaceOf(model, drawable.id, canvasMesh.positions, toParentSpace) }
			if (mesh == null || local == null) {
				unconverted.add(drawable.id)
				return@map drawable
			}
			drawable.copy(mesh = DrawableMesh(positions = mesh.positions, localPositions = local, uvs = mesh.uvs, indices = mesh.indices))
		}
	return LocalPositionsFromCanvas(model.copy(drawables = drawables), unconverted)
}

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
	val converted = LinkedHashMap<DrawableId, FloatArray>()
	for (drawableId in drawableIds) {
		val mapping = drawableSpaceMapping(before, emptyMap(), drawableId) ?: continue
		val rest = drawableLocalPosed(before, emptyMap(), drawableId) ?: continue
		// The eval negates Y into world space; canvas space is the pre-negation Y-down convention.
		val world = mapping.localToWorld(rest)
		val canvas = FloatArray(world.size) { coordIndex -> if (coordIndex % 2 == 1) -world[coordIndex] else world[coordIndex] }
		parentSpaceOf(after, drawableId, canvas, toParentSpace)?.let { local -> converted[drawableId] = local }
	}
	return converted
}

/**
 * [canvas] in drawable [drawableId]'s parent space in [model], or null when the chain cannot map it there.  The
 * warp inverse keeps its best estimate when it cannot reach a target, so the base is mapped forward again and
 * refused unless it lands back on [canvas]; a drawable the default pose hides is taken on the inverse's word,
 * since its chain is only defined at the clamped pose the inverse used.
 *
 * @param PuppetModel model         The model whose deformer chain the base belongs to.
 * @param DrawableId  drawableId    The drawable.
 * @param FloatArray  canvas        The canvas positions to map.
 * @param Function2   toParentSpace [model]'s canvas-to-parent inverse.
 * @return FloatArray? The base, or null.
 */
private fun parentSpaceOf(model: PuppetModel, drawableId: DrawableId, canvas: FloatArray, toParentSpace: (DrawableId, FloatArray) -> FloatArray?): FloatArray? {
	val local = toParentSpace(drawableId, canvas) ?: return null
	if (local.size != canvas.size || local.any { component -> !component.isFinite() }) {
		return null
	}
	val mapping = drawableSpaceMapping(model, emptyMap(), drawableId) ?: return local
	val landed = mapping.localToWorld(local)
	for (coordIndex in canvas.indices) {
		val landedCanvas = if (coordIndex % 2 == 1) -landed[coordIndex] else landed[coordIndex]
		if (abs(landedCanvas - canvas[coordIndex]) > LANDING_TOLERANCE_PX) {
			return null
		}
	}
	return local
}