package org.umamo.ktlint

import com.pinterest.ktlint.cli.ruleset.core.api.RuleSetProviderV3
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import com.pinterest.ktlint.rule.engine.core.api.RuleSetId

/**
 * The `umamo` rule set: Umamo's house style rules that ktlint's standard set does not carry.
 *
 * The engine finds it through the service file in META-INF/services.  Its rules run beside the
 * standard ones in both ktlintCheck and ktlintFormat, and an .editorconfig turns one off the usual way
 * (`ktlint_umamo_<rule-name> = disabled`).
 */
public class UmamoRuleSetProvider : RuleSetProviderV3(RuleSetId(UMAMO_RULE_SET_ID)) {
	/**
	 * Lists the rule set's rules, each built fresh per file by its provider.
	 *
	 * @return Set<RuleProvider> One provider per rule.
	 */
	override fun getRuleProviders(): Set<RuleProvider> =
		setOf(
			RuleProvider { BlankLineAroundControlFlowRule() },
			RuleProvider { CommentSentenceSpacingRule() },
			RuleProvider { DocblockTagsRule() },
			RuleProvider { FunctionDocblockRule() },
			RuleProvider { NoCommentEmphasisRule() },
			RuleProvider { NoSingleLetterNameRule() },
			RuleProvider { NoUnitOnlyBlockRule() },
			RuleProvider { StatementIfBracesRule() },
		)
}

/** The rule set id every Umamo rule id is prefixed with. */
internal const val UMAMO_RULE_SET_ID = "umamo"