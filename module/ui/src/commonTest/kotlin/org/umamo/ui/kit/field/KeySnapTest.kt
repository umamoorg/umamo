package org.umamo.ui.kit.field

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the magnetic key snap: it latches only within reach and inside its bounds, onto the nearest target, the
 * first on a tie.
 */
class KeySnapTest {
	/** Bounds wide enough to hold every target here, for the tests that are not about the bounds. */
	private val everywhere = -100f..100f

	/** A value within the tolerance of a target becomes that target. */
	@Test
	fun aValueWithinReachLatchesOntoTheTarget() {
		assertEquals(10f, snapToNearestWithin(10.4f, listOf(0f, 10f, 20f), 0.5f, everywhere))
		assertEquals(10f, snapToNearestWithin(9.5f, listOf(0f, 10f, 20f), 0.5f, everywhere))
	}

	/** A value farther than the tolerance from every target stays where it is. */
	@Test
	fun aValueOutOfReachMovesFreely() {
		assertEquals(5f, snapToNearestWithin(5f, listOf(0f, 10f, 20f), 0.5f, everywhere))
		assertEquals(10.6f, snapToNearestWithin(10.6f, listOf(0f, 10f, 20f), 0.5f, everywhere))
	}

	/** With two targets in reach, the nearer one wins whatever the list order. */
	@Test
	fun theNearerOfTwoTargetsWins() {
		assertEquals(1.2f, snapToNearestWithin(1f, listOf(0.5f, 1.2f), 1f, everywhere))
		assertEquals(1.2f, snapToNearestWithin(1f, listOf(1.2f, 0.5f), 1f, everywhere))
	}

	/** Two targets equally near keep the first in list order. */
	@Test
	fun aTieKeepsTheFirstTarget() {
		assertEquals(0f, snapToNearestWithin(1f, listOf(0f, 2f), 1f, everywhere))
		assertEquals(2f, snapToNearestWithin(1f, listOf(2f, 0f), 1f, everywhere))
	}

	/** No targets, or a zero tolerance off every target, leave the value alone. */
	@Test
	fun noTargetsOrNoReachLeaveTheValue() {
		assertEquals(3f, snapToNearestWithin(3f, emptyList(), 1f, everywhere))
		assertEquals(3f, snapToNearestWithin(3f, listOf(3.1f), 0f, everywhere))
		assertEquals(3f, snapToNearestWithin(3f, listOf(3f), 0f, everywhere))
	}

	/**
	 * A target past the bounds never latches, though it is the nearest in reach: the value would be clamped
	 * back onto the wall.  A target inside them still does, and one on the wall itself counts as inside.
	 */
	@Test
	fun aTargetOutsideTheBoundsIsIgnored() {
		assertEquals(9.8f, snapToNearestWithin(9.8f, listOf(10.2f), 0.5f, 0f..10f))
		assertEquals(9.5f, snapToNearestWithin(9.8f, listOf(10.2f, 9.5f), 0.5f, 0f..10f))
		assertEquals(10f, snapToNearestWithin(9.8f, listOf(10f), 0.5f, 0f..10f))
	}

	/** Round a loop, a key at max pulls a value just past min, and lands on max itself, not on min. */
	@Test
	fun roundALoopAKeyAtMaxPullsAValueJustPastMin() {
		assertEquals(10f, snapToNearestWithinPeriodic(0.2f, listOf(5f, 10f), 0.5f, 0f..10f))
		assertEquals(0f, snapToNearestWithinPeriodic(9.8f, listOf(0f, 5f), 0.5f, 0f..10f))
	}

	/** A value a lap or more outside the range still latches, onto the target inside it. */
	@Test
	fun roundALoopAValueLapsOutStillLatches() {
		assertEquals(5f, snapToNearestWithinPeriodic(25.3f, listOf(5f), 0.5f, 0f..10f))
		assertEquals(10f, snapToNearestWithinPeriodic(-9.9f, listOf(10f), 0.5f, 0f..10f))
		assertEquals(10f, snapToNearestWithinPeriodic(10.2f, listOf(10f), 0.5f, 0f..10f), "just past max, a lap out, is beside the max key")
	}

	/** Round a loop the reach is still the reach, and a target outside the range never latches. */
	@Test
	fun roundALoopTheReachAndTheBoundsHold() {
		assertEquals(0.6f, snapToNearestWithinPeriodic(0.6f, listOf(10f), 0.5f, 0f..10f))
		assertEquals(0.2f, snapToNearestWithinPeriodic(0.2f, listOf(10.1f), 0.5f, 0f..10f))
	}

	/** Round a loop, the nearer target wins and a tie keeps the first. */
	@Test
	fun roundALoopTheNearerWinsAndATieKeepsTheFirst() {
		assertEquals(10f, snapToNearestWithinPeriodic(0.1f, listOf(0.4f, 10f), 0.5f, 0f..10f))
		assertEquals(0f, snapToNearestWithinPeriodic(0f, listOf(0f, 10f), 0.5f, 0f..10f), "min and max are the same place, so the first listed wins")
	}

	/** An empty range has no loop, so the snap measures plain distance. */
	@Test
	fun anEmptyRangeSnapsAlongALine() {
		assertEquals(3f, snapToNearestWithinPeriodic(3.2f, listOf(3f), 0.5f, 3f..3f))
	}
}