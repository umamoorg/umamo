package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.rule.engine.core.api.ifAutocorrectAllowed
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType

/**
 * Writes a braced block that holds nothing but `Unit` as the empty block `{}`.
 *
 * A bare `Unit` inside braces is an expression nothing reads, which the Kotlin compiler warns about,
 * and `{}` is the same value wherever the block stands: a function body, a branch of an `if` or `when`,
 * a loop or `try` body.  The usual source is a do-nothing `when` branch written `X -> Unit`: the
 * standard `when-entry-bracing` rule braces every branch once any branch is braced, which turns it into
 * `X -> { Unit }`, so this rule runs after that one and a single format pass leaves `X -> {}`.
 *
 * A lambda body is left alone, since a lambda's last expression is its value rather than an unused one.
 * Comments in the block stay, and only the `Unit` goes.
 */
public class NoUnitOnlyBlockRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:no-unit-only-block"),
		about = About(maintainer = "Umamo"),
		visitorModifiers =
			setOf(
				VisitorModifier.RunAfterRule(
					RuleId(WHEN_ENTRY_BRACING_RULE_ID),
					VisitorModifier.RunAfterRule.Mode.REGARDLESS_WHETHER_RUN_AFTER_RULE_IS_LOADED_OR_DISABLED,
				),
			),
	),
	RuleAutocorrectApproveHandler {
	/**
	 * Reports, and on approval empties, a braced block whose only statement is `Unit`.
	 *
	 * @param ASTNode node The node being visited; only a braced BLOCK is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		// A lambda's BLOCK carries no braces of its own; the function literal around it holds them.
		if (node.elementType != ElementType.BLOCK || node.firstChildNode?.elementType != ElementType.LBRACE) {
			return
		}

		val statement = node.getChildren(null).filter { child -> child.elementType !in TRIVIA_TYPES }.singleOrNull() ?: return

		if (statement.elementType != ElementType.REFERENCE_EXPRESSION || statement.text != "Unit") {
			return
		}

		emit(statement.startOffset, "A block holding only `Unit` leaves it unused; write `{}`", true).ifAutocorrectAllowed {
			removeUnit(node, statement)
		}
	}

	/**
	 * Takes the `Unit` out of a block, with the whitespace of the line it stood on.
	 *
	 * @param ASTNode block The braced block.
	 * @param ASTNode unit The block's `Unit` statement.
	 */
	private fun removeUnit(block: ASTNode, unit: ASTNode) {
		val before = unit.treePrev
		val after = unit.treeNext
		block.removeChild(unit)

		if (before?.elementType == ElementType.WHITE_SPACE && after?.elementType == ElementType.WHITE_SPACE) {
			// Keep the whitespace that lines up what remains: the line break in front of a comment that
			// trailed the `Unit` on its line, otherwise the closing brace's indentation.
			val afterLeadsTrailingComment = !after.textContains('\n') && after.treeNext?.elementType in COMMENT_TYPES
			block.removeChild(if (afterLeadsTrailingComment) after else before)
		}

		val remaining = block.getChildren(null)

		if (remaining.all { child -> child.elementType in BRACE_AND_WHITESPACE_TYPES }) {
			for (child in remaining) {
				if (child.elementType == ElementType.WHITE_SPACE) {
					block.removeChild(child)
				}
			}
		}
	}

	private companion object {
		/** The standard rule that braces every `when` branch, which this rule runs after. */
		const val WHEN_ENTRY_BRACING_RULE_ID = "standard:when-entry-bracing"

		/** Comment element types. */
		val COMMENT_TYPES: Set<IElementType> = setOf(ElementType.EOL_COMMENT, ElementType.BLOCK_COMMENT, ElementType.KDOC)

		/** Block children that are not statements. */
		val TRIVIA_TYPES: Set<IElementType> = COMMENT_TYPES + setOf(ElementType.WHITE_SPACE, ElementType.LBRACE, ElementType.RBRACE)

		/** The children of a block that is empty apart from its layout. */
		val BRACE_AND_WHITESPACE_TYPES: Set<IElementType> = setOf(ElementType.WHITE_SPACE, ElementType.LBRACE, ElementType.RBRACE)
	}
}