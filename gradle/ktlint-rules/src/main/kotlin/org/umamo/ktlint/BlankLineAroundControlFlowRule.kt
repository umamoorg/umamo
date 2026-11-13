package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.rule.engine.core.api.ifAutocorrectAllowed
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType

/**
 * Keeps a blank line around every control-flow block and before every jump.
 *
 * Inside a block of statements (a function, accessor, init, or lambda body, a branch or loop body, a
 * script's top level), a statement is set off from the statement before it by a blank line when:
 *
 *  - it is a control-flow block: an `if`, `when`, `for`, `while`, `do`-`while`, or `try` statement that
 *    spans more than one line,
 *  - the statement before it is a control-flow block, or
 *  - it is a jump: `return`, `throw`, `break`, or `continue`.
 *
 * The first statement of a block never takes one, so a block never opens on a blank line, and the
 * standard rules already keep one from closing on one.  Only whole statements count: an `if` or `when`
 * used as a value (`val side = if (...) ... else ...`) and a jump inside an expression (`?: return`)
 * belong to the statement that holds them, and a one-line `if (...) a else b` reads as an expression,
 * not a block.  Comment lines above a statement belong to it, so the blank line goes above them; a
 * comment trailing the previous statement on its line stays with that statement.
 *
 * Autocorrect adds the missing line break to the whitespace the two statements already share, keeping
 * its indentation.  Statements sharing one line are left alone.
 */
public class BlankLineAroundControlFlowRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:blank-line-around-control-flow"),
		about = About(maintainer = "Umamo"),
		// A one-line `if` only becomes a block once it is braced, so the bracing rule goes first and a
		// single format pass leaves the new block with its blank lines.
		visitorModifiers =
			setOf(
				VisitorModifier.RunAfterRule(
					RuleId(STATEMENT_IF_BRACES_RULE_ID),
					VisitorModifier.RunAfterRule.Mode.REGARDLESS_WHETHER_RUN_AFTER_RULE_IS_LOADED_OR_DISABLED,
				),
			),
	),
	RuleAutocorrectApproveHandler {
	/**
	 * Checks each pair of neighboring statements in a block.
	 *
	 * @param ASTNode node The node being visited; only a BLOCK is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType != ElementType.BLOCK) {
			return
		}

		var previousStatement: ASTNode? = null
		var child = node.firstChildNode

		while (child != null) {
			if (isStatement(child)) {
				if (previousStatement != null) {
					checkGap(previousStatement, child, emit)
				}

				previousStatement = child
			}

			child = child.treeNext
		}
	}

	/**
	 * Reports, and on approval corrects, a missing blank line between two neighboring statements.
	 *
	 * @param ASTNode previousStatement The statement above.
	 * @param ASTNode statement The statement below it.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	private fun checkGap(
		previousStatement: ASTNode,
		statement: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		val reason = blankLineReason(previousStatement, statement) ?: return
		var firstLineBreak: ASTNode? = null
		var between = previousStatement.treeNext

		while (between != null && between !== statement) {
			if (between.elementType == ElementType.WHITE_SPACE) {
				if (between.text.count { character -> character == '\n' } >= 2) {
					return
				}

				if (firstLineBreak == null && between.textContains('\n')) {
					firstLineBreak = between
				}
			}

			between = between.treeNext
		}

		// No line break at all: the two share a line, which is not this rule's to split.
		val lineBreak = firstLineBreak ?: return
		emit(lineBreak.startOffset + lineBreak.textLength, reason, true).ifAutocorrectAllowed {
			val whitespace = lineBreak.text
			val breakIndex = whitespace.indexOf('\n')
			(lineBreak as LeafPsiElement).rawReplaceWithText(whitespace.substring(0, breakIndex) + "\n" + whitespace.substring(breakIndex))
		}
	}

	/**
	 * Says why two neighboring statements need a blank line between them.
	 *
	 * @param ASTNode previousStatement The statement above.
	 * @param ASTNode statement The statement below it.
	 * @return String? The violation message, or null when the two may sit on adjacent lines.
	 */
	private fun blankLineReason(previousStatement: ASTNode, statement: ASTNode): String? {
		if (isControlFlowBlock(statement)) {
			return "Expected a blank line before this ${keywordOf(statement)} block"
		}

		if (isControlFlowBlock(previousStatement)) {
			return "Expected a blank line after the ${keywordOf(previousStatement)} block above"
		}

		if (isJump(statement)) {
			return "Expected a blank line before this ${keywordOf(statement)}"
		}

		return null
	}

	/**
	 * Tells whether a block child is a statement rather than a brace, separator, comment, or whitespace.
	 *
	 * @param ASTNode node A direct child of a BLOCK.
	 * @return Boolean True for a statement.
	 */
	private fun isStatement(node: ASTNode): Boolean = node.elementType !in NON_STATEMENT_TYPES

	/**
	 * Tells whether a statement is a control-flow block: a control-flow statement spanning several lines.
	 *
	 * @param ASTNode statement A statement in a block.
	 * @return Boolean True for a multi-line if, when, for, while, do-while, or try.
	 */
	private fun isControlFlowBlock(statement: ASTNode): Boolean {
		val expression = unwrapped(statement)

		return expression.elementType in CONTROL_FLOW_TYPES && expression.textContains('\n')
	}

	/**
	 * Tells whether a statement is a jump.
	 *
	 * @param ASTNode statement A statement in a block.
	 * @return Boolean True for return, throw, break, or continue, labeled or not.
	 */
	private fun isJump(statement: ASTNode): Boolean = unwrapped(statement).elementType in JUMP_TYPES

	/**
	 * Names a statement by its leading keyword, for the violation message.
	 *
	 * @param ASTNode statement A control-flow or jump statement.
	 * @return String The keyword, such as `if` or `return`.
	 */
	private fun keywordOf(statement: ASTNode): String = "`${unwrapped(statement).firstChildNode?.text ?: statement.text}`"

	/**
	 * Looks through labels, annotations, and a script's statement wrapper to the expression a statement holds.
	 *
	 * @param ASTNode statement A statement in a block.
	 * @return ASTNode The expression itself, or the statement when it wraps nothing.
	 */
	private fun unwrapped(statement: ASTNode): ASTNode {
		var expression = statement

		while (expression.elementType in WRAPPER_TYPES) {
			var inner = expression.lastChildNode

			while (inner != null && inner.elementType in NON_STATEMENT_TYPES) {
				inner = inner.treePrev
			}

			expression = inner ?: return expression
		}

		return expression
	}

	private companion object {
		/** Block children that are not statements. */
		val NON_STATEMENT_TYPES: Set<IElementType> =
			setOf(
				ElementType.WHITE_SPACE,
				ElementType.EOL_COMMENT,
				ElementType.BLOCK_COMMENT,
				ElementType.KDOC,
				ElementType.LBRACE,
				ElementType.RBRACE,
				ElementType.SEMICOLON,
			)

		/**
		 * Nodes that wrap the expression a statement really is: a label, an annotation, and the
		 * initializer a script puts around each top-level expression.
		 */
		val WRAPPER_TYPES: Set<IElementType> =
			setOf(
				ElementType.LABELED_EXPRESSION,
				ElementType.ANNOTATED_EXPRESSION,
				ElementType.SCRIPT_INITIALIZER,
			)

		/** Statements that open a control-flow block. */
		val CONTROL_FLOW_TYPES: Set<IElementType> =
			setOf(
				ElementType.IF,
				ElementType.WHEN,
				ElementType.FOR,
				ElementType.WHILE,
				ElementType.DO_WHILE,
				ElementType.TRY,
			)

		/** Statements that leave the block. */
		val JUMP_TYPES: Set<IElementType> =
			setOf(
				ElementType.RETURN,
				ElementType.THROW,
				ElementType.BREAK,
				ElementType.CONTINUE,
			)
	}
}