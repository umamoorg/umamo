package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Reports `Dispatchers.Main` named in library code.
 *
 * The main dispatcher is something a platform installs, and the desktop has none: Compose Desktop brings
 * no main dispatcher of its own, so library code that names one compiles everywhere and fails only when
 * it first runs there.  A library delivers on the scope its caller hands it, and the application, which
 * knows its platform, picks the dispatcher.
 *
 * The applications' own packages are left alone, and nothing is autocorrected.
 */
public class NoMainDispatcherRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:no-main-dispatcher"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	private var libraryCode = false

	/**
	 * Reports `Dispatchers.Main` as an expression or as an import, in a library package.
	 *
	 * @param ASTNode node The node being visited.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		when (node.elementType) {
			ElementType.FILE -> {
				val packageName = packageNameOf(node)
				libraryCode = isInPackage(packageName, LIBRARY_ROOT) && APPLICATION_PACKAGES.none { applicationPackage -> isInPackage(packageName, applicationPackage) }
			}

			ElementType.IMPORT_DIRECTIVE -> {
				if (libraryCode && importedPathOf(node) == "kotlinx.coroutines.Dispatchers.Main") {
					emit(node.startOffset, MESSAGE, false)
				}
			}

			ElementType.DOT_QUALIFIED_EXPRESSION -> {
				// `Dispatchers.Main.immediate` holds `Dispatchers.Main` as its receiver, which is the node reported.
				if (libraryCode && node.lastChildNode?.text == "Main" && node.firstChildNode?.text?.substringAfterLast('.') == "Dispatchers" && node.treeParent?.elementType != ElementType.IMPORT_DIRECTIVE) {
					emit(node.startOffset, MESSAGE, false)
				}
			}
		}
	}

	private companion object {
		/** The package every Umamo module lives under. */
		const val LIBRARY_ROOT = "org.umamo"

		/** The applications, which know their platform and may pick its main dispatcher. */
		val APPLICATION_PACKAGES: List<String> = listOf("org.umamo.editor", "org.umamo.cli")

		/** The violation. */
		const val MESSAGE = "Library code must not name `Dispatchers.Main`, which the desktop does not have; deliver on the scope the caller passes"
	}
}