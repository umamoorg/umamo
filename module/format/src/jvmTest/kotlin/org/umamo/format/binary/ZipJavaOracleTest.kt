package org.umamo.format.binary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.zip.ZipEntry as JavaZipEntry

/**
 * Differential test against java.util.zip in both directions, the way ImageIO stayed an oracle for the
 * PNG codec: archives Java writes read identically through ZipArchive, archives ZipWriter writes read
 * identically through ZipFile and ZipInputStream, and every corpus .kra lists and inflates the same
 * through both readers.
 */
class ZipJavaOracleTest {
	/**
	 * One entry as java.util.zip reads it.
	 *
	 * @property String    name     The entry name.
	 * @property ByteArray contents The uncompressed bytes.
	 */
	private class JavaReadEntry(
		val name: String,
		val contents: ByteArray,
	)

	/**
	 * Every entry of [bytes], read sequentially through ZipInputStream.
	 *
	 * @param ByteArray bytes The archive.
	 * @return List<JavaReadEntry> The entries in stream order.
	 */
	private fun readWithZipInputStream(bytes: ByteArray): List<JavaReadEntry> {
		val entries = ArrayList<JavaReadEntry>()
		ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
			var entry = zip.nextEntry
			while (entry != null) {
				entries += JavaReadEntry(entry.name, zip.readBytes())
				zip.closeEntry()
				entry = zip.nextEntry
			}
		}
		return entries
	}

	/**
	 * Every entry of [bytes], read through ZipFile's central directory.
	 *
	 * @param ByteArray bytes The archive.
	 * @return List<JavaReadEntry> The entries in directory order.
	 */
	private fun readWithZipFile(bytes: ByteArray): List<JavaReadEntry> {
		val file = File.createTempFile("umamo-zip-oracle", ".zip")
		try {
			file.writeBytes(bytes)
			ZipFile(file).use { zip ->
				return zip.entries().toList().map { entry -> JavaReadEntry(entry.name, zip.getInputStream(entry).use { stream -> stream.readBytes() }) }
			}
		} finally {
			file.delete()
		}
	}

	/**
	 * Asserts ZipArchive reads [bytes] as exactly [expected]: names in order and contents byte for byte.
	 *
	 * @param ByteArray           bytes    The archive.
	 * @param List<JavaReadEntry> expected The entries java.util.zip read.
	 * @param String              label    What is being compared, for failure messages.
	 */
	private fun assertArchiveReads(bytes: ByteArray, expected: List<JavaReadEntry>, label: String) {
		val archive = ZipArchive.read(bytes)
		assertEquals(expected.map { entry -> entry.name }, archive.entries.map { entry -> entry.name }, "$label: entry names and order")
		for ((entryIndex, entry) in archive.entries.withIndex()) {
			assertContentEquals(expected[entryIndex].contents, archive.contents(entry), "$label: '${entry.name}' contents")
		}
	}

	/**
	 * The entries the synthetic Java archives hold: stored and deflated, empty, a directory, and names
	 * outside ASCII.
	 *
	 * @return List The name, contents, and whether the entry is deflated.
	 */
	private fun syntheticEntries(): List<Triple<String, ByteArray, Boolean>> =
		listOf(
			Triple("mimetype", "application/vnd.umamo.uma+zip".encodeToByteArray(), false),
			Triple("model/puppet.json", ByteArray(50_000) { byteIndex -> (byteIndex % 17).toByte() }, true),
			Triple("empty-deflated", ByteArray(0), true),
			Triple("textures/", ByteArray(0), false),
			Triple("テクスチャ/ページ.bin", ByteArray(3000) { byteIndex -> (byteIndex * 31).toByte() }, false),
			Triple("émoji-😀.txt", "naïve".encodeToByteArray(), true),
		)

	/**
	 * An archive Java writes with ZipOutputStream, whose deflated entries carry data descriptors.
	 *
	 * @param List entries The name, contents, and whether the entry is deflated.
	 * @return ByteArray The archive.
	 */
	private fun javaArchiveOf(entries: List<Triple<String, ByteArray, Boolean>>): ByteArray {
		val sink = ByteArrayOutputStream()
		ZipOutputStream(sink).use { zip ->
			for ((name, contents, deflated) in entries) {
				val entry = JavaZipEntry(name)
				if (!deflated) {
					val checksum = CRC32()
					checksum.update(contents)
					entry.method = JavaZipEntry.STORED
					entry.size = contents.size.toLong()
					entry.compressedSize = contents.size.toLong()
					entry.crc = checksum.value
				}
				zip.putNextEntry(entry)
				zip.write(contents)
				zip.closeEntry()
			}
		}
		return sink.toByteArray()
	}

	/**
	 * Archives Java writes read identically through ZipArchive, data descriptors included.
	 */
	@Test
	fun javaWrittenArchivesReadIdentically() {
		val bytes = javaArchiveOf(syntheticEntries())
		val archive = ZipArchive.read(bytes)
		assertTrue(
			archive.entries.any { entry -> (entry.flags and ZipRecords.FLAG_DATA_DESCRIPTOR) != 0 },
			"the Java archive exercises data descriptors",
		)
		assertArchiveReads(bytes, readWithZipInputStream(bytes), "Java-written archive")
	}

	/**
	 * A Java archive with more entries than the classic count field holds - which Java writes with Zip64
	 * end records - reads whole.
	 */
	@Test
	fun javaZip64ArchiveReads() {
		val entries = List(70_000) { entryIndex -> Triple("entry-$entryIndex", byteArrayOf(entryIndex.toByte()), false) }
		val bytes = javaArchiveOf(entries)
		assertArchiveReads(bytes, entries.map { (name, contents) -> JavaReadEntry(name, contents) }, "Java Zip64 archive")
	}

	/**
	 * A raw copy of a Java entry that carried a data descriptor drops the flag, since the copy's sizes sit
	 * in its local header - and java.util.zip reads the copy back.
	 */
	@Test
	fun rawCopyOfADataDescriptorEntryDropsTheFlag() {
		val source = ZipArchive.read(javaArchiveOf(syntheticEntries()))
		val copier = ZipWriter(TEST_DOS_DATE_TIME)
		for (entry in source.entries) {
			copier.addRaw(entry, source.rawPayload(entry))
		}
		val copied = copier.finish()
		assertTrue(
			ZipArchive.read(copied).entries.none { entry -> (entry.flags and ZipRecords.FLAG_DATA_DESCRIPTOR) != 0 },
			"no copied entry claims a data descriptor",
		)
		assertArchiveReads(copied, readWithZipInputStream(copied), "raw copy through ZipInputStream")
	}

	/**
	 * Archives ZipWriter writes read identically through ZipInputStream and ZipFile.
	 */
	@Test
	fun writerOutputReadsThroughJavaZip() {
		val entries = syntheticEntries()
		val bytes = archiveOf(*entries.toTypedArray())
		val expected = entries.map { (name, contents) -> JavaReadEntry(name, contents) }
		for ((label, javaRead) in listOf("ZipInputStream" to readWithZipInputStream(bytes), "ZipFile" to readWithZipFile(bytes))) {
			assertEquals(expected.map { entry -> entry.name }, javaRead.map { entry -> entry.name }, "$label: entry names and order")
			for ((entryIndex, entry) in javaRead.withIndex()) {
				assertContentEquals(expected[entryIndex].contents, entry.contents, "$label: '${entry.name}' contents")
			}
		}
	}

	/**
	 * A ZipWriter archive with Zip64 end records reads whole through ZipFile, which follows them.
	 */
	@Test
	fun writerZip64OutputReadsThroughZipFile() {
		val writer = ZipWriter(TEST_DOS_DATE_TIME)
		repeat(70_000) { entryIndex ->
			writer.addStored("entry-$entryIndex", byteArrayOf(entryIndex.toByte()))
		}
		val javaRead = readWithZipFile(writer.finish())
		assertEquals(70_000, javaRead.size, "ZipFile sees every entry")
		assertEquals("entry-69999", javaRead.last().name, "the last entry is intact")
		assertContentEquals(byteArrayOf(69_999.toByte()), javaRead.last().contents, "and its contents")
	}

	/**
	 * Every corpus .kra lists and inflates identically through ZipArchive and ZipInputStream.  Corpus-gated:
	 * `-Dkra.sample` when set, else every .kra under test/corpus/krita found by walking up from the working
	 * directory; with neither it self-skips.
	 */
	@Test
	fun corpusKraArchivesMatchZipInputStream() {
		val samples = locateKraSamples()
		if (samples.isEmpty()) {
			println("no kra.sample and no test/corpus/krita samples; skipping the KRA ZIP oracle")
			return
		}
		for (sample in samples) {
			val bytes = sample.readBytes()
			val expected = readWithZipInputStream(bytes)
			assertTrue(expected.isNotEmpty(), "${sample.name}: java.util.zip found entries")
			assertArchiveReads(bytes, expected, sample.name)
			println("[Umamo][zip] ${sample.name}: ${expected.size} entries match java.util.zip")
		}
	}

	/**
	 * Locates the KRA samples: `-Dkra.sample` when set, else every .kra under test/corpus/krita.
	 *
	 * @return List<File> The samples, empty when none are configured.
	 */
	private fun locateKraSamples(): List<File> {
		System.getProperty("kra.sample")?.let { path ->
			return listOfNotNull(File(path).takeIf(File::isFile))
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus/krita")
			if (corpus.isDirectory) {
				return corpus.listFiles { file -> file.extension.equals("kra", ignoreCase = true) }?.sortedBy { it.name } ?: emptyList()
			}
			directory = directory.parentFile
		}
		return emptyList()
	}
}