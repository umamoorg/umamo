package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType

/**
 * Reports a comment that cites one of the plan documents by its path.
 *
 * The plans are working notes that are not part of the repository, so a comment pointing at one sends
 * its reader to a file they do not have, and to a decision id that means nothing without it.  A comment
 * states the rule itself, or cites the format documents, which ship beside the code.
 *
 * Only the path is read.  A phase label or a decision id written without it is too close to an ordinary
 * name to tell apart (`D65`, `D3D12`), and stays a matter for review.  Nothing is autocorrected: what
 * replaces a citation is the rule it stood for.
 */
public class NoPlanCitationRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:no-plan-citation"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/**
	 * Reports each plan path a comment or a docblock names.
	 *
	 * @param ASTNode node The node being visited; only a comment or a docblock is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType !in COMMENT_TYPES) {
			return
		}

		for (citation in PLAN_PATH.findAll(node.text)) {
			emit(node.startOffset + citation.range.first, MESSAGE, false)
		}
	}

	private companion object {
		/** The nodes that hold comment text. */
		val COMMENT_TYPES: Set<IElementType> = setOf(ElementType.EOL_COMMENT, ElementType.BLOCK_COMMENT, ElementType.KDOC)

		/** The plan directory as a path, under either name it has had.  A longer word that starts the same is not it. */
		val PLAN_PATH = Regex("""\bdocs/plans?\b""")

		/** The violation. */
		const val MESSAGE = "A comment must not cite a plan document, which is not part of the repository; state the rule, or cite `docs/format/`"
	}
}