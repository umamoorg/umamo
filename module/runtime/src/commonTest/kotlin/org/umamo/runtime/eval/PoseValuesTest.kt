package org.umamo.runtime.eval

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins how a parameter value resolves before the evaluator reads it: inside the range it stands, outside it a normal
 * axis clamps and a repeating one wraps, and exactly max stays max on both.
 */
class PoseValuesTest {
	private val tolerance = 1e-5f

	private fun unit(repeat: Boolean) = Parameter(ParameterId("Unit"), "Unit", 0f, 1f, 0f, repeat = repeat)

	private fun angle(repeat: Boolean) = Parameter(ParameterId("Angle"), "Angle", -30f, 30f, 0f, repeat = repeat)

	@Test
	fun aValueInsideTheRangeStandsOnEitherKind() {
		for (repeat in listOf(false, true)) {
			assertEquals(0.4f, unit(repeat).wrapOrClamp(0.4f))
			assertEquals(-12.5f, angle(repeat).wrapOrClamp(-12.5f))
		}
	}

	/** Umamo keeps exactly max where the runtime evaluates it as min, so the max key stays reachable. */
	@Test
	fun bothEndsStandOnARepeatingAxis() {
		assertEquals(0f, unit(repeat = true).wrapOrClamp(0f))
		assertEquals(1f, unit(repeat = true).wrapOrClamp(1f))
		assertEquals(-30f, angle(repeat = true).wrapOrClamp(-30f))
		assertEquals(30f, angle(repeat = true).wrapOrClamp(30f))
	}

	@Test
	fun aNormalAxisClampsPastEitherEnd() {
		assertEquals(1f, unit(repeat = false).wrapOrClamp(1.5f))
		assertEquals(0f, unit(repeat = false).wrapOrClamp(-0.5f))
		assertEquals(30f, angle(repeat = false).wrapOrClamp(1000f))
		assertEquals(-30f, angle(repeat = false).wrapOrClamp(-1000f))
		assertEquals(30f, angle(repeat = false).wrapOrClamp(Float.POSITIVE_INFINITY))
		assertEquals(-30f, angle(repeat = false).wrapOrClamp(Float.NEGATIVE_INFINITY))
	}

	@Test
	fun aRepeatingAxisWrapsPastEitherEnd() {
		val unit = unit(repeat = true)
		assertEquals(0.01f, unit.wrapOrClamp(1.01f), tolerance)
		assertEquals(0.99f, unit.wrapOrClamp(-0.01f), tolerance)
		assertEquals(0.25f, unit.wrapOrClamp(1.25f), tolerance)
		assertEquals(0.75f, unit.wrapOrClamp(-0.25f), tolerance)
		assertEquals(0f, unit.wrapOrClamp(2f), tolerance)
		assertEquals(0f, unit.wrapOrClamp(-1f), tolerance)
	}

	@Test
	fun aRepeatingAxisWrapsSeveralPeriodsOut() {
		assertEquals(0.25f, unit(repeat = true).wrapOrClamp(3.25f), tolerance)
		assertEquals(0.25f, unit(repeat = true).wrapOrClamp(-2.75f), tolerance)
	}

	/** A range that does not start at zero, which an offset measured from zero instead of min would get wrong. */
	@Test
	fun aRepeatingAxisWrapsFromItsMinimum() {
		val angle = angle(repeat = true)
		assertEquals(-25f, angle.wrapOrClamp(35f), tolerance)
		assertEquals(25f, angle.wrapOrClamp(-35f), tolerance)
		assertEquals(-25f, angle.wrapOrClamp(215f), tolerance)
		assertEquals(25f, angle.wrapOrClamp(-215f), tolerance)
	}

	@Test
	fun aWrappedValueNeverLeavesTheRange() {
		val awkward = Parameter(ParameterId("Awkward"), "Awkward", -0.1f, 0.7f, 0f, repeat = true)

		for (step in -5000..5000) {
			val resolved = awkward.wrapOrClamp(step * 0.001f)
			assertTrue(resolved >= awkward.min && resolved <= awkward.max, "${step * 0.001f} resolved to $resolved")
		}
	}

	@Test
	fun anEmptyRangeGivesItsMinimum() {
		for (repeat in listOf(false, true)) {
			val empty = Parameter(ParameterId("Empty"), "Empty", 0.5f, 0.5f, 0.5f, repeat = repeat)
			assertEquals(0.5f, empty.wrapOrClamp(3f))
			assertEquals(0.5f, empty.wrapOrClamp(-3f))
			val inverted = Parameter(ParameterId("Inverted"), "Inverted", 1f, -1f, 0f, repeat = repeat)
			assertEquals(1f, inverted.wrapOrClamp(0f))
		}
	}

	@Test
	fun nonFiniteValuesNeverThrow() {
		assertTrue(unit(repeat = false).wrapOrClamp(Float.NaN).isNaN())
		assertTrue(unit(repeat = true).wrapOrClamp(Float.NaN).isNaN())
		assertEquals(1f, unit(repeat = true).wrapOrClamp(Float.POSITIVE_INFINITY))
		assertEquals(0f, unit(repeat = true).wrapOrClamp(Float.NEGATIVE_INFINITY))
	}

	@Test
	fun theLookupResolvesPoseValuesAndDefaults() {
		val repeating = Parameter(ParameterId("Loop"), "Loop", 0f, 1f, 0f, repeat = true)
		val normal = angle(repeat = false)
		val outOfRangeDefault = Parameter(ParameterId("Wide"), "Wide", -1f, 1f, 4f)
		val lookup = poseValueLookup(listOf(repeating, normal, outOfRangeDefault), mapOf(repeating.id to 1.25f, normal.id to 45f))
		assertEquals(0.25f, lookup(repeating.id), tolerance)
		assertEquals(30f, lookup(normal.id))
		assertEquals(1f, lookup(outOfRangeDefault.id), "a default resolves like any pose value")
	}

	@Test
	fun anIdTheRigLacksReadsThePoseElseZero() {
		val stray = ParameterId("Stray")
		assertEquals(7f, poseValueLookup(listOf(unit(repeat = false)), mapOf(stray to 7f))(stray))
		assertEquals(0f, poseValueLookup(listOf(unit(repeat = false)), emptyMap())(stray))
	}
}