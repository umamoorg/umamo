package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.IndentConfig
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.EditorConfig
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INDENT_SIZE_PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.editorconfig.INDENT_STYLE_PROPERTY
import com.pinterest.ktlint.rule.engine.core.api.firstChildLeafOrSelf20
import com.pinterest.ktlint.rule.engine.core.api.ifAutocorrectAllowed
import com.pinterest.ktlint.rule.engine.core.api.indent20
import com.pinterest.ktlint.rule.engine.core.api.isPartOfComment20
import com.pinterest.ktlint.rule.engine.core.api.isWhiteSpace20
import com.pinterest.ktlint.rule.engine.core.api.isWhiteSpaceWithoutNewline20
import com.pinterest.ktlint.rule.engine.core.api.lastChildLeafOrSelf20
import com.pinterest.ktlint.rule.engine.core.api.nextLeaf
import com.pinterest.ktlint.rule.engine.core.api.nextSibling
import com.pinterest.ktlint.rule.engine.core.api.prevLeaf
import com.pinterest.ktlint.rule.engine.core.api.replaceTextWith
import com.pinterest.ktlint.rule.engine.core.api.upsertWhitespaceBeforeMe
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.psi.KtBlockExpression

/**
 * Braces every branch of a statement `if`, with the branch body on its own line.
 *
 * An `if` is a statement when its value provably goes unused:
 *
 *  - its chain has no final `else`, which Kotlin only allows where the value is unused, or
 *  - it stands where no value is read: any statement of a function, accessor, constructor,
 *    initializer, loop, or `finally` body, a statement that is not the last one of its block, or the
 *    last statement of a branch block whose own `if`, `when`, or `try` is a statement by this same test.
 *
 * Every other `if` is left alone as a value: an initializer, argument, operand, `return` value, or
 * expression body, and the last statement of a lambda, whose last expression is its value.  An
 * `else if` continues its chain rather than being a branch that needs braces.
 *
 * The standard `multiline-if-else` rule braces a branch whose body sits on its own line and lets a
 * one-line `if (x) return` pass; this rule runs after it and covers that one-line form.
 */
public class StatementIfBracesRule :
	Rule(
		ruleId = RuleId(STATEMENT_IF_BRACES_RULE_ID),
		about = About(maintainer = "Umamo"),
		visitorModifiers =
			setOf(
				VisitorModifier.RunAfterRule(
					RuleId(MULTILINE_IF_ELSE_RULE_ID),
					VisitorModifier.RunAfterRule.Mode.REGARDLESS_WHETHER_RUN_AFTER_RULE_IS_LOADED_OR_DISABLED,
				),
			),
		usesEditorConfigProperties = setOf(INDENT_SIZE_PROPERTY, INDENT_STYLE_PROPERTY),
	),
	RuleAutocorrectApproveHandler {
	private var indentConfig = IndentConfig.DEFAULT_INDENT_CONFIG

	/**
	 * Reads the indentation the file is written in, which the braces autocorrect adds are laid out by.
	 *
	 * @param EditorConfig editorConfig The file's resolved .editorconfig properties.
	 */
	override fun beforeFirstNode(editorConfig: EditorConfig) {
		indentConfig =
			IndentConfig(
				indentStyle = editorConfig[INDENT_STYLE_PROPERTY],
				tabWidth = editorConfig[INDENT_SIZE_PROPERTY],
			)
	}

	/**
	 * Reports, and on approval braces, a braceless branch of a statement `if`.
	 *
	 * @param ASTNode node The node being visited; only a THEN or ELSE branch is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType != ElementType.THEN && node.elementType != ElementType.ELSE) {
			return
		}

		// A branch with no body at all (`if (x);`) has nothing to brace.
		val body = node.firstChildNode ?: return

		if (body.elementType == ElementType.BLOCK || continuesChain(node, body)) {
			return
		}

		val ifExpression = node.treeParent ?: return

		if (!isStatement(chainRootOf(ifExpression))) {
			return
		}

		emit(body.startOffset, "A statement `if` takes braces around each branch, with the body on its own line", true)
			.ifAutocorrectAllowed {
				wrapInBraces(node, body)
			}
	}

	/**
	 * Tells whether an `else` branch carries the next `if` of its chain rather than a body of its own.
	 *
	 * @param ASTNode branch A THEN or ELSE branch.
	 * @param ASTNode body The branch's body.
	 * @return Boolean True for an `else if`, and for a chain whose value an operator or call then reads.
	 */
	private fun continuesChain(branch: ASTNode, body: ASTNode): Boolean {
		if (branch.elementType != ElementType.ELSE) {
			return false
		}

		if (body.elementType == ElementType.IF) {
			return true
		}

		// `... else if (b) { 1 } else { 2 } ?: 3` and `... else { 2 }.plus(1)` parse with the trailing
		// operator or call wrapped around the last `if` of the chain.
		return body.elementType in CHAIN_READING_TYPES && body.firstChildNode?.elementType == ElementType.IF
	}

	/**
	 * Finds the first `if` of the chain an `if` belongs to.
	 *
	 * @param ASTNode ifExpression An IF node, possibly the `if` of an `else if`.
	 * @return ASTNode The IF that opens the chain.
	 */
	private fun chainRootOf(ifExpression: ASTNode): ASTNode {
		var root = ifExpression

		while (true) {
			val holder = root.treeParent

			if (holder?.elementType != ElementType.ELSE) {
				return root
			}

			root = holder.treeParent ?: return root
		}
	}

	/**
	 * Tells whether an `if` chain is a statement: one whose value provably goes unused.
	 *
	 * @param ASTNode chainRoot The IF that opens the chain.
	 * @return Boolean True when the chain has no final `else`, or stands where no value is read.
	 */
	private fun isStatement(chainRoot: ASTNode): Boolean = !hasFinalElse(chainRoot) || !valueMayBeRead(chainRoot)

	/**
	 * Tells whether an `if` chain ends in a plain `else`, which is what lets it be used as a value.
	 *
	 * @param ASTNode chainRoot The IF that opens the chain.
	 * @return Boolean True when the last `if` of the chain has an `else` branch.
	 */
	private fun hasFinalElse(chainRoot: ASTNode): Boolean {
		var current = chainRoot

		while (true) {
			val elseBranch = current.findChildByType(ElementType.ELSE) ?: return false
			val body = elseBranch.firstChildNode ?: return true

			if (body.elementType != ElementType.IF) {
				return true
			}

			current = body
		}
	}

	/**
	 * Tells whether the value of an `if`, `when`, or `try` may be read where the expression stands.
	 *
	 * @param ASTNode expression The expression, for an `if` the one that opens its chain.
	 * @return Boolean False only where the value provably goes unused.
	 */
	private fun valueMayBeRead(expression: ASTNode): Boolean {
		var node = expression
		var parent = node.treeParent ?: return true

		while (parent.elementType in WRAPPER_TYPES) {
			node = parent
			parent = node.treeParent ?: return true
		}

		return when (parent.elementType) {
			ElementType.BLOCK -> isLastStatement(node) && blockValueMayBeRead(parent)

			// The braceless body of a loop.
			ElementType.BODY -> false

			// The braceless branch of another `if`, or of a `when`: read exactly when that one is.
			ElementType.THEN, ElementType.ELSE -> parent.treeParent?.let { outerIf -> valueMayBeRead(chainRootOf(outerIf)) } ?: true

			ElementType.WHEN_ENTRY -> parent.treeParent?.let { outerWhen -> valueMayBeRead(outerWhen) } ?: true

			else -> true
		}
	}

	/**
	 * Tells whether the last statement of a block may be read as the block's value.
	 *
	 * @param ASTNode block A BLOCK node.
	 * @return Boolean False for a body that yields no value, and for a branch of a statement.
	 */
	private fun blockValueMayBeRead(block: ASTNode): Boolean {
		val owner = block.treeParent ?: return true

		return when (owner.elementType) {
			in VALUELESS_BODY_OWNERS -> false

			ElementType.THEN, ElementType.ELSE -> owner.treeParent?.let { outerIf -> valueMayBeRead(chainRootOf(outerIf)) } ?: true

			ElementType.WHEN_ENTRY -> owner.treeParent?.let { outerWhen -> valueMayBeRead(outerWhen) } ?: true

			ElementType.TRY -> valueMayBeRead(owner)

			ElementType.CATCH -> owner.treeParent?.let { outerTry -> valueMayBeRead(outerTry) } ?: true

			// A lambda's last expression is its value, and nothing here can tell whether it is read.
			else -> true
		}
	}

	/**
	 * Tells whether no statement follows a node in its block.
	 *
	 * @param ASTNode statement A direct child of a BLOCK.
	 * @return Boolean True when only whitespace, comments, and the closing brace follow it.
	 */
	private fun isLastStatement(statement: ASTNode): Boolean {
		var following = statement.treeNext

		while (following != null) {
			if (following.elementType !in NON_STATEMENT_TYPES) {
				return false
			}

			following = following.treeNext
		}

		return true
	}

	/**
	 * Wraps a branch body in braces, the body one indent level below the line its `if` or `else` is on.
	 *
	 * @param ASTNode branch The THEN or ELSE branch.
	 * @param ASTNode body The branch's braceless body.
	 */
	private fun wrapInBraces(branch: ASTNode, body: ASTNode) {
		// What stands between the `)` or `else` and the body: whitespace, and any comment written there.
		val leading = ArrayList<ASTNode>()
		var before = branch.firstChildLeafOrSelf20.prevLeaf

		while (before != null && before.elementType != ElementType.RPAR && before.elementType != ElementType.ELSE_KEYWORD) {
			leading.add(0, before)
			before = before.prevLeaf
		}

		// A comment trailing the body on its line goes into the block with it.  The walk starts from the
		// body's last leaf, since the leaf after a composite node is the first one inside it.
		val trailing = ArrayList<ASTNode>()
		var after = branch.lastChildLeafOrSelf20.nextLeaf

		while (after != null && (after.isWhiteSpaceWithoutNewline20 || after.isPartOfComment20)) {
			trailing.add(after)
			after = after.nextLeaf
		}

		while (trailing.isNotEmpty() && trailing.last().isWhiteSpaceWithoutNewline20) {
			trailing.removeAt(trailing.lastIndex)
		}

		leading.firstOrNull()?.takeIf { leaf -> leaf.isWhiteSpace20 }?.replaceTextWith(" ")

		// Read both before the tree changes: the closing brace lines up with the line the branch began on.
		val branchIndent = branch.indent20
		val bodyIndent = indentConfig.childIndentOf(branch)
		val block = KtBlockExpression(null)
		branch.replaceChild(body, block)
		block.addChild(LeafPsiElement(ElementType.LBRACE, "{"))
		block.addChild(PsiWhiteSpaceImpl(bodyIndent))

		for (leaf in leading.dropWhile { leaf -> leaf.isWhiteSpace20 }) {
			block.addChild(leaf)
		}

		block.addChild(body)

		for (leaf in trailing) {
			block.addChild(leaf)
		}

		block.addChild(PsiWhiteSpaceImpl(branchIndent))
		block.addChild(LeafPsiElement(ElementType.RBRACE, "}"))

		// Keep an `else` on the line of the brace that now closes the branch above it.
		if (branch.elementType == ElementType.THEN) {
			branch.nextSibling { sibling -> !sibling.isPartOfComment20 }?.upsertWhitespaceBeforeMe(" ")
		}
	}

	private companion object {
		/** The standard rule that braces a branch whose body is on its own line, which this rule runs after. */
		const val MULTILINE_IF_ELSE_RULE_ID = "standard:multiline-if-else"

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
		 * Nodes that wrap an expression without reading its value: a label, an annotation, parentheses,
		 * and the initializer a script puts around each top-level expression.
		 */
		val WRAPPER_TYPES: Set<IElementType> =
			setOf(
				ElementType.LABELED_EXPRESSION,
				ElementType.ANNOTATED_EXPRESSION,
				ElementType.PARENTHESIZED,
				ElementType.SCRIPT_INITIALIZER,
			)

		/** Owners of a block body that yields no value, so its last statement is as unread as the rest. */
		val VALUELESS_BODY_OWNERS: Set<IElementType> =
			setOf(
				ElementType.FUN,
				ElementType.BODY,
				ElementType.CLASS_INITIALIZER,
				ElementType.PROPERTY_ACCESSOR,
				ElementType.SECONDARY_CONSTRUCTOR,
				ElementType.FINALLY,
				ElementType.SCRIPT,
			)

		/** Expressions that read the value of the `if` chain they wrap: an operator, or a call on the result. */
		val CHAIN_READING_TYPES: Set<IElementType> = setOf(ElementType.BINARY_EXPRESSION, ElementType.DOT_QUALIFIED_EXPRESSION)
	}
}

/** The rule's id, shared with the rule that must run after it. */
internal const val STATEMENT_IF_BRACES_RULE_ID = "$UMAMO_RULE_SET_ID:statement-if-braces"