package org.umamo.ui.kit.field

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins how an aspect-locked pair resolves the field not edited: unlocked it holds, locked it scales by the
 * edited field's factor, a degenerate edited axis carries no ratio, a mirrored partner keeps its own sign, and
 * the result stays inside the pair's range.
 */
class AspectLockFieldPairTest {
	private val unbounded = Float.NEGATIVE_INFINITY..Float.POSITIVE_INFINITY

	/** Unlocked, the partner keeps its value whatever the edit. */
	@Test
	fun anUnlockedPartnerHolds() {
		assertEquals(50f, aspectLockedPartner(editedValue = 200f, editedCurrent = 100f, partnerCurrent = 50f, locked = false, range = unbounded))
	}

	/** Locked, the partner scales by the factor the edited field changed by. */
	@Test
	fun aLockedPartnerScalesByTheEditedFactor() {
		assertEquals(100f, aspectLockedPartner(editedValue = 200f, editedCurrent = 100f, partnerCurrent = 50f, locked = true, range = unbounded))
		assertEquals(0.75f, aspectLockedPartner(editedValue = 0.5f, editedCurrent = 1f, partnerCurrent = 1.5f, locked = true, range = unbounded))
	}

	/** An edited field that was zero has no ratio to carry, so the partner holds. */
	@Test
	fun aDegenerateEditedAxisLeavesThePartnerAlone() {
		assertEquals(50f, aspectLockedPartner(editedValue = 10f, editedCurrent = 0f, partnerCurrent = 50f, locked = true, range = unbounded))
	}

	/** Each axis keeps its own sign: the lock carries the magnitude of the factor, never a mirror. */
	@Test
	fun aMirroredAxisKeepsItsOwnSign() {
		// Unmirroring x leaves y unmirrored at the new magnitude.
		assertEquals(2f, aspectLockedPartner(editedValue = 2f, editedCurrent = -1f, partnerCurrent = 1f, locked = true, range = unbounded))
		// Growing an unmirrored x leaves a mirrored y mirrored.
		assertEquals(-3f, aspectLockedPartner(editedValue = 3f, editedCurrent = 1f, partnerCurrent = -1f, locked = true, range = unbounded))
	}

	/** The partner clamps to the pair's range, which is where the ratio gives way. */
	@Test
	fun thePartnerClampsToTheRange() {
		val scaleRange = 0.01f..100f
		assertEquals(100f, aspectLockedPartner(editedValue = 100f, editedCurrent = 1f, partnerCurrent = 2f, locked = true, range = scaleRange))
		assertEquals(0.01f, aspectLockedPartner(editedValue = 0.01f, editedCurrent = 1f, partnerCurrent = 0.5f, locked = true, range = scaleRange))
	}

	/**
	 * A whole-number pair (a lattice's rows and columns) rounds the locked partner to the nearest whole number and
	 * holds it inside the range: 6 x 4 locked with the rows edited to 9 becomes 9 x 6, 7 x 4 to 9 rounds 5.14 to 5,
	 * and a partner that would fall below 1 holds at 1.
	 */
	@Test
	fun aWholeNumberPartnerRoundsAndClamps() {
		val divisions = 1..100
		val floatRange = 1f..100f

		assertEquals(6, wholeValueOf(aspectLockedPartner(editedValue = 9f, editedCurrent = 6f, partnerCurrent = 4f, locked = true, range = floatRange), divisions))
		assertEquals(5, wholeValueOf(aspectLockedPartner(editedValue = 9f, editedCurrent = 7f, partnerCurrent = 4f, locked = true, range = floatRange), divisions))
		assertEquals(1, wholeValueOf(aspectLockedPartner(editedValue = 1f, editedCurrent = 8f, partnerCurrent = 2f, locked = true, range = floatRange), divisions))
		assertEquals(100, wholeValueOf(140f, divisions))
		assertEquals(4, wholeValueOf(aspectLockedPartner(editedValue = 9f, editedCurrent = 6f, partnerCurrent = 4f, locked = false, range = floatRange), divisions), "unlocked, the partner holds")
	}
}