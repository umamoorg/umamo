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
import com.pinterest.ktlint.rule.engine.core.api.ifAutocorrectAllowed
import com.pinterest.ktlint.rule.engine.core.api.indent20
import com.pinterest.ktlint.rule.engine.core.api.isPartOfComment20
import com.pinterest.ktlint.rule.engine.core.api.isWhiteSpace20
import com.pinterest.ktlint.rule.engine.core.api.isWhiteSpaceWithNewline20
import com.pinterest.ktlint.rule.engine.core.api.nextCodeSibling20
import com.pinterest.ktlint.rule.engine.core.api.prevLeaf
import com.pinterest.ktlint.rule.engine.core.api.replaceTextWith
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl

/**
 * Writes every `Command(...)` with its arguments one per line: each argument and the closing `)` open a
 * line of their own, and the last argument carries a trailing comma.
 *
 * The standard `argument-list-wrapping` rule wraps a call's arguments only once they already span lines,
 * and with no column limit nothing else makes them, so a declaration stays on one line or is wrapped
 * depending on how it was first typed.  A command table reads as a column of declarations, so this rule
 * wraps each one however few arguments it has.  The trailing lambda is the handler's body and keeps the
 * shape it was written in.
 *
 * The class is followed through the file's imports, so a class that merely shares the name is left alone
 * and an aliased import is still caught.
 */
public class CommandArgumentWrappingRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:command-argument-wrapping"),
		about = About(maintainer = "Umamo"),
		usesEditorConfigProperties = setOf(INDENT_SIZE_PROPERTY, INDENT_STYLE_PROPERTY),
	),
	RuleAutocorrectApproveHandler {
	/** The indentation the file is written in, which the wrapped arguments are laid out by. */
	private var indentConfig = IndentConfig.DEFAULT_INDENT_CONFIG

	/** The name this file calls the command class by, or null where no call can be one. */
	private var commandName: String? = null

	/**
	 * Reads the indentation the file is written in.
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
	 * Reports, and on approval wraps, a call to the command class's constructor not written one argument per line.
	 *
	 * @param ASTNode node The node being visited.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType == ElementType.FILE) {
			commandName = localNameOf(node, COMMAND_CLASS)

			return
		}

		if (node.elementType != ElementType.CALL_EXPRESSION || commandName == null) {
			return
		}

		val callee = node.firstChildNode

		if (callee?.elementType != ElementType.REFERENCE_EXPRESSION || callee.text != commandName) {
			return
		}

		val argumentList = node.findChildByType(ElementType.VALUE_ARGUMENT_LIST) ?: return
		val closingParenthesis = argumentList.findChildByType(ElementType.RPAR) ?: return
		val arguments = argumentList.getChildren(null).filter { child -> child.elementType == ElementType.VALUE_ARGUMENT }

		if (arguments.isEmpty() || isWrapped(arguments, closingParenthesis)) {
			return
		}

		emit(node.startOffset, "A `Command(...)` puts each argument and the closing parenthesis on a line of its own, with a trailing comma", true)
			.ifAutocorrectAllowed {
				wrap(node, argumentList, arguments, closingParenthesis)
			}
	}

	/**
	 * Tells whether an argument list is already written one argument per line.
	 *
	 * @param List<ASTNode> arguments The list's VALUE_ARGUMENT nodes, in source order.
	 * @param ASTNode closingParenthesis The list's RPAR.
	 * @return Boolean True when every argument and the parenthesis open a line, and the last argument has its comma.
	 */
	private fun isWrapped(arguments: List<ASTNode>, closingParenthesis: ASTNode): Boolean =
		arguments.all { argument -> startsLine(argument) } && startsLine(closingParenthesis) && hasTrailingComma(arguments.last())

	/**
	 * Tells whether a node is the first code on its line.
	 *
	 * @param ASTNode node An argument or the closing parenthesis.
	 * @return Boolean True when a line break stands between it and the code before it, with only comments between.
	 */
	private fun startsLine(node: ASTNode): Boolean {
		var before = node.prevLeaf

		while (before != null && (before.isWhiteSpace20 || before.isPartOfComment20)) {
			if (before.isWhiteSpaceWithNewline20) {
				return true
			}

			before = before.prevLeaf
		}

		return false
	}

	/**
	 * Tells whether the last argument of a list is followed by a comma.
	 *
	 * @param ASTNode lastArgument The list's last VALUE_ARGUMENT.
	 * @return Boolean True when the next code after it is a comma rather than the closing parenthesis.
	 */
	private fun hasTrailingComma(lastArgument: ASTNode): Boolean = lastArgument.nextCodeSibling20?.elementType == ElementType.COMMA

	/**
	 * Puts each argument one indent level below the call's line, the closing parenthesis back on that line's indent,
	 * and a comma after the last argument.
	 *
	 * @param ASTNode call The CALL_EXPRESSION.
	 * @param ASTNode argumentList Its VALUE_ARGUMENT_LIST.
	 * @param List<ASTNode> arguments The list's VALUE_ARGUMENT nodes, in source order.
	 * @param ASTNode closingParenthesis The list's RPAR.
	 */
	private fun wrap(call: ASTNode, argumentList: ASTNode, arguments: List<ASTNode>, closingParenthesis: ASTNode) {
		// Read both before the tree changes.  A call that starts partway along a line, such as the argument of
		// another call, is laid out from that line; the indent rule moves it along if the outer call wraps too.
		val callIndent = call.indent20
		val argumentIndent = indentConfig.childIndentOf(call)

		for (argument in arguments) {
			wrapBefore(argumentList, argument, argumentIndent)
		}

		val lastArgument = arguments.last()

		if (!hasTrailingComma(lastArgument)) {
			argumentList.addChild(LeafPsiElement(ElementType.COMMA, ","), lastArgument.treeNext)
		}

		wrapBefore(argumentList, closingParenthesis, callIndent)
	}

	/**
	 * Starts a line at a node of the argument list, unless one already starts there.
	 *
	 * @param ASTNode argumentList The VALUE_ARGUMENT_LIST holding the node.
	 * @param ASTNode node An argument or the closing parenthesis.
	 * @param String indent The line break and indentation to put before it.
	 */
	private fun wrapBefore(argumentList: ASTNode, node: ASTNode, indent: String) {
		if (startsLine(node)) {
			return
		}

		val before = node.prevLeaf

		if (before != null && before.isWhiteSpace20) {
			before.replaceTextWith(indent)
		} else {
			argumentList.addChild(PsiWhiteSpaceImpl(indent), node)
		}
	}
}