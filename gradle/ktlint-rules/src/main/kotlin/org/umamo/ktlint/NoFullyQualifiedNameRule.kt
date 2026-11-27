package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.AutocorrectDecision
import com.pinterest.ktlint.rule.engine.core.api.ElementType
import com.pinterest.ktlint.rule.engine.core.api.Rule
import com.pinterest.ktlint.rule.engine.core.api.RuleAutocorrectApproveHandler
import com.pinterest.ktlint.rule.engine.core.api.RuleId
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Reports a class written out with its package where it is used, which an import should carry.
 *
 * `org.umamo.render.device.PipelineBlend` in a signature, an expression, an annotation, or the type word
 * of a docblock tag reads as noise beside the one name that matters.  The rule reports the package path
 * together with the first capitalized name after it, so it also catches a top-level constant
 * (`org.umamo.render.glsl.MAX_CORNERS`) and reports a nested name (`a.b.Outer.Inner`) once, at `Outer`.
 *
 * A dotted run only counts when it starts at a package: the root of the file's own package or of one of
 * its imports, or one of the usual roots (`org`, `com`, `java`, `kotlin`, and the like).  That keeps
 * an ordinary chain of property reads from ever looking like one.  The `package` and `import` lines
 * themselves, docblock links, and prose are not read.  A function called by its package
 * (`kotlin.math.abs(value)`) has no capitalized name to anchor on and is not reported.
 *
 * Nothing here is autocorrected.  An import changes what every other unqualified use of that name in
 * the file means, so it is a change to make while looking at the file.  Where two classes share a
 * name, import one under an alias; `@Suppress("ktlint:umamo:no-fully-qualified-name")` is there for a
 * spot where writing the path out really is the clearer choice.
 */
public class NoFullyQualifiedNameRule :
	Rule(
		ruleId = RuleId("$UMAMO_RULE_SET_ID:no-fully-qualified-name"),
		about = About(maintainer = "Umamo"),
	),
	RuleAutocorrectApproveHandler {
	private var packageRoots: Set<String> = COMMON_PACKAGE_ROOTS

	/**
	 * Reports a package-qualified name in an expression, a type, or a docblock tag.
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
				packageRoots = COMMON_PACKAGE_ROOTS + declaredRootsOf(node)
			}

			ElementType.DOT_QUALIFIED_EXPRESSION -> {
				checkExpression(node, emit)
			}

			ElementType.USER_TYPE -> {
				checkType(node, emit)
			}

			ElementType.KDOC -> {
				checkTagTypes(node, emit)
			}
		}
	}

	/**
	 * Reports a qualified expression whose receiver is a package and whose name is capitalized.
	 *
	 * @param ASTNode expression A DOT_QUALIFIED_EXPRESSION node.
	 * @param Function3 emit Reports a violation.
	 */
	private fun checkExpression(expression: ASTNode, emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision) {
		if (isInsideDirective(expression)) {
			return
		}

		// The selector is a name, or a call on one: `a.b.Name` or `a.b.Name(…)`.
		val selector = expression.lastChildNode ?: return
		val nameNode = if (selector.elementType == ElementType.CALL_EXPRESSION) selector.firstChildNode else selector

		if (nameNode?.elementType != ElementType.REFERENCE_EXPRESSION) {
			return
		}

		val packagePath = expression.firstChildNode?.let { receiver -> expressionPackagePath(receiver) } ?: return
		report(expression, packagePath, nameNode.text, emit)
	}

	/**
	 * Reports a qualified type whose qualifier is a package and whose name is capitalized.
	 *
	 * @param ASTNode type A USER_TYPE node.
	 * @param Function3 emit Reports a violation.
	 */
	private fun checkType(type: ASTNode, emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision) {
		val qualifier = type.findChildByType(ElementType.USER_TYPE) ?: return
		val name = type.findChildByType(ElementType.REFERENCE_EXPRESSION)?.text ?: return
		val packagePath = typePackagePath(qualifier) ?: return

		report(type, packagePath, name, emit)
	}

	/**
	 * Reports a package path and name when the name is capitalized and the path starts at a known root.
	 *
	 * @param ASTNode node The qualified expression or type.
	 * @param String packagePath The lowercase dotted path before the name.
	 * @param String name The name after it.
	 * @param Function3 emit Reports a violation.
	 */
	private fun report(
		node: ASTNode,
		packagePath: String,
		name: String,
		emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision,
	) {
		if (name.firstOrNull()?.isUpperCase() == true && packagePath.substringBefore('.') in packageRoots) {
			emit(node.startOffset, message("$packagePath.$name", name), false)
		}
	}

	/**
	 * Reads an expression as a package path: plain lowercase names joined by dots.
	 *
	 * @param ASTNode expression The receiver of a qualified expression.
	 * @return String? The dotted path, or null when the expression is anything else.
	 */
	private fun expressionPackagePath(expression: ASTNode): String? =
		when (expression.elementType) {
			ElementType.REFERENCE_EXPRESSION -> {
				expression.text.takeIf { segment -> PACKAGE_SEGMENT.matches(segment) }
			}

			ElementType.DOT_QUALIFIED_EXPRESSION -> {
				val selector = expression.lastChildNode
				val leading = expression.firstChildNode?.let { receiver -> expressionPackagePath(receiver) }

				if (leading != null && selector?.elementType == ElementType.REFERENCE_EXPRESSION && PACKAGE_SEGMENT.matches(selector.text)) {
					"$leading.${selector.text}"
				} else {
					null
				}
			}

			else -> {
				null
			}
		}

	/**
	 * Reads a type qualifier as a package path: plain lowercase names joined by dots.
	 *
	 * @param ASTNode qualifier The USER_TYPE that qualifies another.
	 * @return String? The dotted path, or null when a segment is capitalized or carries type arguments.
	 */
	private fun typePackagePath(qualifier: ASTNode): String? {
		val name = qualifier.findChildByType(ElementType.REFERENCE_EXPRESSION)?.text ?: return null

		if (!PACKAGE_SEGMENT.matches(name) || qualifier.findChildByType(ElementType.TYPE_ARGUMENT_LIST) != null) {
			return null
		}

		val outer = qualifier.findChildByType(ElementType.USER_TYPE) ?: return name

		return typePackagePath(outer)?.let { leading -> "$leading.$name" }
	}

	/**
	 * Tells whether a node is part of the file's `package` line or of an `import` line.
	 *
	 * @param ASTNode node A node of the file.
	 * @return Boolean True inside either directive.
	 */
	private fun isInsideDirective(node: ASTNode): Boolean {
		var current: ASTNode? = node.treeParent

		while (current != null) {
			if (current.elementType == ElementType.IMPORT_DIRECTIVE || current.elementType == ElementType.PACKAGE_DIRECTIVE) {
				return true
			}

			current = current.treeParent
		}

		return false
	}

	/**
	 * Reports the type word of a docblock tag when it is written with its package.
	 *
	 * @param ASTNode docblock The KDOC node.
	 * @param Function3 emit Reports a violation.
	 */
	private fun checkTagTypes(docblock: ASTNode, emit: (offset: Int, errorMessage: String, canBeAutoCorrected: Boolean) -> AutocorrectDecision) {
		for (tag in TAG_TYPE.findAll(docblock.text)) {
			val qualifiedName = tag.groups[1] ?: continue

			if (qualifiedName.value.substringBefore('.') in packageRoots) {
				emit(docblock.startOffset + qualifiedName.range.first, message(qualifiedName.value, qualifiedName.value.substringAfterLast('.')), false)
			}
		}
	}

	/**
	 * Words the violation for one qualified name.
	 *
	 * @param String qualifiedName The name as written, package included.
	 * @param String name The name alone.
	 * @return String The message.
	 */
	private fun message(qualifiedName: String, name: String): String = "`$qualifiedName` is written out in full; import it and write `$name`"

	/**
	 * Reads the package roots a file itself names: the first segment of its package and of each import.
	 *
	 * @param ASTNode file The FILE node.
	 * @return Set<String> The roots, empty for a file with neither.
	 */
	private fun declaredRootsOf(file: ASTNode): Set<String> {
		val directives = listOfNotNull(file.findChildByType(ElementType.PACKAGE_DIRECTIVE)) + file.findChildByType(ElementType.IMPORT_LIST)?.getChildren(null).orEmpty().toList()

		return directives.mapNotNull { directive -> DIRECTIVE_ROOT.find(directive.text)?.groupValues?.get(1) }.toSet()
	}

	private companion object {
		/** Roots that open a package almost wherever they appear, for a file that imports nothing from them. */
		val COMMON_PACKAGE_ROOTS: Set<String> = setOf("org", "com", "java", "javax", "kotlin", "kotlinx", "android", "androidx", "okio")

		/** One segment of a package path. */
		val PACKAGE_SEGMENT = Regex("""[a-z_][a-z0-9_]*""")

		/** The first segment of a `package` or `import` line; group 1 is that segment. */
		val DIRECTIVE_ROOT = Regex("""^(?:package|import)\s+([a-z_][a-z0-9_]*)\.""")

		/** A docblock tag whose type word carries a package; group 1 is the qualified name up to its first capitalized part. */
		val TAG_TYPE = Regex("""@(?:param|return|property|throws|receiver)[ \t]+((?:[a-z_][a-z0-9_]*\.)+[A-Z]\w*)""")
	}
}