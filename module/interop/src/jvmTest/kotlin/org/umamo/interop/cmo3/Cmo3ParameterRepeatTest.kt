package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.export.Cmo3Conversion
import org.umamo.interop.cmo3.export.Cmo3Export
import org.umamo.interop.cmo3.import.Cmo3Import
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.testkit.requireSample
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The repeat flag crosses CMO3 both ways: a fresh conversion writes each parameter's flag into its synthesized
 * source, and a toggle on a retained graph rewrites that one `isRepeat` element and nothing else, so the
 * decompressed main.xml differs from the file as read in exactly one line.
 */
class Cmo3ParameterRepeatTest {
	private val wheelId = ParameterId("ParamWheel")
	private val angleId = ParameterId("ParamAngleX")

	private val repeatOff = """<b xs.n="isRepeat">false</b>"""
	private val repeatOn = """<b xs.n="isRepeat">true</b>"""

	private fun puppet(): PuppetModel =
		PuppetModel(
			parameters =
				listOf(
					Parameter(wheelId, "Wheel", 0f, 360f, 0f, repeat = true),
					Parameter(angleId, "Angle X", -30f, 30f, 0f),
				),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			parameterTree = listOf(ParameterNode.Param(wheelId), ParameterNode.Param(angleId)),
			canvasWidth = 64f,
			canvasHeight = 64f,
			worldOriginX = 32f,
			worldOriginZ = -32f,
			runtimeTarget = RuntimeTarget.Cubism53,
		)

	private fun convertedBytes(): ByteArray {
		val converted = Cmo3Conversion.freshCmo3(puppet(), emptyList(), emptyMap(), "repeat", nowMillis = 0L, obfuscateKey = 0)

		return Cmo3.write(converted.model)
	}

	private fun mainXmlLines(cmo3Bytes: ByteArray): List<String> =
		CaffCodec
			.read(cmo3Bytes)
			.firstByTag(CaffArchive.TAG_MAIN_XML)!!
			.content
			.decodeToString()
			.lines()

	private fun importOf(cmo3Bytes: ByteArray): PuppetModel = Cmo3Import.fromModelSource(assertIs<CModelSource>(Cmo3.read(cmo3Bytes).root))

	private fun withRepeat(puppet: PuppetModel, parameterId: ParameterId, repeat: Boolean): PuppetModel =
		puppet.copy(parameters = puppet.parameters.map { parameter -> if (parameter.id == parameterId) parameter.copy(repeat = repeat) else parameter })

	/**
	 * Applies one parameter's repeat toggle to a fresh parse of [sourceBytes], asserting the export is lossless
	 * and changes only that parameter's `isRepeat` line.
	 *
	 * @param ByteArray   sourceBytes The CMO3 as read.
	 * @param ParameterId parameterId The parameter to toggle.
	 * @param Boolean     repeat      The flag it is given.
	 * @return ByteArray The re-emitted CMO3.
	 */
	private fun assertTogglesOnlyItsFlag(sourceBytes: ByteArray, parameterId: ParameterId, repeat: Boolean): ByteArray {
		val cmo3 = Cmo3.read(sourceBytes)
		val edited = withRepeat(Cmo3Import.fromModelSource(assertIs<CModelSource>(cmo3.root)), parameterId, repeat)
		val report = Cmo3Export.apply(edited, cmo3)
		assertTrue(report.isEmpty, "a repeat toggle lowers fully: ${report.notices}")
		val written = Cmo3.write(cmo3)

		val residual = diffPuppetModels(importOf(written), edited)
		assertTrue(residual.isEmpty, "the toggle survives re-import: $residual")

		val before = mainXmlLines(sourceBytes)
		val after = mainXmlLines(written)
		assertEquals(before.size, after.size, "the re-emitted main.xml keeps its line count")
		val changed = before.indices.filter { lineIndex -> before[lineIndex] != after[lineIndex] }.map { lineIndex -> before[lineIndex] to after[lineIndex] }
		val expected = if (repeat) repeatOff to repeatOn else repeatOn to repeatOff
		assertEquals(listOf(expected), changed, "only the toggled parameter's isRepeat changes")

		return written
	}

	@Test
	fun aFreshConversionWritesEachParametersRepeatFlag() {
		val written = convertedBytes()

		val repeatById = importOf(written).parameters.associate { parameter -> parameter.id to parameter.repeat }
		assertEquals(mapOf(wheelId to true, angleId to false), repeatById)
		assertEquals(1, mainXmlLines(written).count { line -> line.trim() == repeatOn }, "one source repeats")
	}

	@Test
	fun turningRepeatOnRewritesOnlyThatFlag() {
		assertTogglesOnlyItsFlag(convertedBytes(), angleId, repeat = true)
	}

	@Test
	fun turningRepeatOffRewritesOnlyThatFlag() {
		assertTogglesOnlyItsFlag(convertedBytes(), wheelId, repeat = false)
	}

	/**
	 * The toggle over an editor-written sample.  The first parameter that does not repeat is turned on, so any
	 * flag the sample already sets stays on; the export is kept for the shape gate and the official editor.
	 */
	@Test
	fun turningRepeatOnInTheSampleRewritesOnlyThatFlag() {
		val sample = requireSample("cmo3.sample")
		val sourceBytes = sample.readBytes()
		val target = importOf(sourceBytes).parameters.firstOrNull { parameter -> !parameter.repeat } ?: skipTest("${sample.name} has no parameter left to turn repeat on")

		val written = assertTogglesOnlyItsFlag(sourceBytes, target.id, repeat = true)

		val exportFile = File("build/parameter-repeat/${sample.nameWithoutExtension}RepeatExportTest.cmo3")
		exportFile.parentFile.mkdirs()
		exportFile.writeBytes(written)
	}
}