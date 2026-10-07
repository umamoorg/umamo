package org.umamo.runtime.model

/*
 * The conversions between a drawable's absolute keyform positions and the deltas the model stores, both in the
 * keyforms' own space (DrawableMesh.localPositions).  A keyform is rebuilt as `fl32(local + Δ)`; every
 * importer derives Δ so that the rebuild gives the stored float back where float32 can, and every exporter
 * rebuilds through the same expression, so the model and the files agree on each value.
 */

/**
 * The delta that takes [reference] to [absolute]: `absolute − reference`, nudged by one step either way when
 * that difference rounds and its neighbor rebuilds [absolute] exactly.
 *
 * The rebuild `reference + delta` is exact whenever the two values are of a similar magnitude.  When the
 * absolute is far smaller than the reference, the sum keeps only the reference's precision and no delta can
 * reach every bit of the absolute; the result is then within one step of the larger of the two, the precision
 * the shared space itself has.
 *
 * @param Float reference The base value.
 * @param Float absolute  The keyform's value.
 * @return Float The delta.
 */
fun deltaReaching(reference: Float, absolute: Float): Float {
	val delta = absolute - reference
	if (!delta.isFinite() || reference + delta == absolute) {
		return delta
	}
	val above = floatAbove(delta)
	if (reference + above == absolute) {
		return above
	}
	val below = floatBelow(delta)
	if (reference + below == absolute) {
		return below
	}
	return delta
}

/**
 * The float32 neighbor of [value] toward positive infinity, the platform `nextUp` in common code: kotlin.math
 * declares `Float.nextUp` for JVM and Native but not for the common metadata, so the step goes through the bits.
 * A finite float's neighbor away from zero is the next raw-bits pattern and toward zero the previous one, for
 * either sign; both zeros step to the smallest positive subnormal.  NaN and positive infinity come back as they
 * are.
 *
 * @param Float value The value.
 * @return Float The next float32 above it.
 */
internal fun floatAbove(value: Float): Float {
	if (value.isNaN() || value == Float.POSITIVE_INFINITY) {
		return value
	}
	if (value == 0f) {
		return Float.MIN_VALUE
	}
	return Float.fromBits(if (value > 0f) value.toRawBits() + 1 else value.toRawBits() - 1)
}

/**
 * The float32 neighbor of [value] toward negative infinity, the platform `nextDown` in common code: the mirror
 * of [floatAbove].
 *
 * @param Float value The value.
 * @return Float The next float32 below it.
 */
internal fun floatBelow(value: Float): Float = -floatAbove(-value)

/**
 * The per-component deltas that take [reference] to [absolutes] ([deltaReaching] each).
 *
 * @param FloatArray reference The base, at least [absolutes]' length.
 * @param FloatArray absolutes The keyform's absolute positions.
 * @return FloatArray The deltas, [absolutes]' length.
 */
fun deltasReaching(reference: FloatArray, absolutes: FloatArray): FloatArray =
	FloatArray(absolutes.size) { componentIndex -> deltaReaching(reference[componentIndex], absolutes[componentIndex]) }

/**
 * The absolute positions [deltas] give over [reference]: `reference + Δ` per component, the expression every
 * consumer rebuilds a keyform with.  Components past the end of [deltas] take the reference unchanged, the
 * tolerance the evaluator gives a short delta array.
 *
 * @param FloatArray reference The base.
 * @param FloatArray deltas    The deltas.
 * @return FloatArray The absolute positions, [reference]'s length.
 */
fun positionsFromDeltas(reference: FloatArray, deltas: FloatArray): FloatArray =
	FloatArray(reference.size) { componentIndex ->
		if (componentIndex < deltas.size) {
			reference[componentIndex] + deltas[componentIndex]
		} else {
			reference[componentIndex]
		}
	}

/**
 * Whether this geometry grid holds nothing but the rest shape: no grid at all, or one axis-less cell of zero
 * deltas.  The two spellings mean the same thing - every CMO3 source carries a default form, so an unkeyed
 * drawable exports as that one cell and re-imports as this grid.
 *
 * @return Boolean True for the rest shape alone.
 */
fun KeyformGrid<MeshDeltaForm>?.holdsOnlyTheRest(): Boolean {
	if (this == null) {
		return true
	}
	if (axes.isNotEmpty() || cells.size != 1) {
		return false
	}
	return cells.single().form.positionDeltas.all { delta -> delta == 0f }
}

/**
 * Whether this drawable's geometry is its rest shape alone: a grid that [holdsOnlyTheRest] and no blend shape.
 * Such a drawable's whole shape is [DrawableMesh.localPositions], which is what lets a reparent carry it over by
 * mapping that one array into the new parent's space.
 */
val Drawable.hasUnkeyedGeometry: Boolean get() = geometryGrid.holdsOnlyTheRest() && blendShapes.isEmpty()