package org.umamo.ui.viewport.viewport2d

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the demand register's one fact, whether any area asks, across asks, withdrawals, and removals. */
class WireframeDemandTest {
	@Test
	fun anyAreaAskingIsWanted() {
		val demand = WireframeDemand()
		assertFalse(demand.wanted.value, "nothing asks at first")

		demand.set("left", true)
		assertTrue(demand.wanted.value, "one area asking is enough")
		demand.set("right", true)
		demand.set("left", false)
		assertTrue(demand.wanted.value, "another area still asks")
		demand.set("right", false)
		assertFalse(demand.wanted.value, "no area asks")
	}

	@Test
	fun removingTheLastAskingAreaWithdrawsIt() {
		val demand = WireframeDemand()
		demand.set("left", true)

		demand.remove("left")

		assertFalse(demand.wanted.value)
		demand.remove("never")
		assertFalse(demand.wanted.value, "removing an area that never asked is harmless")
	}
}