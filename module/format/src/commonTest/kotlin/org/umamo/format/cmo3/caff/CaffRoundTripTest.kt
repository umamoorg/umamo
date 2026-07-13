package org.umamo.format.cmo3.caff

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Platform-independent CAFF codec tests using synthetic archives (no sample/jar needed).
 */
class CaffRoundTripTest {
	private fun assertEntriesEqual(expected: CaffEntry, actual: CaffEntry) {
		assertEquals(expected.path, actual.path)
		assertEquals(expected.tag, actual.tag)
		assertEquals(expected.compression, actual.compression)
		assertEquals(expected.obfuscated, actual.obfuscated)
		assertContentEquals(expected.content, actual.content, "content of ${expected.path}")
	}

	@Test
	fun roundTripsObfuscatedMixedCompression() {
		val entries =
			listOf(
				CaffEntry("raw.bin", "", byteArrayOf(0, 1, 2, 3, 0x7F, -1, -128), CompressOption.RAW, obfuscated = true),
				CaffEntry("doc.xml", CaffArchive.TAG_MAIN_XML, "<?xml?><root>テスト</root>".encodeToByteArray(), CompressOption.FAST, obfuscated = true),
				CaffEntry("plain.txt", "", "no-xor".encodeToByteArray(), CompressOption.RAW, obfuscated = false),
			)
		val archive = CaffArchive(obfuscateKey = 0xD7FC71B1.toInt(), entries = entries)

		val bytes = CaffCodec.write(archive)
		assertEquals("CAFF", bytes.copyOfRange(0, 4).decodeToString())
		// Guard bytes 'b','c' terminate the blob region.
		assertEquals(0x62, bytes[bytes.size - 2].toInt() and 0xFF)
		assertEquals(0x63, bytes[bytes.size - 1].toInt() and 0xFF)

		val reparsed = CaffCodec.read(bytes)
		assertEquals(archive.obfuscateKey, reparsed.obfuscateKey)
		assertEquals(archive.formatIdentifier, reparsed.formatIdentifier)
		assertEquals(entries.size, reparsed.entries.size)
		for (index in entries.indices) assertEntriesEqual(entries[index], reparsed.entries[index])
		assertEquals(CaffArchive.TAG_MAIN_XML, reparsed.firstByTag(CaffArchive.TAG_MAIN_XML)?.tag)
	}

	@Test
	fun roundTripsUnobfuscatedArchive() {
		val entries = listOf(CaffEntry("a.bin", "", byteArrayOf(10, 20, 30), CompressOption.RAW, obfuscated = false))
		val archive = CaffArchive(obfuscateKey = 0, entries = entries)
		val reparsed = CaffCodec.read(CaffCodec.write(archive))
		assertEquals(0, reparsed.obfuscateKey)
		assertEntriesEqual(entries[0], reparsed.entries[0])
	}

	@Test
	fun emptyArchiveHasNoPreview() {
		val reparsed = CaffCodec.read(CaffCodec.write(CaffArchive()))
		assertEquals(0, reparsed.entries.size)
		assertTrue(!reparsed.preview.present)
	}
}
