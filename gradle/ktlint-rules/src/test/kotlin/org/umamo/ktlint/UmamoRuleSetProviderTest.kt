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
				"umamo:comment-sentence-spacing",
				"umamo:docblock-tags",
				"umamo:function-docblock",
				"umamo:import-boundary",
				"umamo:no-command-outside-tables",
				"umamo:no-comment-emphasis",
				"umamo:no-fully-qualified-name",
				"umamo:no-main-dispatcher",
				"umamo:no-print-and-return-gate",
				"umamo:no-single-letter-name",
				"umamo:no-unit-only-block",
				"umamo:session-extension-placement",
				"umamo:statement-if-braces",
			),
			providers.map { provider -> provider.createNewRuleInstance().ruleId.value },
		)
	}
}