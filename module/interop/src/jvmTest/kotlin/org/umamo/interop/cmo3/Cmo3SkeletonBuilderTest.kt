package org.umamo.interop.cmo3

import org.jdom.Element
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Author
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.xml.XmlCodec
import org.umamo.interop.cmo3TargetVersionNo
import org.umamo.runtime.model.RuntimeTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gates the blank-skeleton builder (M2 of the fresh-graph synthesis): a built blank assembles
 * into a .cmo3 that reads back through the normal codec and imports as an empty puppet with the
 * right document fields, and its CModelSource child sequence matches BareMinimum.cmo3 (the
 * official editor's own New -> Save As) minus the three 5.x-optional tail elements our model
 * classes deliberately omit on fresh emission.
 */
class Cmo3SkeletonBuilderTest {
	@Test
	fun blankSkeletonReadsBackAsAnEmptyPuppet() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Untitled Model", 1000, 2000, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		val bytes =
			Cmo3FreshFile.assemble(
				skeleton.root,
				skeleton.iconEntries.map { icon -> Cmo3FreshFile.PngEntry(icon.path, icon.pngBytes) },
				obfuscateKey = 0x49C74776.toInt(),
			)
		// The assembled blank doubles as the manual official-editor gate's input: open
		// build/fresh-blank.cmo3 in the Cubism Editor to validate from-scratch acceptance.
		File("build/fresh-blank.cmo3").writeBytes(bytes)

		val model = Cmo3.read(bytes)
		val modelSource = model.root as? CModelSource ?: error("fresh blank did not read back as CModelSource")
		assertEquals("Untitled Model", modelSource.name, "model name survives")
		assertEquals(4, model.archive.entries.size, "three icon PNGs + main.xml")
		assertEquals(CaffArchive.TAG_MAIN_XML, model.archive.entries.last().tag, "main.xml is the last entry")

		val puppet = Cmo3Import.fromModelSource(modelSource)
		assertEquals(1000f, puppet.canvasWidth, "canvas width")
		assertEquals(2000f, puppet.canvasHeight, "canvas height")
		assertEquals(RuntimeTarget.Cubism53, puppet.runtimeTarget, "runtime target round-trips")
		assertTrue(puppet.parameters.isEmpty(), "no parameters in a blank")
		assertTrue(puppet.parts.isEmpty(), "no user parts in a blank (the root part is synthetic)")
		assertTrue(puppet.deformers.isEmpty(), "no deformers in a blank")
		assertTrue(puppet.drawables.isEmpty(), "no drawables in a blank")
		assertTrue(puppet.glues.isEmpty(), "no glues in a blank")
	}

	@Test
	fun blankSpineMatchesBareMinimumChildSequence() {
		val bareMinimum =
			System.getProperty("cmo3.probe")
				?.split(',')
				?.map { path -> File(path.trim()) }
				?.firstOrNull { file -> file.name == "BareMinimum.cmo3" && file.isFile }
				?: run {
					println("BareMinimum.cmo3 not in cmo3.probe; skipping spine comparison")
					return
				}
		val blankArchive = CaffCodec.read(bareMinimum.readBytes())
		val blankXml = blankArchive.firstByTag(CaffArchive.TAG_MAIN_XML) ?: error("BareMinimum has no main_xml")
		val editorSpine = modelSourceChildren(XmlCodec.parse(blankXml.content).rootElement)

		val skeleton = Cmo3SkeletonBuilder.buildBlank("Untitled Model", 1000, 2000, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		val freshSpine = modelSourceChildren(XmlCodec.parse(Cmo3Author.writeFreshMainXml(skeleton.root)).rootElement)

		// Our model classes omit the 5.x-optional tails on fresh emission (older-era files omit
		// them entirely, so the editor's reader accepts their absence).
		val omittedTails = setOf("randomPoseSetting", "motionSyncSettingsSet", "modelStateSetSet")
		val expectedSpine = editorSpine.filterNot { (_, fieldName) -> fieldName in omittedTails }
		assertEquals(expectedSpine, freshSpine, "CModelSource child (tag, xs.n) sequence matches the editor's blank")
	}

	/**
	 * The (tag, xs.n) sequence of the document's CModelSource children.
	 *
	 * @param Element rootElement The parsed root element.
	 * @return List The child descriptors in document order.
	 */
	private fun modelSourceChildren(rootElement: Element): List<Pair<String, String?>> {
		val mainElement = rootElement.getChild("main") ?: error("no <main> element")
		val modelSource = mainElement.children.filterIsInstance<Element>().firstOrNull() ?: error("no model root element")
		return modelSource.children.filterIsInstance<Element>().map { child -> child.name to child.getAttributeValue("xs.n") }
	}
}
