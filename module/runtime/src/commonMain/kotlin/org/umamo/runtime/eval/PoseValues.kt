package org.umamo.runtime.eval

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deformerChain
import org.umamo.runtime.model.endsInCycle

/**
 * The default value per parameter id of this model, 0 for an id it lacks (as the evaluator reads one).
 *
 * @return Function The lookup.
 */
fun PuppetModel.defaultValueLookup(): (ParameterId) -> Float {
	val defaultById = parameters.associate { parameter -> parameter.id to parameter.default }

	return { parameterId -> defaultById[parameterId] ?: 0f }
}

/**
 * The value this axis evaluates [value] as: inside the range it stands, and outside it a normal axis clamps to the
 * nearer end while a repeating one wraps back in by whole periods.
 *
 * Exactly max stays max on a repeating axis.  The runtime evaluates it as min; Umamo keeps it so the max key stays
 * reachable, and only a value outside `[min, max]` wraps (docs/format/MOC3.md §5.5).
 *
 * @param Float value The raw value, from a pose or a default.
 * @return Float The value the evaluator reads; min for an empty range, NaN for NaN.
 */
fun Parameter.wrapOrClamp(value: Float): Float {
	if (max <= min) {
		return min
	}

	if (value.isNaN() || value in min..max) {
		return value
	}

	// An infinite value has no period to reduce by, so a repeating axis clamps it like a normal one.
	if (!repeat || value.isInfinite()) {
		return if (value > max) max else min
	}

	// The cap absorbs the rounding of min + an offset just short of the range.
	return (min + (value - min).mod(max - min)).coerceAtMost(max)
}

/**
 * Each of [parameters]' values at [pose] through [wrapOrClamp], its default for one the pose does not name, and the
 * pose's own value, else 0, for an id the list lacks.
 *
 * @param List<Parameter> parameters The rig's parameters.
 * @param Map<ParameterId, Float> pose The pose (partial; the rest default).
 * @return Function The lookup.
 */
fun poseValueLookup(parameters: List<Parameter>, pose: Map<ParameterId, Float>): (ParameterId) -> Float {
	val valueById = HashMap<ParameterId, Float>(parameters.size)

	for (parameter in parameters) {
		valueById[parameter.id] = parameter.wrapOrClamp(pose[parameter.id] ?: parameter.default)
	}

	return { parameterId -> valueById[parameterId] ?: pose[parameterId] ?: 0f }
}

/**
 * Each parameter's value at [pose] through [wrapOrClamp], its default for one the pose does not name, and the pose's
 * own value, else 0, for an id the model lacks.
 *
 * @param Map<ParameterId, Float> pose The pose (partial; the rest default).
 * @return Function The lookup.
 */
fun PuppetModel.poseValueLookup(pose: Map<ParameterId, Float>): (ParameterId) -> Float = poseValueLookup(parameters, pose)

/**
 * Whether [deformerId] is a warp whose lattice the pose [valueOf] reads draws: its keyform grid, and every grid up its
 * parent chain, brackets the pose, and the chain reaches the root rather than a parent cycle - exactly when the
 * evaluator builds it.
 *
 * @param DeformerId deformerId The deformer.
 * @param Function valueOf Each parameter's value at the pose.
 * @return Boolean True for a warp the pose draws.
 */
fun PuppetModel.warpDrawnAt(deformerId: DeformerId, valueOf: (ParameterId) -> Float): Boolean {
	val chain = deformerChain(deformerId)

	if (chain.firstOrNull() !is Deformer.Warp || endsInCycle(chain)) {
		return false
	}

	return chain.all { deformer ->
		val grid =
			when (deformer) {
				is Deformer.Warp -> deformer.geometryGrid
				is Deformer.Rotation -> deformer.geometryGrid
			}

		grid != null && gridCorners(grid, valueOf) != null
	}
}