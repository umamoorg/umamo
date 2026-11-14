package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Reports a top-level property of the command tables that is not private.
 *
 * A command table is a function that returns its commands, and the registry is where anything that shows a set
 * of them reads it: a menu, a pie, or a header control names the commands' group and is handed the registered
 * ones, in the table's order.  A property a table leaves visible is a second list of the same commands, read
 * past the registry by whatever imports it, and the two come apart the first time a command joins one and not
 * the other.
 *
 * Only properties are read.  The types a table shares are left alone - the payload a command takes, the
 * routing, the availability tiers - and so are its functions, which is what a table is.  Nothing is
 * autocorrected: a property something outside the file already reads cannot simply be made private, and the
 * fix is to name the commands' group on them and list that group from the registry.
 */
public class NoCommandTableExportRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:no-command-table-export"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/**
	 * Reports each property a command table file declares at its top level and does not keep private.
	 *
	 * @param ASTNode node The node being visited; only the FILE node is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType != ElementType.FILE || !isInPackage(packageNameOf(node), COMMAND_TABLES_PACKAGE)) {
			return
		}

		for (declaration in node.getChildren(null)) {
			if (declaration.elementType != ElementType.PROPERTY || isPrivate(declaration)) {
				continue
			}

			// The name is where the report points: a docblock above the property is part of its node.
			val name = declaration.findChildByType(ElementType.IDENTIFIER) ?: declaration

			emit(name.startOffset, "`${name.text}` is a command table's own data and stays private: whatever lists related commands reads them from the registry by their group (`inGroup`)", false)
		}
	}

	/**
	 * Tells whether a declaration is marked private.
	 *
	 * @param ASTNode declaration A declaration node.
	 * @return Boolean True when its modifiers include `private`.
	 */
	private fun isPrivate(declaration: ASTNode): Boolean = declaration.findChildByType(ElementType.MODIFIER_LIST)?.findChildByType(ElementType.PRIVATE_KEYWORD) != null
}