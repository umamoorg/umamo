package org.umamo.format.cmo3

import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end facade test: read a real .cmo3 into a typed model, inspect/replace layer images, and
 * write it back losslessly. Self-contained (no editor jar). Skips without the sample.
 */
class Cmo3FacadeTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }
	private val pngMagic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

	private fun ByteArray.isPng() = size >= 4 && pngMagic.indices.all { this[it] == pngMagic[it] }

	@Test
	fun readsModelExposesLayersAndWritesBackByteIdentical() {
		val file =
			sample ?: run {
				println("cmo3.sample not present; skipping facade test")
				return
			}
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
		val file = sample ?: return
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
}
