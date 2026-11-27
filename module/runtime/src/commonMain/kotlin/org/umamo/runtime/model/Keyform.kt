package org.umamo.runtime.model

/**
 * A captured delta at a specific parameter value.  The runtime blends across the N-D grid of
 * keyforms: `p = base + Σ wᵢ·Δᵢ` (multilinear interpolation).  Weights are cheap and computed
 * CPU-side per frame; the per-vertex delta-sum runs in the vertex shader (see `:render`).
 */
data class Keyform(
	val atValue: Float,
	val delta: FloatArray,
) {
	// data class with an array field: generated equals/hashCode use referential array equality,
	// which is wrong for value semantics.  Override so two keyforms with equal deltas compare equal.
	override fun equals(other: Any?): Boolean {
		if (this === other) {
			return true
		}

		if (other !is Keyform) {
			return false
		}

		return atValue == other.atValue && delta.contentEquals(other.delta)
	}

	override fun hashCode(): Int = 31 * atValue.hashCode() + delta.contentHashCode()
}