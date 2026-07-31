package org.umamo.format.cmo3

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.serialize.ChildSlot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the targetVersionNo write path: the facade persists the field through a re-emit, including on
 * a document whose source XML carried no such element (where a bare field assignment is dropped by
 * the slot-replaying writer), and a same-value set stays byte-identical.  Skips without the sample.
 */
class Cmo3TargetVersionWriteTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun sameValueSetKeepsMainXmlByteIdentical() {
		val file =
			sample ?: run {
				println("cmo3.sample not present; skipping targetVersionNo write test")
				return
			}
		val model = Cmo3.read(file)
		val existing =
			model.targetVersionNo ?: run {
				println("sample carries no targetVersionNo; skipping byte-identity pin")
				return
			}
		model.setTargetVersionNo(existing)
		val originalMainXml = CaffCodec.read(file.readBytes()).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
		val rewrittenMainXml = CaffCodec.read(Cmo3.write(model)).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
		assertContentEquals(originalMainXml, rewrittenMainXml, "same-value set keeps main.xml byte-identical")
	}

	@Test
	fun changedValuePersistsThroughReemit() {
		val file = sample ?: return
		val model = Cmo3.read(file)
		model.setTargetVersionNo(Cmo3TargetVersion.V42.versionNo)
		assertEquals(Cmo3TargetVersion.V42.versionNo, Cmo3.read(Cmo3.write(model)).targetVersionNo)
	}

	@Test
	fun missingElementIsRecreatedWhereTheEditorWritesIt() {
		val file = sample ?: return
		val model = Cmo3.read(file)
		val modelSource = model.root as CModelSource
		// Simulate a source document that never carried the element: null the field and drop its
		// recorded slot, so the writer has nothing to replay.
		modelSource.targetVersionNo = null
		val slots = model.graph.childOrder[modelSource]!!.getValue("CModelSource")
		slots.removeAll { slot -> slot is ChildSlot.KnownField && slot.propertyName == "targetVersionNo" }
		// A bare field assignment is dropped - the failure mode the facade exists for.
		modelSource.targetVersionNo = Cmo3TargetVersion.V42.versionNo
		assertNull(Cmo3.read(Cmo3.write(model)).targetVersionNo, "a bare assignment has no slot to replay")
		// The facade records the slot too, so the value survives, ordered before the modeler version.
		model.setTargetVersionNo(Cmo3TargetVersion.V42.versionNo)
		val rewritten = Cmo3.write(model)
		assertEquals(Cmo3TargetVersion.V42.versionNo, Cmo3.read(rewritten).targetVersionNo)
		val mainXml = CaffCodec.read(rewritten).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content.decodeToString()
		val targetIndex = mainXml.indexOf("xs.n=\"targetVersionNo\"")
		val modelerIndex = mainXml.indexOf("xs.n=\"latestVersionOfLastModelerNo\"")
		assertTrue(targetIndex in 0 until modelerIndex, "recreated element sits before latestVersionOfLastModelerNo")
	}
}
