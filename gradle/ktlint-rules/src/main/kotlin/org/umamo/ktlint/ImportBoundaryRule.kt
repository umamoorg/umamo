package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Reports an import that crosses one of the boundaries the architecture draws between packages.
 *
 * A module dependency says which modules a module may see.  These boundaries are finer than that, or run
 * against it: a package that must stay ignorant of packages its own module can reach.  The compiler takes
 * such an import without complaint, so the boundary holds only as long as nobody writes one.  Each
 * boundary is a row of [BOUNDARIES]: the packages it binds, the imports it forbids them, and the reason,
 * which the violation states.
 *
 * Nothing is autocorrected.  A class reached without an import (one in the file's own package, or one
 * written out in full, which `umamo:no-fully-qualified-name` reports) is not read.
 */
public class ImportBoundaryRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:import-boundary"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	/**
	 * Reports each import of a file that a boundary on the file's package forbids.
	 *
	 * @param ASTNode node The node being visited; only the FILE node is examined.
	 * @param Function3 emit Reports a violation and answers whether to correct it.
	 */
	override fun beforeVisitChildNodes(
		node: ASTNode,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (node.elementType != ElementType.FILE) {
			return
		}

		val packageName = packageNameOf(node)
		val boundaries = BOUNDARIES.filter { boundary -> boundary.packages.matches(packageName) }

		if (boundaries.isEmpty()) {
			return
		}

		for (directive in importDirectivesOf(node)) {
			val importedPath = importedPathOf(directive)
			val crossed = boundaries.firstOrNull { boundary -> boundary.forbiddenImports.containsMatchIn(importedPath) } ?: continue

			emit(directive.startOffset, "`$importedPath` must not be imported into `$packageName`: ${crossed.reason}", false)
		}
	}

	/**
	 * One boundary.
	 *
	 * @property Regex packages Matches, whole, the package names the boundary binds.
	 * @property Regex forbiddenImports Matches, from its start, an imported path those packages may not name.
	 * @property String reason Why, as the violation states it.
	 */
	private class Boundary(val packages: Regex, val forbiddenImports: Regex, val reason: String)

	private companion object {
		/** The boundaries, each one a rule CLAUDE.md states and no module dependency can enforce. */
		val BOUNDARIES: List<Boundary> =
			listOf(
				Boundary(
					packages = Regex("""org\.umamo\.ui\.tracks(\..+)?"""),
					forbiddenImports = Regex("""^org\.umamo\.(?!ui\.(tracks|kit|theme)\b)"""),
					reason = "the track sheet is domain-agnostic so a time-based dope sheet can reuse it, and sees only the kit and the theme",
				),
				Boundary(
					packages = Regex("""org\.umamo\.edit"""),
					forbiddenImports = Regex("""^org\.umamo\.edit\.[a-z][a-z0-9_]*\."""),
					reason = "inside :edit a domain subpackage imports the root package, never the reverse",
				),
				Boundary(
					packages = Regex("""org\.umamo\.render(\..+)?"""),
					forbiddenImports = Regex("""^org\.umamo\.format\.(cmo3|moc3|uma)\b"""),
					reason = ":render knows no container format; :interop walks a document and hands over neutral page data",
				),
				Boundary(
					packages = Regex("""org\.umamo\.(?!editor\b).+"""),
					forbiddenImports = Regex("""^(javax\.swing|javafx|imgui)\b"""),
					reason = "the interface is Compose only, with no Swing, JavaFX, or ImGui",
				),
				Boundary(
					packages = Regex("""org\.umamo\..+"""),
					forbiddenImports = Regex("""^java\.lang\.foreign\b"""),
					reason = "Android has no FFM API, so native FFI goes behind one dedicated bridge module",
				),
			)
	}
}