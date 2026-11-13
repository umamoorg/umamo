package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Reports a `Command(...)` built anywhere but the command tables.
 *
 * Every operation is a named command in the registry, and the tables in `org.umamo.ui.workspace.commands`
 * are where one is declared: `registerAll` picks up what they list, and the palette, the keymap, and the
 * menus all read the registry.  A command built somewhere else is one none of them knows about, bound to
 * the widget that built it, so a rebind never reaches it.
 *
 * The name is followed through the file's imports, so a class that merely shares the name is left alone
 * and an aliased import is still caught.  Nothing is autocorrected: the fix is to declare the command in
 * a table and dispatch it by id.
 */
public class NoCommandOutsideTablesRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:no-command-outside-tables"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/** The name this file calls the command class by, or null where a call cannot be one to report. */
	private var commandName: String? = null

	/**
	 * Reports a call to the command class's constructor outside the command tables.
	 *
	 * @param ASTNode node The node being visited.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType == ElementType.FILE) {
			commandName = localNameOf(node, COMMAND_CLASS).takeUnless { isInPackage(packageNameOf(node), COMMAND_TABLES_PACKAGE) }

			return
		}

		if (node.elementType != ElementType.CALL_EXPRESSION || commandName == null) {
			return
		}

		val callee = node.firstChildNode

		if (callee?.elementType == ElementType.REFERENCE_EXPRESSION && callee.text == commandName) {
			emit(node.startOffset, "A `Command(...)` is built only in the command tables ($COMMAND_TABLES_PACKAGE), which `registerAll` registers; declare it there and dispatch it by id", false)
		}
	}

	private companion object {
		/** The command class. */
		const val COMMAND_CLASS = "org.umamo.ui.action.Command"

		/** The package the command tables live in, one file per domain. */
		const val COMMAND_TABLES_PACKAGE = "org.umamo.ui.workspace.commands"
	}
}