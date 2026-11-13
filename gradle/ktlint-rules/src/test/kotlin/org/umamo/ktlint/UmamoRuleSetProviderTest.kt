package org.umamo.ktlint

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the rules the `umamo` rule set hands the engine, so a rule written but never registered fails here
 * instead of silently never running.
 */
class UmamoRuleSetProviderTest {
	@Test
	fun registersEveryUmamoRule() {
		val providers = UmamoRuleSetProvider().getRuleProviders()

		assertEquals(
			listOf(
				"umamo:blank-line-around-control-flow",
				"umamo:docblock-tags",
				"umamo:function-docblock",
				"umamo:no-comment-emphasis",
				"umamo:no-single-letter-name",
				"umamo:no-unit-only-block",
				"umamo:statement-if-braces",
			),
			providers.map { provider -> provider.createNewRuleInstance().ruleId.value },
		)
	}
}