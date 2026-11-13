package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives SessionExtensionPlacementRule through the real ktlint engine, alone, so each case shows which
 * functions it reads as editing logic declared in the wrong package.
 */
class SessionExtensionPlacementRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { SessionExtensionPlacementRule() }))

	/**
	 * Lints a snippet with the rule alone and reduces each violation to where it is.
	 *
	 * @param String code Kotlin source.
	 * @return List<String> One "line:column" per violation.
	 */
	private fun reported(code: String): List<String> {
		val positions = ArrayList<String>()
		engine.lint(Code.fromSnippet(code)) { error -> positions.add("${error.line}:${error.col}") }

		return positions
	}

	@Test
	fun reportsAnExtensionOnTheSessionInAnInterfacePackage() {
		val code =
			"""
			package org.umamo.ui.viewport.viewport2d

			import org.umamo.edit.EditorSession

			internal fun EditorSession.commitDrive(areaId: String) {
				commitStep(areaId)
			}

			private fun <T> EditorSession?.readOr(fallback: T): T = fallback
			""".trimIndent()

		assertEquals(listOf("5:14", "9:17"), reported(code))
	}

	@Test
	fun leavesTheEditModuleAndTheTransformPackageAlone() {
		val editSubpackage =
			"""
			package org.umamo.edit.mesh

			import org.umamo.edit.EditorSession

			fun EditorSession.commitMeshPositions() {
			}
			""".trimIndent()
		val editRoot =
			"""
			package org.umamo.edit

			fun EditorSession.clearSelection() {
			}
			""".trimIndent()
		val transform =
			"""
			package org.umamo.ui.transform.lattice

			import org.umamo.edit.EditorSession

			internal fun EditorSession.commitLatticeLanding() {
			}
			""".trimIndent()

		assertEquals(emptyList(), reported(editSubpackage))
		assertEquals(emptyList(), reported(editRoot))
		assertEquals(emptyList(), reported(transform))
	}

	@Test
	fun followsAnAlias() {
		val code =
			"""
			package org.umamo.ui.properties

			import org.umamo.edit.EditorSession as Session

			internal fun Session.setWorldCenter(centerX: Float) {
			}
			""".trimIndent()

		assertEquals(listOf("5:14"), reported(code))
	}

	@Test
	fun leavesAFunctionThatOnlyTakesTheSessionAlone() {
		val code =
			"""
			package org.umamo.ui.viewport

			import org.umamo.edit.EditorSession

			internal fun ownsPointer(session: EditorSession, areaId: String): Boolean = session.activeAreaId == areaId

			internal fun String.belongsTo(session: EditorSession): Boolean = session.activeAreaId == this

			internal val EditorSession.hasGesture: Boolean
				get() = activeAreaId != null
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun leavesAnotherClassOfTheSameNameAlone() {
		val code =
			"""
			package org.umamo.cli

			class EditorSession

			fun EditorSession.describe(): String = "none"
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}
}