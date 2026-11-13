package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType

/**
 * Reports a declared name that is a single letter.
 *
 * A name says what the thing is: `pageIndex`, not `i`; `height`, not `h`; `alpha`, not `a`.  The rule
 * reads the names a file declares for functions, properties, local variables, parameters of every kind
 * (function, constructor, lambda, loop, `catch`), and destructured values.
 *
 * The coordinate and color-channel letters are permitted: `x`, `y`, `z`, `u`, `v`, and `r`, `g`, `b`.
 * So is the parameter of an override, whose name the function it overrides has already chosen.  A type
 * parameter is not a variable and is not looked at.
 *
 * Math is the place a single letter can be the right name: the operands of a formula written the way
 * its source writes it.  Such a function or file says so with
 * `@Suppress("ktlint:umamo:no-single-letter-name")`, which keeps the exception visible where it is made.
 *
 * Nothing here can be autocorrected, since a rename has to be chosen and carried to every use.
 */
public class NoSingleLetterNameRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:no-single-letter-name"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/**
	 * Reports the name of a declaration when it is one letter outside the permitted set.
	 *
	 * @param ASTNode node The node being visited; only an identifier that names a declaration is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType != ElementType.IDENTIFIER) {
			return
		}

		val declaration = node.treeParent ?: return

		if (declaration.elementType !in NAMING_TYPES) {
			return
		}

		val name = node.text.trim('`')

		if (name.length != 1 || !name[0].isLetter() || name[0] in PERMITTED_LETTERS) {
			return
		}

		if (declaration.elementType == ElementType.VALUE_PARAMETER && belongsToOverride(declaration)) {
			return
		}

		emit(node.startOffset, "`$name` is a single-letter name; spell out what it is", false)
	}

	/**
	 * Tells whether a parameter is declared by a function that overrides another.
	 *
	 * @param ASTNode parameter A VALUE_PARAMETER node.
	 * @return Boolean True when its function carries the `override` modifier.
	 */
	private fun belongsToOverride(parameter: ASTNode): Boolean {
		val function = parameter.treeParent?.treeParent ?: return false

		return function.elementType == ElementType.FUN && function.findChildByType(ElementType.MODIFIER_LIST)?.findChildByType(ElementType.OVERRIDE_KEYWORD) != null
	}

	private companion object {
		/** The declarations whose own identifier is a name this rule reads. */
		val NAMING_TYPES: Set<IElementType> =
			setOf(
				ElementType.FUN,
				ElementType.PROPERTY,
				ElementType.VALUE_PARAMETER,
				ElementType.DESTRUCTURING_DECLARATION_ENTRY,
			)

		/** Coordinates in model, screen, and texture space, and the color channels. */
		val PERMITTED_LETTERS: Set<Char> = setOf('x', 'y', 'z', 'u', 'v', 'r', 'g', 'b')
	}
}