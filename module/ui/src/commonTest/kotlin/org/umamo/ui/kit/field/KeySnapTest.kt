package org.umamo.ui.kit.field

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the magnetic key snap: it latches only within reach, onto the nearest target, the first on a tie. */
class KeySnapTest {
	/** A value within the tolerance of a target becomes that target. */
	@Test
	fun aValueWithinReachLatchesOntoTheTarget() {
		assertEquals(10f, snapToNearestWithin(10.4f, listOf(0f, 10f, 20f), 0.5f))
		assertEquals(10f, snapToNearestWithin(9.5f, listOf(0f, 10f, 20f), 0.5f))
	}

	/** A value farther than the tolerance from every target stays where it is. */
	@Test
	fun aValueOutOfReachMovesFreely() {
		assertEquals(5f, snapToNearestWithin(5f, listOf(0f, 10f, 20f), 0.5f))
		assertEquals(10.6f, snapToNearestWithin(10.6f, listOf(0f, 10f, 20f), 0.5f))
	}

	/** With two targets in reach, the nearer one wins whatever the list order. */
	@Test
	fun theNearerOfTwoTargetsWins() {
		assertEquals(1.2f, snapToNearestWithin(1f, listOf(0.5f, 1.2f), 1f))
		assertEquals(1.2f, snapToNearestWithin(1f, listOf(1.2f, 0.5f), 1f))
	}

	/** Two targets equally near keep the first in list order. */
	@Test
	fun aTieKeepsTheFirstTarget() {
		assertEquals(0f, snapToNearestWithin(1f, listOf(0f, 2f), 1f))
		assertEquals(2f, snapToNearestWithin(1f, listOf(2f, 0f), 1f))
	}

	/** No targets, or a zero tolerance off every target, leave the value alone. */
	@Test
	fun noTargetsOrNoReachLeaveTheValue() {
		assertEquals(3f, snapToNearestWithin(3f, emptyList(), 1f))
		assertEquals(3f, snapToNearestWithin(3f, listOf(3.1f), 0f))
		assertEquals(3f, snapToNearestWithin(3f, listOf(3f), 0f))
	}
}