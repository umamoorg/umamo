package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.core.api.ElementType
import org.jetbrains.kotlin.com.intellij.lang.ASTNode

/**
 * Reads the package a file declares.
 *
 * @param ASTNode file The FILE node.
 * @return String The dotted package name, empty for a file that declares none.
 */
internal fun packageNameOf(file: ASTNode): String = file.findChildByType(ElementType.PACKAGE_DIRECTIVE)?.text.orEmpty().removePrefix("package").trim()

/**
 * Lists a file's import lines.
 *
 * @param ASTNode file The FILE node.
 * @return List<ASTNode> The IMPORT_DIRECTIVE nodes, in source order.
 */
internal fun importDirectivesOf(file: ASTNode): List<ASTNode> =
	file.findChildByType(ElementType.IMPORT_LIST)?.getChildren(null).orEmpty().filter { child -> child.elementType == ElementType.IMPORT_DIRECTIVE }

/**
 * Reads what an import line imports, without the alias it may be given.
 *
 * @param ASTNode directive An IMPORT_DIRECTIVE node.
 * @return String The dotted path, ending in `.*` for an on-demand import.
 */
internal fun importedPathOf(directive: ASTNode): String = directive.text.removePrefix("import").substringBefore(" as ").trim()

/**
 * Finds the name a file knows a class by: its own name, or the alias the file imports it under.
 *
 * @param ASTNode file The FILE node.
 * @param String qualifiedName The class's package and name.
 * @return String? The name, or null when the file neither imports the class nor shares its package.
 */
internal fun localNameOf(file: ASTNode, qualifiedName: String): String? {
	val packageName = qualifiedName.substringBeforeLast('.')
	val simpleName = qualifiedName.substringAfterLast('.')

	for (directive in importDirectivesOf(file)) {
		val importedPath = importedPathOf(directive)

		if (importedPath == qualifiedName) {
			return directive.text.substringAfter(" as ", simpleName).trim()
		}

		if (importedPath == "$packageName.*") {
			return simpleName
		}
	}

	return simpleName.takeIf { packageNameOf(file) == packageName }
}

/**
 * Tells whether a package is another package or lies under it.
 *
 * @param String packageName The package to test.
 * @param String ancestor The package it may be, or be under.
 * @return Boolean True for `a.b` and `a.b.c` against `a.b`, false for `a.bc`.
 */
internal fun isInPackage(packageName: String, ancestor: String): Boolean = packageName == ancestor || packageName.startsWith("$ancestor.")