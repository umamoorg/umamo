package org.umamo.runtime.eval

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
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
 * Each parameter's value at [pose], its default for one the pose does not name, 0 for one the model lacks.
 *
 * @param Map<ParameterId, Float> pose The pose (partial; the rest default).
 * @return Function The lookup.
 */
fun PuppetModel.poseValueLookup(pose: Map<ParameterId, Float>): (ParameterId) -> Float {
	val defaultValue = defaultValueLookup()

	return { parameterId -> pose[parameterId] ?: defaultValue(parameterId) }
}

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