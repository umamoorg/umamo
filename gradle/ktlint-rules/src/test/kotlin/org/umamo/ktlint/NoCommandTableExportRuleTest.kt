package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives NoCommandTableExportRule through the real ktlint engine, alone, so each case shows which
 * declarations it reads as a command table handing its data to whatever imports it.
 */
class NoCommandTableExportRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { NoCommandTableExportRule() }))

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
	fun reportsAPropertyATableLeavesVisible() {
		val code =
			"""
			package org.umamo.ui.workspace.commands

			internal val SELECTABLE_KINDS = listOf("parts", "drawables")
			val MERGE_TARGETS = listOf("center", "first", "last")
			internal const val FALLOFF_COUNT = 8
			var lastPicked: String? = null
			""".trimIndent()

		assertEquals(listOf("3:14", "4:5", "5:20", "6:5"), reported(code))
	}

	@Test
	fun leavesATablesPrivateDataAlone() {
		val code =
			"""
			package org.umamo.ui.workspace.commands

			private val SELECTABLE_KINDS = listOf("parts", "drawables")
			private const val FALLOFF_COUNT = 8

			@Suppress("unused")
			private var lastPicked: String? = null
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun leavesATablesFunctionsAndTypesAlone() {
		val code =
			"""
			package org.umamo.ui.workspace.commands

			internal fun selectCommands(): List<String> {
				val kinds = listOf("parts", "drawables")

				return kinds.map { kind -> "select.all." + kind }
			}

			internal class RelinkRequest(val tileIds: List<String>)

			internal object Routing {
				val fallback: String? = null
			}
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun pointsAtTheNameUnderADocblockAndAnAnnotation() {
		val code =
			"""
			package org.umamo.ui.workspace.commands

			/** The by-kind pairs, in menu order. */
			@Suppress("unused")
			internal val SELECTABLE_KINDS = listOf("parts", "drawables")
			""".trimIndent()

		assertEquals(listOf("5:14"), reported(code))
	}

	@Test
	fun reportsAnExtensionPropertyByItsOwnName() {
		val code =
			"""
			package org.umamo.ui.workspace.commands

			internal val String.kindLabel: String get() = substringAfterLast('.')
			""".trimIndent()

		assertEquals(listOf("3:21"), reported(code))
	}

	@Test
	fun readsAPackageUnderTheTablesAsOneOfThem() {
		val code =
			"""
			package org.umamo.ui.workspace.commands.snap

			internal val SNAP_TARGETS = listOf("grid", "cursor")
			""".trimIndent()

		assertEquals(listOf("3:14"), reported(code))
	}

	@Test
	fun leavesEveryOtherPackageAlone() {
		val widget =
			"""
			package org.umamo.ui.workspace.spaces

			internal val HEADER_PANEL_ROWS = listOf("pivot", "snap")
			""".trimIndent()
		val neighbor =
			"""
			package org.umamo.ui.workspace.commandsheet

			internal val SHEET_ROWS = listOf("insert", "delete")
			""".trimIndent()

		assertEquals(emptyList(), reported(widget))
		assertEquals(emptyList(), reported(neighbor), "a package that only starts with the same letters is not under the tables")
	}
}