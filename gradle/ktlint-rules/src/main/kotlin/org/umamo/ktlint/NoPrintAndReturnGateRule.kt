package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType

/**
 * Reports a test that announces it cannot run and then simply returns.
 *
 * A test that returns reports PASSED, whatever it printed first, so a run that verified nothing reads
 * exactly like one that verified everything.  The test kit's `skipTest(reason)` ends the test as SKIPPED
 * with the reason in its report, and never returns, so it stands wherever the print and the return
 * stood.  Two shapes are read:
 *
 * - a `println(...)` with a bare `return` as the next statement of the same block, which is how a gate
 *   reads in an `if` body or in the `run` block after an elvis;
 * - a `return println(...)`, the same gate written on one line.
 *
 * It is meant for test sources, where .editorconfig turns it on.  A helper that passes over one item of
 * many is caught too, since it reads the same: have it return whether it used the item, so its caller can
 * skip when nothing was used.  Nothing is autocorrected, because the message becomes the skip's reason
 * and the code after the gate may lean on the return.
 */
public class NoPrintAndReturnGateRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:no-print-and-return-gate"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/**
	 * Reports a `return` that follows a `println`, or that returns one.
	 *
	 * @param ASTNode node The node being visited; only a RETURN is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType != ElementType.RETURN) {
			return
		}

		val returned = node.getChildren(null).lastOrNull { child -> child.elementType !in NOT_A_RETURNED_VALUE }

		if (returned != null) {
			if (isPrintCall(returned)) {
				emit(node.startOffset, MESSAGE, false)
			}

			return
		}

		var previous = node.treePrev

		while (previous != null && previous.elementType in TRIVIA_TYPES) {
			previous = previous.treePrev
		}

		if (previous != null && isPrintCall(previous)) {
			emit(previous.startOffset, MESSAGE, false)
		}
	}

	/**
	 * Tells whether a node is a call to `println` or `print`.
	 *
	 * @param ASTNode node The statement or expression.
	 * @return Boolean True for the bare call, false for anything else, a qualified call included.
	 */
	private fun isPrintCall(node: ASTNode): Boolean = node.elementType == ElementType.CALL_EXPRESSION && node.firstChildNode?.text in PRINT_FUNCTIONS

	private companion object {
		/** What the standard library prints with. */
		val PRINT_FUNCTIONS: Set<String> = setOf("println", "print")

		/** The parts of a `return` that are not the value it returns. */
		val NOT_A_RETURNED_VALUE: Set<IElementType> = setOf(ElementType.RETURN_KEYWORD, ElementType.LABEL_QUALIFIER, ElementType.WHITE_SPACE)

		/** What may stand between two statements without being one. */
		val TRIVIA_TYPES: Set<IElementType> = setOf(ElementType.WHITE_SPACE, ElementType.EOL_COMMENT, ElementType.BLOCK_COMMENT)

		/** The violation. */
		const val MESSAGE = "A test that cannot run ends in `skipTest(reason)` from the test kit, which reports it SKIPPED; a print and a return reports PASSED"
	}
}