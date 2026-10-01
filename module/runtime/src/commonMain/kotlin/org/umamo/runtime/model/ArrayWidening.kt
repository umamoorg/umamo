package org.umamo.runtime.model

/*
 * The two conversions at the edge of the double-precision mesh deltas (see MeshDeltaForm).  Widening a float
 * is exact; narrowing rounds once, which is the one rounding a reconstruction is allowed.  Kept explicit, with
 * no FloatArray constructor on the forms, so a float `abs − base` cannot slip into a delta unnoticed.
 */

/**
 * This array widened to double, each component exactly.
 *
 * @return DoubleArray The widened components.
 */
fun FloatArray.toDoubleArray(): DoubleArray = DoubleArray(size) { componentIndex -> this[componentIndex].toDouble() }

/**
 * This array narrowed to float, each component rounded once to the nearest float.
 *
 * @return FloatArray The narrowed components.
 */
fun DoubleArray.toFloatArray(): FloatArray = FloatArray(size) { componentIndex -> this[componentIndex].toFloat() }

/**
 * The per-vertex deltas that take [base] to [positions], computed in double so the difference of the two floats
 * is exact: `(base + delta).toFloat()` gives [positions] back bit for bit whenever the two magnitudes are within
 * 2^29 of each other, and within 2^-41 of a unit otherwise.
 *
 * @param FloatArray positions The absolute positions.
 * @param FloatArray base      The base they are measured from, the same length.
 * @return DoubleArray The deltas.
 */
fun deltasFromBase(positions: FloatArray, base: FloatArray): DoubleArray =
	DoubleArray(positions.size) { componentIndex -> positions[componentIndex].toDouble() - base[componentIndex].toDouble() }

/**
 * The absolute positions [deltas] give over [base], added in double and rounded once.
 *
 * Components past the shorter of the two arrays take the base unchanged, the same tolerance the evaluator gives a
 * short delta array.
 *
 * @param FloatArray  base   The base.
 * @param DoubleArray deltas The deltas.
 * @return FloatArray The absolute positions, [base]'s length.
 */
fun positionsFromDeltas(base: FloatArray, deltas: DoubleArray): FloatArray =
	FloatArray(base.size) { componentIndex ->
		if (componentIndex < deltas.size) {
			(base[componentIndex].toDouble() + deltas[componentIndex]).toFloat()
		} else {
			base[componentIndex]
		}
	}