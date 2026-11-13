package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Requires a docblock on every named function.
 *
 * A function declared with a name carries one whether it is top-level, a member, an extension, or local
 * to another function.  A lambda and an anonymous function have no name to document and are not looked at.
 *
 * An override is left alone.  It needs a docblock when it does something its original's docblock does
 * not already say, and that is a judgment about meaning this rule cannot make: a long override can be a
 * faithful implementation, and a one-line override can change what the original promised.
 *
 * Nothing here can be autocorrected.  An .editorconfig section turns the rule off for a set of files the
 * usual way (`ktlint_umamo_function-docblock = disabled`).
 */
public class FunctionDocblockRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:function-docblock"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/**
	 * Reports a named function that is not an override and has no docblock.
	 *
	 * @param ASTNode node The node being visited; only a function declaration is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType != ElementType.FUN) {
			return
		}

		// An anonymous function (`fun(value: Int) = value`) has no name.
		val name = node.findChildByType(ElementType.IDENTIFIER) ?: return

		if (node.findChildByType(ElementType.KDOC) != null || isOverride(node)) {
			return
		}

		emit(name.startOffset, "Function `${name.text}` has no docblock", false)
	}

	/**
	 * Tells whether a function overrides one it inherits.
	 *
	 * @param ASTNode function The FUN node.
	 * @return Boolean True when it carries the `override` modifier.
	 */
	private fun isOverride(function: ASTNode): Boolean = function.findChildByType(ElementType.MODIFIER_LIST)?.findChildByType(ElementType.OVERRIDE_KEYWORD) != null
}