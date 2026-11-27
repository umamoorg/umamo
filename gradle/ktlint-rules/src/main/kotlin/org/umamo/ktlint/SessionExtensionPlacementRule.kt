package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Reports an extension function on `EditorSession` declared outside the packages that hold editing logic.
 *
 * Editing logic over the session belongs in `:edit`, where it is tested without Compose and shared by
 * every front end; `:ui` keeps the pointer and composition bookkeeping that feeds it plain data.  An
 * extension on the session is what editing logic looks like when it grows in the wrong module, since
 * `:ui` can see the session and the compiler has no objection.  `org.umamo.ui.transform` is the one home
 * outside `:edit`: its edits need the deformation eval, which lives in `:render`, a sibling `:edit`
 * cannot see.
 *
 * Nothing is autocorrected.  The fix is a move: into `:edit` when the function needs only the session
 * and the model, into `org.umamo.ui.transform` when it needs the eval.
 */
public class SessionExtensionPlacementRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:session-extension-placement"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/** The name this file calls the session class by, or null where an extension on it is in place. */
	private var sessionName: String? = null

	/**
	 * Reports a function whose receiver is the editor session, in a package that may not hold one.
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
			sessionName = localNameOf(node, SESSION_CLASS).takeUnless { ALLOWED_PACKAGES.any { allowedPackage -> isInPackage(packageName, allowedPackage) } }

			return
		}

		if (node.elementType != ElementType.FUN || sessionName == null) {
			return
		}

		// A receiver is the type written before the dot that leads to the function's name.
		val receiver = node.getChildren(null).firstOrNull { child -> child.elementType == ElementType.TYPE_REFERENCE && child.treeNext?.elementType == ElementType.DOT } ?: return

		if (receiver.text.removeSuffix("?") == sessionName) {
			emit(receiver.startOffset, "An extension on `EditorSession` is editing logic: it lives in :edit, or in org.umamo.ui.transform when it needs the deformation eval", false)
		}
	}

	private companion object {
		/** The session class. */
		const val SESSION_CLASS = "org.umamo.edit.EditorSession"

		/** The packages that hold editing logic: all of `:edit`, and the one `:ui` package that needs the eval. */
		val ALLOWED_PACKAGES: List<String> = listOf("org.umamo.edit", "org.umamo.ui.transform")
	}
}