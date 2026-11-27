package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives NoFullyQualifiedNameRule through the real ktlint engine, alone, so each case shows exactly which
 * dotted names it reads as a class written out with its package.
 */
class NoFullyQualifiedNameRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { NoFullyQualifiedNameRule() }))

	/**
	 * Lints a snippet with the rule alone.
	 *
	 * @param String code Kotlin source.
	 * @param Boolean script Whether the snippet is a Kotlin script.
	 * @return List<LintError> Every violation the rule reports.
	 */
	private fun lint(code: String, script: Boolean = false): List<LintError> {
		val errors = ArrayList<LintError>()
		engine.lint(Code.fromSnippet(code, script)) { error -> errors.add(error) }

		return errors
	}

	/**
	 * Lints a snippet and reduces each violation to where it is and which name it reports.
	 *
	 * @param String code Kotlin source.
	 * @param Boolean script Whether the snippet is a Kotlin script.
	 * @return List<String> One "line:column qualified name" per violation.
	 */
	private fun reported(code: String, script: Boolean = false): List<String> =
		lint(code, script).map { error -> "${error.line}:${error.col} ${error.detail.substringAfter('`').substringBefore('`')}" }

	@Test
	fun reportsAQualifiedTypeInASignature() {
		val code =
			"""
			package org.umamo.render.gl

			private fun applyBlend(blend: org.umamo.render.device.PipelineBlend): org.umamo.render.device.GpuTexture? {
				val modes: List<org.umamo.render.device.PipelineBlend> = listOf(blend)

				return null
			}
			""".trimIndent()

		assertEquals(
			listOf(
				"3:31 org.umamo.render.device.PipelineBlend",
				"3:71 org.umamo.render.device.GpuTexture",
				"4:18 org.umamo.render.device.PipelineBlend",
			),
			reported(code),
		)
	}

	@Test
	fun reportsAQualifiedNameInAnExpression() {
		val code =
			"""
			package org.umamo.render.gl

			fun describe(blend: Any): String {
				val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
				val limit = org.umamo.render.glsl.MAX_CORNERS
				val reference = org.umamo.render.device.PipelineBlend::class

				return when (blend) {
					org.umamo.render.device.PipelineBlend.Opaque -> "opaque"
					is org.umamo.render.device.PipelineBlend.Normal -> "normal"
					else -> "${'$'}{org.umamo.render.device.PipelineBlend.Additive}"
				}
			}
			""".trimIndent()

		assertEquals(
			listOf(
				"4:13 java.util.Collections",
				"4:49 java.util.IdentityHashMap",
				"5:14 org.umamo.render.glsl.MAX_CORNERS",
				"6:18 org.umamo.render.device.PipelineBlend",
				"9:3 org.umamo.render.device.PipelineBlend",
				"10:6 org.umamo.render.device.PipelineBlend",
				"11:14 org.umamo.render.device.PipelineBlend",
			),
			reported(code),
		)
	}

	@Test
	fun reportsAQualifiedAnnotationAndSupertype() {
		val code =
			"""
			@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
			class Probe : java.io.Closeable {
				@kotlin.jvm.JvmField
				val size = 0

				override fun close() {
				}
			}
			""".trimIndent()

		assertEquals(
			listOf(
				"1:8 kotlinx.coroutines.ExperimentalCoroutinesApi",
				"2:15 java.io.Closeable",
				"3:3 kotlin.jvm.JvmField",
			),
			reported(code),
		)
	}

	@Test
	fun reportsANestedNameOnceAtItsOuterClass() {
		val code =
			"""
			fun kind(deformer: org.umamo.runtime.model.Deformer.Warp): org.umamo.runtime.model.Deformer.Warp = deformer
			""".trimIndent()

		assertEquals(
			listOf(
				"1:20 org.umamo.runtime.model.Deformer",
				"1:60 org.umamo.runtime.model.Deformer",
			),
			reported(code),
		)
	}

	@Test
	fun reportsTheTypeWordOfADocblockTag() {
		val code =
			"""
			import org.umamo.render.device.PipelineBlend

			/**
			 * Sets the blend state.  See [org.umamo.render.device.GpuTexture] and org.umamo.render.device.RenderDevice.
			 *
			 * @param org.umamo.render.device.PipelineBlend blend The blend mode.
			 * @return org.umamo.render.device.GpuTexture? The texture.
			 * @throws kotlinx.serialization.SerializationException When the state is malformed.
			 */
			fun applyBlend(blend: PipelineBlend): Any? = null
			""".trimIndent()

		assertEquals(
			listOf(
				"6:11 org.umamo.render.device.PipelineBlend",
				"7:12 org.umamo.render.device.GpuTexture",
				"8:12 kotlinx.serialization.SerializationException",
			),
			reported(code),
		)
	}

	@Test
	fun leavesThePackageAndImportLinesAlone() {
		val code =
			"""
			package org.umamo.render.gl

			import org.umamo.render.device.PipelineBlend
			import org.umamo.render.glsl.MAX_CORNERS as CORNER_LIMIT
			import java.util.Collections

			val blend: PipelineBlend? = null
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun leavesOrdinaryDottedChainsAlone() {
		val code =
			"""
			package org.umamo.render.gl

			class Pipeline(val settings: Settings, val model: Model) {
				fun describe(): String {
					val limit = settings.viewport.MAX_ZOOM
					val name = model.root.Name
					val nested = Outer.Inner.Leaf
					val enumValue = Settings.Mode.Fast
					val javaClass = this::class.java.superclass
					val built = kotlin.math.abs(-1)

					return "${'$'}limit ${'$'}name ${'$'}nested ${'$'}enumValue ${'$'}javaClass ${'$'}built"
				}
			}
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}

	@Test
	fun readsTheRootsAFileNamesItself() {
		val withImport =
			"""
			import app.cash.sqldelight.db.SqlDriver

			val driver: app.cash.sqldelight.db.SqlDriver? = null
			val schema = app.cash.sqldelight.db.SqlSchema::class
			""".trimIndent()
		val withoutImport =
			"""
			val driver = app.cash.sqldelight.db.SqlDriver
			""".trimIndent()

		assertEquals(listOf("3:13 app.cash.sqldelight.db.SqlDriver", "4:14 app.cash.sqldelight.db.SqlSchema"), reported(withImport))
		assertEquals(emptyList(), reported(withoutImport))
	}

	@Test
	fun reportsAQualifiedNameInAScript() {
		val code =
			"""
			tasks.test {
				testLogging {
					exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
				}
			}
			""".trimIndent()

		assertEquals(listOf("3:21 org.gradle.api.tasks.testing.logging.TestExceptionFormat"), reported(code, script = true))
	}

	@Test
	fun honorsASuppression() {
		val code =
			"""
			@Suppress("ktlint:umamo:no-fully-qualified-name")
			val first: org.umamo.format.cmo3.model.drawable.CoordType? = null

			val second: org.umamo.format.cmo3.model.drawable.CoordType? = null
			""".trimIndent()

		assertEquals(listOf("4:13 org.umamo.format.cmo3.model.drawable.CoordType"), reported(code))
	}

	@Test
	fun reportsEveryViolationAsNotCorrectableWithTheNameToWrite() {
		val errors = lint("val blend: org.umamo.render.device.PipelineBlend? = null")

		assertEquals(1, errors.size)
		assertEquals("`org.umamo.render.device.PipelineBlend` is written out in full; import it and write `PipelineBlend`", errors[0].detail)
		assertTrue(errors.none { error -> error.canBeAutoCorrected })
		assertTrue(errors.all { error -> error.ruleId.value == "umamo:no-fully-qualified-name" })
	}
}