package org.umamo.render.eval

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.WarpForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the blend-shape weight machinery: the epsilon-free saturating bracket with neutral
 * suppression, the min-combined limit curves, and the grid-at-default delta reference.
 */
class BlendShapeEvalTest {
	private val shrink = ParameterId("ParamShrink")
	private val gate = ParameterId("ParamGate")

	/** Value lookup from explicit pairs; everything else 0. */
	private fun values(vararg pairs: Pair<ParameterId, Float>): (ParameterId) -> Float {
		val map = mapOf(*pairs)
		return { map[it] ?: 0f }
	}

	private fun binding(
		keys: FloatArray,
		neutralIndex: Int,
		forms: List<WarpForm?>,
		limits: List<BlendWeightLimit> = emptyList(),
	) = BlendShapeBinding(shrink, keys, neutralIndex, forms, limits)

	private val twoKey =
		binding(
			keys = floatArrayOf(-1f, 0f),
			neutralIndex = 1,
			forms = listOf(WarpForm(floatArrayOf(10f, 20f)), null),
		)

	/** At the neutral value the active list is EMPTY - the default pose is arithmetic-free. */
	@Test
	fun neutralValueContributesNothing() {
		assertTrue(activeBlendKeys(twoKey, 0f, 1f).isEmpty(), "at neutral")
		assertTrue(activeBlendKeys(twoKey, 0.5f, 1f).isEmpty(), "beyond the neutral end saturates to neutral = nothing")
	}

	/** Bracket interpolation between a key and the neutral: only the non-neutral key contributes. */
	@Test
	fun bracketInterpolatesTowardNeutral() {
		val atKey = activeBlendKeys(twoKey, -1f, 1f)
		assertEquals(1, atKey.size)
		assertEquals(0, atKey[0].keyIndex)
		assertEquals(1f, atKey[0].weight)

		val half = activeBlendKeys(twoKey, -0.5f, 1f)
		assertEquals(1, half.size)
		assertEquals(0.5f, half[0].weight, "midpoint weights the key half")
	}

	/** Out-of-range driving values SATURATE (Umamo C++ Runtime verdict): the end delta is held. */
	@Test
	fun outOfRangeSaturates() {
		val beyond = activeBlendKeys(twoKey, -1.5f, 1f)
		assertEquals(1, beyond.size)
		assertEquals(0, beyond[0].keyIndex)
		assertEquals(1f, beyond[0].weight, "beyond the far key holds its full delta")
	}

	/** Between two non-neutral keys both contribute, weights summing to the limit multiplier. */
	@Test
	fun bracketBetweenTwoNonNeutralKeys() {
		val threeKey =
			binding(
				keys = floatArrayOf(0f, 1f, 2f),
				neutralIndex = 0,
				forms = listOf(null, WarpForm(floatArrayOf(1f, 1f)), WarpForm(floatArrayOf(2f, 2f))),
			)
		val between = activeBlendKeys(threeKey, 1.25f, 1f)
		assertEquals(2, between.size)
		assertEquals(1, between[0].keyIndex)
		assertEquals(0.75f, between[0].weight)
		assertEquals(2, between[1].keyIndex)
		assertEquals(0.25f, between[1].weight)
	}

	/** The limit multiplier scales weights linearly (Model B-measured: a half ramp gives exactly 0.5x). */
	@Test
	fun limitRampScalesWeights() {
		val ramp = listOf(BlendWeightLimit(gate, listOf(BlendWeightLimitPoint(0f, 0f), BlendWeightLimitPoint(1f, 1f))))
		assertEquals(0f, limitMultiplier(ramp, values(gate to 0f)), "closed gate suppresses")
		assertEquals(0.5f, limitMultiplier(ramp, values(gate to 0.5f)), "half-open gate halves")
		assertEquals(1f, limitMultiplier(ramp, values(gate to 1f)), "open gate passes")
		assertEquals(0f, limitMultiplier(ramp, values(gate to -1f)), "end-clamped below")
		assertEquals(1f, limitMultiplier(ramp, values(gate to 2f)), "end-clamped above")
	}

	/** Multiple limits combine by MINIMUM, not product (Model C-shaped inverse ramps). */
	@Test
	fun limitsCombineByMinimum() {
		val inverseRamp = { parameter: ParameterId ->
			BlendWeightLimit(parameter, listOf(BlendWeightLimitPoint(0f, 1f), BlendWeightLimitPoint(1f, 0f)))
		}
		val other = ParameterId("ParamOther")
		val limits = listOf(inverseRamp(gate), inverseRamp(other))
		// gate=0.6 -> 0.4; other=0.8 -> 0.2; MIN = 0.2 (a product would give 0.08).
		assertEquals(0.2f, limitMultiplier(limits, values(gate to 0.6f, other to 0.8f)), 1e-6f)
	}

	/** meshBlendState resolves the grid-at-default reference and stacks bindings by summation. */
	@Test
	fun meshBlendStateUsesGridAtDefaultReference() {
		val axis = ParameterId("ParamAxis")
		// Grid keyed on ParamAxis with keys [0 (default), 1]: the default cell carries deltas (5, 0).
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(axis, floatArrayOf(0f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(5f, 0f))),
					KeyformCell(intArrayOf(1), MeshForm(floatArrayOf(9f, 0f))),
				),
			)
		val meshBinding =
			BlendShapeBinding(
				parameterId = shrink,
				keys = floatArrayOf(-1f, 0f),
				neutralIndex = 1,
				forms = listOf(MeshForm(floatArrayOf(11f, 4f)), null),
			)
		val drawable =
			Drawable(
				id = DrawableId("d"),
				name = "d",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				keyforms = grid,
				blendShapes = listOf(meshBinding),
			)
		val state = meshBlendState(drawable, values(shrink to -1f), values())
		assertTrue(state != null)
		assertEquals(1, state.contributions.size)
		assertEquals(1f, state.contributions[0].weight)
		// Reference = the default-pose grid form's deltas: (5, 0).
		assertEquals(listOf(5f, 0f), state.referenceDeltas?.toList())
		// The eval adds w * (form - reference) = (11-5, 4-0) = (6, 4) on top of the grid result.
	}

	/** Warp blend deltas subtract the lattice's default-pose grid form (absolute forms). */
	@Test
	fun warpBlendDeltasSubtractDefaultLattice() {
		val axis = ParameterId("ParamAxis")
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(axis, floatArrayOf(0f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), WarpForm(floatArrayOf(100f, 100f))),
					KeyformCell(intArrayOf(1), WarpForm(floatArrayOf(120f, 100f))),
				),
			)
		val warp =
			Deformer.Warp(
				id = DeformerId("w"),
				name = "w",
				parent = null,
				partId = null,
				rows = 1,
				columns = 1,
				isQuadTransform = true,
				keyforms = grid,
				blendShapes =
					listOf(
						BlendShapeBinding(
							parameterId = shrink,
							keys = floatArrayOf(-1f, 0f),
							neutralIndex = 1,
							forms = listOf(WarpForm(floatArrayOf(107f, 103f)), null),
						),
					),
			)
		val deltas = warpBlendDeltas(warp, values(shrink to -0.5f), values())
		assertTrue(deltas != null)
		// Reference = default lattice (100, 100); authored (107, 103); weight 0.5 -> (3.5, 1.5).
		assertEquals(3.5f, deltas[0], 1e-6f)
		assertEquals(1.5f, deltas[1], 1e-6f)
	}

	/** A binding-free drawable resolves to null state - the caller keeps today's exact path. */
	@Test
	fun bindingFreeDrawableHasNoState() {
		val bare =
			Drawable(
				id = DrawableId("d"),
				name = "d",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				keyforms = null,
			)
		assertEquals(null, meshBlendState(bare, values(), values()))
	}
}
