package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
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
 * The parameter note crosses CMO3 both ways: the import reads each source's description verbatim, a fresh
 * conversion writes each note into its synthesized source, and an edit on a retained graph rewrites that one
 * description element and nothing else, so the decompressed main.xml differs from the file as read in exactly
 * one line.
 */
class Cmo3ParameterNoteTest {
	private val wheelId = ParameterId("ParamWheel")
	private val mouthId = ParameterId("ParamMouth")
	private val angleId = ParameterId("ParamAngleX")

	// Markup characters, so the writer's escaping is part of what crosses.
	private val wheelNote = "Turns the wheel <clockwise> & back"
	private val mouthNote = "口を開く"

	private val emptyDescription = """<s xs.n="description" />"""
	private val descriptionOpen = """<s xs.n="description">"""

	/**
	 * The two lines a note edit changed, and the CMO3 it wrote.
	 */
	private class NoteEdit(val before: String, val after: String, val written: ByteArray)

	private fun puppet(): PuppetModel =
		PuppetModel(
			parameters =
				listOf(
					Parameter(wheelId, "Wheel", 0f, 360f, 0f, note = wheelNote),
					Parameter(mouthId, "Mouth", 0f, 1f, 0f, note = mouthNote),
					Parameter(angleId, "Angle X", -30f, 30f, 0f),
				),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			parameterTree = listOf(ParameterNode.Param(wheelId), ParameterNode.Param(mouthId), ParameterNode.Param(angleId)),
			canvasWidth = 64f,
			canvasHeight = 64f,
			worldOriginX = 32f,
			worldOriginZ = -32f,
			runtimeTarget = RuntimeTarget.Cubism53,
		)

	private fun convertedBytes(): ByteArray {
		val converted = Cmo3Conversion.freshCmo3(puppet(), emptyList(), emptyMap(), "note", nowMillis = 0L, obfuscateKey = 0)

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

	/**
	 * Gives one parameter [note] on a fresh parse of [sourceBytes], asserting the export is lossless and changes
	 * exactly one line of main.xml.
	 *
	 * @param ByteArray   sourceBytes The CMO3 as read.
	 * @param ParameterId parameterId The parameter to edit.
	 * @param String      note        The note it is given.
	 * @return NoteEdit The changed line before and after, and the re-emitted CMO3.
	 */
	private fun assertNoteEditRewritesOneLine(sourceBytes: ByteArray, parameterId: ParameterId, note: String): NoteEdit {
		val cmo3 = Cmo3.read(sourceBytes)
		val imported = Cmo3Import.fromModelSource(assertIs<CModelSource>(cmo3.root))
		val edited = imported.copy(parameters = imported.parameters.map { parameter -> if (parameter.id == parameterId) parameter.copy(note = note) else parameter })
		val report = Cmo3Export.apply(edited, cmo3)
		assertTrue(report.isEmpty, "a note edit lowers fully: ${report.notices}")
		val written = Cmo3.write(cmo3)

		val residual = diffPuppetModels(importOf(written), edited)
		assertTrue(residual.isEmpty, "the note survives re-import: $residual")

		val before = mainXmlLines(sourceBytes)
		val after = mainXmlLines(written)
		assertEquals(before.size, after.size, "the re-emitted main.xml keeps its line count")
		val changed = before.indices.filter { lineIndex -> before[lineIndex] != after[lineIndex] }
		assertEquals(1, changed.size, "only the edited parameter's description changes: ${changed.map { lineIndex -> before[lineIndex] to after[lineIndex] }}")

		return NoteEdit(before[changed.single()], after[changed.single()], written)
	}

	@Test
	fun theImportReadsEachDescriptionVerbatim() {
		fun source(idStr: String, description: String?): CParameterSource =
			CParameterSource().apply {
				guid = Guid("CParameterGuid").apply { uuid = "uuid-$idStr" }
				id = Id("CParameterId").apply { idstr = idStr }
				this.description = description
			}
		val model =
			CModelSource().apply {
				parameterSourceSet =
					CParameterSourceSet().apply {
						// A note is user data, so even its edge whitespace is kept; an empty or absent one is no note.
						_sources = arrayListOf(source("ParamA", "  端の空白も残す  "), source("ParamB", ""), source("ParamC", null))
					}
			}

		assertEquals(listOf("  端の空白も残す  ", "", ""), Cmo3Import.fromModelSource(model).parameters.map { parameter -> parameter.note })
	}

	@Test
	fun aFreshConversionWritesEachParametersNote() {
		val noteById = importOf(convertedBytes()).parameters.associate { parameter -> parameter.id to parameter.note }

		assertEquals(mapOf(wheelId to wheelNote, mouthId to mouthNote, angleId to ""), noteById)
	}

	@Test
	fun settingANoteRewritesOnlyItsDescription() {
		val edit = assertNoteEditRewritesOneLine(convertedBytes(), angleId, "Tilts the head")

		assertEquals(emptyDescription, edit.before, "a parameter with no note was written in the empty form")
		assertEquals("""<s xs.n="description">Tilts the head</s>""", edit.after)
	}

	@Test
	fun clearingANoteWritesTheEmptyForm() {
		val edit = assertNoteEditRewritesOneLine(convertedBytes(), wheelId, "")

		assertTrue(edit.before.startsWith(descriptionOpen), "the cleared line held the note: ${edit.before}")
		assertEquals(emptyDescription, edit.after)
	}

	@Test
	fun editingAJapaneseNoteRewritesOnlyItsDescription() {
		val edit = assertNoteEditRewritesOneLine(convertedBytes(), mouthId, "口を閉じる")

		assertEquals("""<s xs.n="description">口を開く</s>""", edit.before)
		assertEquals("""<s xs.n="description">口を閉じる</s>""", edit.after)
	}

	/**
	 * The edit over an editor-written sample.  The first parameter with a note gets a new one; the export is kept
	 * for the shape gate and the official editor.
	 */
	@Test
	fun editingANoteInTheSampleRewritesOnlyItsDescription() {
		val sample = requireSample("cmo3.sample")
		val sourceBytes = sample.readBytes()
		val target = importOf(sourceBytes).parameters.firstOrNull { parameter -> parameter.note.isNotEmpty() } ?: skipTest("${sample.name} has no parameter with a note to edit")

		val edit = assertNoteEditRewritesOneLine(sourceBytes, target.id, "Edited note")
		assertTrue(edit.before.startsWith(descriptionOpen), "the edited line held the note: ${edit.before}")
		assertEquals("""<s xs.n="description">Edited note</s>""", edit.after)

		val exportFile = File("build/parameter-note/${sample.nameWithoutExtension}NoteExportTest.cmo3")
		exportFile.parentFile.mkdirs()
		exportFile.writeBytes(edit.written)
	}
}