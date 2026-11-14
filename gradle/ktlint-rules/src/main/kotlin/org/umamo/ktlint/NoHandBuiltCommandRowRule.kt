package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Reports a command's chord looked up anywhere but where commands are shown with one.
 *
 * A menu row or a button that stands for a command is built from the command: its label, its glyph, when it is
 * grayed, and the chord the keymap binds to it all come with it, from the builders beside the command registry.  A
 * widget that asks the keymap for a chord is building such a row by hand, and what it builds can run one command
 * and advertise another's chord, or stay enabled while its command does not apply.
 *
 * So `chordFor` is called only by what shows a command with its chord for every surface: the menu builders and the
 * palette beside the registry, the keybindings editor, and the status bar's hints.  Nothing is autocorrected: the
 * fix is to build the row from its command.
 */
public class NoHandBuiltCommandRowRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:no-hand-built-command-row"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/** Whether this file may look a chord up: it is in one of the packages that show commands with theirs. */
	private var showsChords = false

	/**
	 * Reports a chord lookup, called or referenced, in a file that has no business making one.
	 *
	 * @param ASTNode node The node being visited.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType == ElementType.FILE) {
			val packageName = packageNameOf(node)
			showsChords = CHORD_PACKAGES.any { chordPackage -> isInPackage(packageName, chordPackage) }

			return
		}

		if (showsChords) {
			return
		}

		// A call names the function first; a reference to it (`keymap::chordFor`) names it last.
		val lookup =
			when (node.elementType) {
				ElementType.CALL_EXPRESSION -> node.firstChildNode
				ElementType.CALLABLE_REFERENCE_EXPRESSION -> node.lastChildNode
				else -> null
			}

		if (lookup?.elementType == ElementType.REFERENCE_EXPRESSION && lookup.text == CHORD_LOOKUP) {
			emit(lookup.startOffset, "A command's chord comes with its row: build the row from the command (`rememberFamilyMenu`, `rememberCommandMenu`, `commandMenuItem`) and never ask the keymap for it by hand", false)
		}
	}

	private companion object {
		/** The keymap's lookup of the chord bound to a command. */
		const val CHORD_LOOKUP = "chordFor"

		/** The packages that show a command with its chord: the registry's own, the keybindings editor's, and the status bar's. */
		val CHORD_PACKAGES = listOf("org.umamo.ui.action", "org.umamo.ui.settings", "org.umamo.ui.workspace.statusbar")
	}
}