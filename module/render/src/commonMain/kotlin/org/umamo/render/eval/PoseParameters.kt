package org.umamo.render.eval

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/**
 * The two parameter lookups a pose evaluation reads, built once per evaluation: a parameter's value at the
 * pose, and its default.  The pose's value wins, else the parameter's default, else zero for a parameter the
 * rig does not define.  Blend shapes measure their deltas from the defaults, so every bake of the deformer
 * cascade takes both lookups from here; a bake handed the pose as its defaults would measure them from the
 * pose and place the rig somewhere the renderer does not draw it.
 *
 * @param List<Parameter> definitions The rig's parameters, whose defaults fill what the pose leaves out.
 * @param Map pose Parameter id -> value (partial; the rest default).
 */
internal class PoseParameters(definitions: List<Parameter>, pose: Map<ParameterId, Float>) {
	/**
	 * The lookups for [model]'s parameters at [pose].
	 *
	 * @param PuppetModel model The rig.
	 * @param Map pose Parameter id -> value (partial; the rest default).
	 */
	constructor(model: PuppetModel, pose: Map<ParameterId, Float>) : this(model.parameters, pose)

	private val defaults: Map<ParameterId, Float> = definitions.associate { parameter -> parameter.id to parameter.default }

	/** A parameter's value at the pose, else its default, else zero. */
	val paramValue: (ParameterId) -> Float = { parameterId -> pose[parameterId] ?: defaults[parameterId] ?: 0f }

	/** A parameter's default, else zero: the neutral pose blend shapes measure from. */
	val defaultValue: (ParameterId) -> Float = { parameterId -> defaults[parameterId] ?: 0f }
}