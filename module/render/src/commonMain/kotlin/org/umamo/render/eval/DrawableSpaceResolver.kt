package org.umamo.render.eval

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/**
 * Resolves the local<->world mappings and the posed local shapes of MANY drawables at one (model,
 * pose), sharing everything that is a property of the pair rather than of a drawable: the parameter
 * defaults, the drawable index, and the deformer worlds, which bake once on the first parented request
 * and never for a batch of direct drawables.  Per drawable the answers are exactly what
 * [drawableSpaceMapping] and [drawableLocalPosed] give, because both halves run the same code
 * ([mappingOver], [localPosedOver]) and the worlds come from the same two-argument
 * [buildDeformerWorlds] call the single functions make (the pose doubles as the defaults, no channel
 * overrides), so a batch cannot answer differently from a single call.
 *
 * One resolver per batch is the whole point.  A loop over the single functions rebakes every deformer
 * world per drawable - 1330 x 623 on modelF, seconds per Edit-mode commit or Object-mode latch over
 * everything - where a resolver bakes them once.
 *
 * @param PuppetModel model The rig.
 * @param Map parameters Parameter id -> value (partial; the rest default).
 */
class DrawableSpaceResolver(
	model: PuppetModel,
	parameters: Map<ParameterId, Float>,
) {
	private val deformers: List<Deformer> = model.deformers
	private val defaults: Map<ParameterId, Float> = model.parameters.associate { parameter -> parameter.id to parameter.default }
	private val paramValue: (ParameterId) -> Float = { parameterId -> parameters[parameterId] ?: defaults[parameterId] ?: 0f }
	private val drawableById: Map<DrawableId, Drawable> = model.drawables.associateBy { drawable -> drawable.id }

	// Baked on the first parented request, so a batch of direct drawables never pays for the chain.
	private val deformerWorlds: Map<DeformerId, DeformerWorld> by lazy { buildDeformerWorlds(deformers, paramValue) }

	/**
	 * The drawable behind an id, or null when the model does not carry it.
	 *
	 * @param DrawableId drawableId The drawable.
	 * @return Drawable? The drawable, or null.
	 */
	fun drawable(drawableId: DrawableId): Drawable? = drawableById[drawableId]

	/**
	 * The local<->world mapping for [drawableId] at this pose: what [drawableSpaceMapping] answers.
	 *
	 * @param DrawableId drawableId The drawable to map.
	 * @return DrawableSpaceMapping? The mapping, or null when the drawable is absent or unmappable (a
	 *   hidden ancestor, or a parent the model does not carry).
	 */
	fun mapping(drawableId: DrawableId): DrawableSpaceMapping? {
		val drawable = drawableById[drawableId] ?: return null
		return mappingOver(drawable) { parentDeformerId -> deformerWorlds[parentDeformerId] }
	}

	/**
	 * The drawable's blended LOCAL posed positions at this pose: what [drawableLocalPosed] answers.
	 *
	 * @param DrawableId drawableId The drawable to sample.
	 * @return FloatArray? The interleaved local posed positions, or null when the drawable or its mesh
	 *   is missing or the pose hides it (out of range).
	 */
	fun localPosed(drawableId: DrawableId): FloatArray? {
		val drawable = drawableById[drawableId] ?: return null
		return localPosedOver(drawable, paramValue)
	}
}