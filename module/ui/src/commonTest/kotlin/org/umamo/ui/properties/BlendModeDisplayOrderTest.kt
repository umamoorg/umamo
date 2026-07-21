package org.umamo.ui.properties

import org.umamo.runtime.model.BlendMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The blend-mode picker order: every mode present exactly once, Normal first, the two "(Legacy)"
 * premultiplied modes sunk to the bottom, and the 5.3 modes keeping their canonical order ahead of
 * them.  Guards against the enum/format order (BlendMode.entries) leaking into the UI unchanged.
 */
class BlendModeDisplayOrderTest {
	@Test
	fun displayOrderSinksLegacyModesToTheBottomWithoutDroppingAny() {
		val order = blendModeDisplayOrder()
		assertEquals(BlendMode.entries.size, order.size, "no drops or duplicates")
		assertEquals(BlendMode.entries.toSet(), order.toSet(), "every mode present exactly once")
		assertEquals(BlendMode.Normal, order.first(), "Normal stays first")
		assertEquals(
			listOf(BlendMode.AdditivePremultiplied, BlendMode.MultiplyPremultiplied),
			order.takeLast(2),
			"the two (Legacy) premultiplied modes are last",
		)
		assertEquals(
			BlendMode.entries.filterNot { it.ignoresAlphaBlend },
			order.dropLast(2),
			"the 5.3 modes keep their canonical order ahead of the legacy pair",
		)
	}
}
