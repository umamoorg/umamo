package org.umamo.render.eval

import org.umamo.runtime.model.Keyform

/**
 * Multilinear blend for a single parameter axis: `base + Σ wᵢ·Δᵢ`.
 *
 * A 1-D isolation of the blend math also used, generalized to N dimensions, by the real keyform-grid
 * blend in [blendLocalFromCorners].  Kept as a free function so it's trivially testable on its own.
 *
 * @param FloatArray base The base positions.
 * @param List<Pair<Float, Keyform>> keyforms Each keyform with its weight.
 * @return FloatArray The blended positions, in a new array.
 */
fun blend(base: FloatArray, keyforms: List<Pair<Float, Keyform>>): FloatArray {
	val result = base.copyOf()

	for ((weight, keyform) in keyforms) {
		for (componentIndex in result.indices) {
			result[componentIndex] += weight * keyform.delta[componentIndex]
		}
	}

	return result
}