package org.umamo.format.cmo3.caff

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the CAFF codec against the real sample, end to end: parse, inspect, and round-trip
 * through our own writer/reader. Self-contained (no editor jar). Skips when the sample is absent.
 */
class CaffSampleTest {
	private val sample: File? =
		System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	private val pngMagic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

	private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
		size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

	@Test
	fun readsSampleAndRoundTrips() {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping CAFF sample test")
			return
		}
		val ours = CaffCodec.read(file.readBytes())
		assertEquals(926, ours.entries.size, "entry count")
		assertEquals(0xD7FC71B1.toInt(), ours.obfuscateKey, "obfuscate key")
		assertEquals("----", ours.formatIdentifier, "format identifier")
		assertTrue(!ours.preview.present, "sample has NO_PREVIEW")

		val mainXml = ours.firstByTag(CaffArchive.TAG_MAIN_XML) ?: error("no main_xml entry")
		assertTrue(mainXml.content.decodeToString().startsWith("<?xml"), "main_xml is XML")
		assertEquals(CompressOption.FAST, mainXml.compression)

		val imageFileBufs = ours.entries.filter { it.path.startsWith("imageFileBuf") }
		assertEquals(180, imageFileBufs.size, "imageFileBuf count")
		for (entry in imageFileBufs) {
			assertTrue(entry.content.startsWith(pngMagic), "${entry.path} is a PNG")
			assertEquals(CompressOption.RAW, entry.compression)
		}

		// Round-trip through our own writer + reader.
		val ours2 = CaffCodec.read(CaffCodec.write(ours))
		assertEquals(ours.entries.size, ours2.entries.size)
		for (index in ours.entries.indices) {
			val before = ours.entries[index]
			val after = ours2.entries[index]
			assertEquals(before.path, after.path)
			assertEquals(before.tag, after.tag)
			assertEquals(before.compression, after.compression)
			assertEquals(before.obfuscated, after.obfuscated)
			assertContentEquals(before.content, after.content, "round-trip content of ${before.path}")
		}
	}
}
