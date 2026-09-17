package org.umamo.format.uma

import org.umamo.format.binary.ZipArchive
import org.umamo.format.binary.ZipRecords
import org.umamo.format.binary.ZipWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins how a UMA file identifies itself (docs/format/UMA.md §2) and the layout the writer gives it: the
 * fixed-offset mimetype probe accepts exactly what the writer emits and nothing merely similar.
 */
class UmaIdentificationTest {
	private val written = Uma.write(UmaModel.create(TEST_WRITER).withLiveContent(UmaEntryKind.Puppet, sampleTree("puppet")))

	/**
	 * A mimetype-first archive with the given first entry.
	 *
	 * @param String    name     The first entry's name.
	 * @param ByteArray contents The first entry's contents.
	 * @param Boolean   deflated Whether the first entry is compressed.
	 * @return ByteArray The archive.
	 */
	private fun archiveStartingWith(name: String, contents: ByteArray, deflated: Boolean = false): ByteArray {
		val writer = ZipWriter(ZipRecords.DOS_EPOCH_DATE_TIME)
		if (deflated) {
			writer.addDeflated(name, contents)
		} else {
			writer.addStored(name, contents)
		}
		writer.addDeflated("second.json", "{}".encodeToByteArray())
		return writer.finish()
	}

	/**
	 * What the writer emits is what the probe accepts.
	 */
	@Test
	fun probeAcceptsWriterOutput() {
		assertTrue(Uma.matches(written), "a written UMA announces itself")
	}

	/**
	 * The probe rejects every near miss: another ZIP format's mimetype, a compressed or displaced mimetype,
	 * an extra field that moves the content, a wrong or longer string, and bytes that are not a ZIP.
	 */
	@Test
	fun probeRejectsNearMisses() {
		val mimetype = UmaContainer.MIMETYPE.encodeToByteArray()
		assertFalse(Uma.matches(archiveStartingWith("mimetype", "application/x-krita".encodeToByteArray())), "a Krita mimetype")
		assertFalse(Uma.matches(archiveStartingWith("mimetype", mimetype, deflated = true)), "a deflated mimetype")
		assertFalse(Uma.matches(archiveStartingWith("manifest.json", mimetype)), "the right content under another name")
		assertFalse(Uma.matches(archiveStartingWith("mimetype", "application/vnd.umamo.uma+zipx".encodeToByteArray())), "a longer string with the right prefix")
		assertFalse(Uma.matches(archiveStartingWith("mimetype", "application/vnd.umamo.umb+zip".encodeToByteArray())), "a wrong string")

		val withExtraField = written.copyOf()
		// ZIP: local header @ +0x1C extra length; a non-zero value moves the content off offset 38.
		withExtraField[28] = 4
		assertFalse(Uma.matches(withExtraField), "an extra field on the mimetype entry")

		val writer = ZipWriter(ZipRecords.DOS_EPOCH_DATE_TIME)
		writer.addDeflated(UmaContainer.MANIFEST_PATH, "{}".encodeToByteArray())
		writer.addStored(UmaContainer.MIMETYPE_PATH, mimetype)
		assertFalse(Uma.matches(writer.finish()), "a mimetype that is not the first entry")

		assertFalse(Uma.matches(ByteArray(200) { byteIndex -> byteIndex.toByte() }), "random bytes")
		assertFalse(Uma.matches(written.copyOf(40)), "a file cut off inside the mimetype")
	}

	/**
	 * The writer puts the mimetype first and stored at offset 38, the manifest second, and stamps every
	 * entry with the fixed epoch.
	 */
	@Test
	fun writerLayoutIsFixed() {
		val mimetype = UmaContainer.MIMETYPE.encodeToByteArray()
		assertContentEquals(mimetype, written.copyOfRange(38, 38 + mimetype.size), "the mimetype content sits at offset 38")
		val archive = ZipArchive.read(written)
		assertEquals(listOf("mimetype", "manifest.json", "model/puppet.json"), archive.entries.map { entry -> entry.name }, "entry order")
		assertEquals(ZipRecords.METHOD_STORED, archive.entries.first().method, "the mimetype is stored")
		assertTrue(archive.entries.all { entry -> entry.dosDateTime == ZipRecords.DOS_EPOCH_DATE_TIME }, "every entry carries the fixed stamp")
	}

	/**
	 * Written JSON is pretty-printed with tabs.
	 */
	@Test
	fun writtenJsonIsTabIndented() {
		val archive = ZipArchive.read(written)
		val manifest = archive.contents(archive.entry(UmaContainer.MANIFEST_PATH)!!).decodeToString()
		assertTrue(manifest.startsWith("{\n\t\"format\": \"uma\""), "the manifest opens tab-indented: ${manifest.take(40)}")
	}
}