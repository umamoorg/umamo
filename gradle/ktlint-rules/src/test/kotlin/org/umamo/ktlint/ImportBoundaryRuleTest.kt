package org.umamo.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.core.api.RuleProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives ImportBoundaryRule through the real ktlint engine, alone, one boundary of its table at a time.
 */
class ImportBoundaryRuleTest {
	private val engine = KtLintRuleEngine(ruleProviders = setOf(RuleProvider { ImportBoundaryRule() }))

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
	fun theTrackSheetSeesTheKitAndTheThemeAndNoDomain() {
		val code =
			"""
			package org.umamo.ui.tracks

			import androidx.compose.runtime.Composable
			import org.umamo.edit.EditorSession
			import org.umamo.runtime.model.*
			import org.umamo.ui.kit.menu.PieMenu
			import org.umamo.ui.theme.EditorColors
			import org.umamo.ui.tracks.TrackAxis
			import org.umamo.ui.workspace.spaces.keyform.KeyformSheet
			""".trimIndent()

		assertEquals(listOf("4:1", "5:1", "9:1"), reported(code))
	}

	@Test
	fun aPackageUnderTheTrackSheetIsBoundTheSame() {
		val code =
			"""
			package org.umamo.ui.tracks.ruler

			import org.umamo.runtime.model.ParameterId
			""".trimIndent()

		assertEquals(listOf("3:1"), reported(code))
	}

	@Test
	fun theEditRootImportsNoneOfItsSubpackages() {
		val root =
			"""
			package org.umamo.edit

			import org.umamo.edit.mesh.withMeshUvs
			import org.umamo.runtime.model.PuppetModel
			""".trimIndent()
		val subpackage =
			"""
			package org.umamo.edit.mesh

			import org.umamo.edit.EditorSession
			import org.umamo.edit.transform.ModalTransformCapture
			""".trimIndent()

		assertEquals(listOf("3:1"), reported(root))
		assertEquals(emptyList(), reported(subpackage))
	}

	@Test
	fun theRendererImportsNoContainerFormat() {
		val code =
			"""
			package org.umamo.render.eval

			import org.umamo.format.cmo3.Cmo3
			import org.umamo.format.moc3.Moc3
			import org.umamo.format.raster.RasterImage
			import org.umamo.format.uma.UmaDocument
			""".trimIndent()

		assertEquals(listOf("3:1", "4:1", "6:1"), reported(code))
	}

	@Test
	fun aLibraryImportsNoOtherInterfaceToolkit() {
		val library =
			"""
			package org.umamo.ui.kit

			import javax.swing.JFrame
			import javafx.scene.Scene
			import java.awt.Toolkit
			""".trimIndent()
		val application =
			"""
			package org.umamo.editor.desktop

			import javax.swing.JFrame
			""".trimIndent()

		assertEquals(listOf("3:1", "4:1"), reported(library))
		assertEquals(emptyList(), reported(application))
	}

	@Test
	fun noUmamoPackageImportsTheForeignFunctionApi() {
		val code =
			"""
			package org.umamo.editor.desktop

			import java.lang.foreign.Arena
			import java.lang.invoke.MethodHandle
			""".trimIndent()

		assertEquals(listOf("3:1"), reported(code))
	}

	@Test
	fun aPackageNoBoundaryBindsIsLeftAlone() {
		val code =
			"""
			package org.example.tool

			import javax.swing.JFrame
			import org.umamo.format.cmo3.Cmo3
			""".trimIndent()

		assertEquals(emptyList(), reported(code))
	}
}