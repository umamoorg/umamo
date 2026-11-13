package org.umamo.format.cmo3

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * End-to-end facade test: read a real .cmo3 into a typed model, inspect/replace layer images, and
 * write it back losslessly.  Self-contained (no editor jar).  Skips without the sample.
 */
class Cmo3FacadeTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }
	private val pngMagic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

	private fun ByteArray.isPng() = size >= 4 && pngMagic.indices.all { this[it] == pngMagic[it] }

	@Test
	fun readsModelExposesLayersAndReemitsMainXmlByteIdentical() {
		val file = sample ?: skipTest("cmo3.sample not present; skipping facade test")
		val model = Cmo3.read(file)

		// Typed root + layer resources.
		assertNotNull(model.root, "model root")
		assertEquals("CModelSource", model.root!!::class.simpleName)
		val resources = model.imageResources()
		assertEquals(180, resources.size, "layer image resources")

		for (resource in resources) {
			val png = model.extractLayerPng(resource)
			assertNotNull(png, "layer png for ${resource.imageFileBuf?.archivePath}")
			assertTrue(png.isPng(), "valid PNG")
			assertEquals(png.size, resource.imageFileBuf_size, "size attribute matches bytes")
		}

		// Write back: the model's main.xml must round-trip byte-identical (re-read via our own codec).
		val originalMainXml = CaffCodec.read(file.readBytes()).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
		val rewritten = Cmo3.write(model)
		val rewrittenMainXml = CaffCodec.read(rewritten).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
		assertContentEquals(originalMainXml, rewrittenMainXml, "main.xml round-trips byte-identical via facade")
	}

	@Test
	fun replaceLayerPngPersists() {
		val file = sample ?: skipTest("cmo3.sample not present; skipping facade test")
		val model = Cmo3.read(file)
		val resources = model.imageResources()
		val target = resources.first()
		val donorPng = model.extractLayerPng(resources[1])!! // reuse another layer's PNG as the new pixels

		model.replaceLayerPng(target, donorPng)
		val reread = Cmo3.read(Cmo3.write(model))
		val updated = reread.imageResources().first()
		assertContentEquals(donorPng, reread.extractLayerPng(updated), "replaced PNG persisted")
		assertEquals(donorPng.size, updated.imageFileBuf_size, "size attribute updated")
	}

	/**
	 * Reading a model's archive again yields a private graph as the file was read: it re-emits the original
	 * main.xml byte for byte, an edit of it never reaches the first model, and an edit of the first model
	 * never reaches it - a model's main_xml entry stays as read, because a write re-emits into a copy.
	 */
	@Test
	fun readingAnArchiveAgainGivesAPrivateGraphAsRead() {
		val file = sample ?: skipTest("cmo3.sample not present; skipping the archive reread test")
		val model = Cmo3.read(file)
		val originalMainXml = CaffCodec.read(file.readBytes()).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
		val mainXmlOf = { cmo3: Cmo3Model -> CaffCodec.read(Cmo3.write(cmo3)).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content }

		val workingCopy = Cmo3.read(model.archive)
		assertSame(model.archive, workingCopy.archive, "the reread shares the archive value")
		assertNotSame(model.root, workingCopy.root, "the reread parses a graph of its own")
		assertContentEquals(originalMainXml, mainXmlOf(workingCopy), "the reread re-emits the original main.xml")

		// Edits of the working copy - a graph field and a layer's pixels - stay in the working copy.
		val firstResource = model.imageResources().first()
		val originalPng = model.extractLayerPng(firstResource)!!
		val workingResources = workingCopy.imageResources()
		workingCopy.setTargetVersionNo((model.targetVersionNo ?: 0) + 1)
		workingCopy.replaceLayerPng(workingResources.first(), workingCopy.extractLayerPng(workingResources[1])!!)
		assertFalse(originalMainXml.contentEquals(mainXmlOf(workingCopy)), "the working copy's edit reaches its own main.xml")
		assertNotSame(model.archive, workingCopy.archive, "a pixel edit swaps in an archive of the working copy's own")
		assertContentEquals(originalMainXml, mainXmlOf(model), "the first model re-emits the original main.xml")
		assertContentEquals(originalPng, model.extractLayerPng(firstResource), "the first model's layer keeps its pixels")

		// An edit of the first model stays in its graph: its archive still rereads as the file was read.
		model.setTargetVersionNo((model.targetVersionNo ?: 0) + 2)
		assertFalse(originalMainXml.contentEquals(mainXmlOf(model)), "the first model's edit reaches its own main.xml")
		assertContentEquals(originalMainXml, mainXmlOf(Cmo3.read(model.archive)), "a reread of the edited model's archive re-emits the original main.xml")
	}
}