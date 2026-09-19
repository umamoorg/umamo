package org.umamo.format.binary

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the ZIP writer's byte layout - the parts other code relies on without reading the archive back:
 * the fixed mimetype offset, byte-reproducible output, and when Zip64 records appear.  In commonTest, so
 * the layout compiles for Kotlin/Native.
 */
class ZipWriterTest {
	private val mimetype = "application/vnd.umamo.uma+zip".encodeToByteArray()

	/**
	 * Whether [signature] occurs anywhere in [bytes].
	 *
	 * @param ByteArray bytes     The buffer to scan.
	 * @param Int       signature The little-endian 32-bit signature.
	 * @return Boolean True when it occurs.
	 */
	private fun containsSignature(bytes: ByteArray, signature: Int): Boolean =
		(0..bytes.size - 4).any { offset -> readU32Le(bytes, offset) == signature.toLong() }

	/**
	 * A stored first entry's payload starts at offset 30 plus its name length - 38 for "mimetype" - because
	 * the writer emits no extra field.  The UMA identification probe reads it there without parsing the ZIP.
	 */
	@Test
	fun storedMimetypeSitsAtOffset38() {
		val writer = ZipWriter(TEST_DOS_DATE_TIME)
		writer.addStored("mimetype", mimetype)
		writer.addDeflated("manifest.json", "{}".encodeToByteArray())
		val bytes = writer.finish()
		assertEquals(0, readU16Le(bytes, 28), "no extra field on the first local header")
		assertContentEquals("mimetype".encodeToByteArray(), bytes.copyOfRange(30, 38), "the name follows the fixed header")
		assertContentEquals(mimetype, bytes.copyOfRange(38, 38 + mimetype.size), "the payload sits at offset 38")
	}

	/**
	 * The same entries and stamp always produce the same bytes, and the stamp is the only clock in them.
	 */
	@Test
	fun outputIsAPureFunctionOfEntriesAndStamp() {
		/**
		 * Writes the fixed test entries under a stamp.
		 *
		 * @param Int stamp The DOS date and time.
		 * @return ByteArray The archive.
		 */
		fun write(stamp: Int): ByteArray {
			val writer = ZipWriter(stamp)
			writer.addStored("mimetype", mimetype)
			writer.addDeflated("model/puppet.json", ByteArray(5000) { byteIndex -> (byteIndex % 7).toByte() })
			writer.addStored("textures/", ByteArray(0))
			return writer.finish()
		}
		assertContentEquals(write(TEST_DOS_DATE_TIME), write(TEST_DOS_DATE_TIME), "identical inputs, identical bytes")
		val epoch = write(ZipRecords.DOS_EPOCH_DATE_TIME)
		assertFalse(write(TEST_DOS_DATE_TIME).contentEquals(epoch), "the stamp reaches the bytes")
		// ZIP: local header @ +0x0A time, @ +0x0C date.
		assertEquals(ZipRecords.DOS_EPOCH_DATE_TIME and 0xFFFF, readU16Le(epoch, 10), "local time field")
		assertEquals(ZipRecords.DOS_EPOCH_DATE_TIME ushr 16, readU16Le(epoch, 12), "local date field")
	}

	/**
	 * Every header declares a UTF-8 name and carries its sizes up front, with no data descriptor.
	 */
	@Test
	fun headersDeclareUtf8NamesAndInlineSizes() {
		val bytes = archiveOf(Triple("ページ", ByteArray(300) { 1 }, true))
		val localFlags = readU16Le(bytes, 6)
		assertEquals(ZipRecords.FLAG_UTF8_NAME, localFlags, "local flags: UTF-8 name, no data descriptor")
		assertEquals(ZipRecords.FLAG_UTF8_NAME, readU16Le(bytes, centralHeaderOffset(bytes, 0) + 8), "central flags")
		assertTrue(readU32Le(bytes, 18) > 0, "the local header carries the compressed size")
		assertEquals(300L, readU32Le(bytes, 22), "the local header carries the uncompressed size")
		assertFalse(containsSignature(bytes, ZipRecords.DATA_DESCRIPTOR_SIGNATURE), "no data descriptor")
	}

	/**
	 * Zip64 end records appear exactly when the entry count reaches the classic field's reserved all-ones
	 * value, and an archive on either side of that line reads back whole.
	 */
	@Test
	fun zip64RecordsAppearOnlyWhenTheEntryCountNeedsThem() {
		for (entryCount in listOf(0xFFFE, 0xFFFF)) {
			val writer = ZipWriter(TEST_DOS_DATE_TIME)
			repeat(entryCount) { entryIndex ->
				writer.addStored("e$entryIndex", ByteArray(0))
			}
			val bytes = writer.finish()
			val needsZip64 = entryCount >= 0xFFFF
			val endOffset = endRecordOffset(bytes)
			assertEquals(if (needsZip64) 0xFFFF else entryCount, readU16Le(bytes, endOffset + 10), "$entryCount entries: classic count field")
			assertEquals(
				needsZip64,
				readU32Le(bytes, endOffset - ZipRecords.ZIP64_LOCATOR_SIZE) == ZipRecords.ZIP64_LOCATOR_SIGNATURE.toLong(),
				"$entryCount entries: Zip64 locator present",
			)
			val archive = ZipArchive.read(bytes)
			assertEquals(entryCount, archive.entries.size, "$entryCount entries read back")
			assertEquals("e${entryCount - 1}", archive.entries.last().name, "$entryCount entries: the last one is intact")
		}
	}

	/**
	 * Copying every entry raw, under the same stamp, reproduces the archive byte for byte.
	 */
	@Test
	fun rawCopyReproducesTheArchive() {
		val original =
			archiveOf(
				Triple("mimetype", mimetype, false),
				Triple("model/puppet.json", ByteArray(20_000) { byteIndex -> (byteIndex % 11).toByte() }, true),
				Triple("textures/tile-0.png", ByteArray(700) { byteIndex -> byteIndex.toByte() }, false),
			)
		val source = ZipArchive.read(original)
		val copier = ZipWriter(TEST_DOS_DATE_TIME)
		for (entry in source.entries) {
			copier.addRaw(entry, source.rawPayload(entry))
		}
		assertContentEquals(original, copier.finish(), "a raw copy is byte-identical")
	}

	/**
	 * A raw copy's payload must match the size the entry declares.
	 */
	@Test
	fun rawCopyRejectsAMismatchedPayload() {
		val source = ZipArchive.read(archiveOf(Triple("entry", ByteArray(100) { 3 }, true)))
		val entry = source.entries.single()
		assertFailsWith<IllegalArgumentException> { ZipWriter(TEST_DOS_DATE_TIME).addRaw(entry, source.rawPayload(entry).copyOf(1)) }
	}

	/**
	 * A raw copy of an encrypted entry whose source used a data descriptor keeps the flag, its source's timestamp, and
	 * the descriptor, which traditional decryption checks against; an unencrypted one is written like any other entry.
	 */
	@Test
	fun rawCopyKeepsWhatDecryptionChecks() {
		val bytes = archiveOf(Triple("secret", ByteArray(300) { byteIndex -> (byteIndex % 7).toByte() }, true), Triple("plain", ByteArray(40) { 5 }, false))
		for (entryIndex in 0..1) {
			val header = centralHeaderOffset(bytes, entryIndex)
			val extraFlags = if (entryIndex == 0) ZipRecords.FLAG_ENCRYPTED or ZipRecords.FLAG_DATA_DESCRIPTOR else ZipRecords.FLAG_DATA_DESCRIPTOR
			writeU16Le(bytes, header + 8, readU16Le(bytes, header + 8) or extraFlags)
		}
		val source = ZipArchive.read(bytes)
		val copier = ZipWriter(ZipRecords.DOS_EPOCH_DATE_TIME)
		for (entry in source.entries) {
			copier.addRaw(entry, source.rawPayload(entry))
		}
		val copied = copier.finish()
		val archive = ZipArchive.read(copied)
		val secret = archive.entry("secret")!!
		val plain = archive.entry("plain")!!
		assertEquals(ZipRecords.FLAG_ENCRYPTED or ZipRecords.FLAG_DATA_DESCRIPTOR, secret.flags and (ZipRecords.FLAG_ENCRYPTED or ZipRecords.FLAG_DATA_DESCRIPTOR), "the encrypted entry keeps its descriptor flag")
		assertEquals(TEST_DOS_DATE_TIME, secret.dosDateTime, "and its source's timestamp")
		assertEquals(0, plain.flags and ZipRecords.FLAG_DATA_DESCRIPTOR, "an unencrypted entry drops the flag")
		assertEquals(ZipRecords.DOS_EPOCH_DATE_TIME, plain.dosDateTime, "and takes this writer's timestamp")

		val local = localHeaderOffset(copied, 0)
		assertEquals(0L, readU32Le(copied, local + 14), "the local header leaves the CRC-32 to the descriptor")
		assertEquals(0L, readU32Le(copied, local + 18), "and the compressed size")
		val descriptor = secret.payloadOffset + secret.compressedSize
		assertEquals(ZipRecords.DATA_DESCRIPTOR_SIGNATURE.toLong(), readU32Le(copied, descriptor), "a descriptor follows the payload")
		assertEquals(secret.crc32, readU32Le(copied, descriptor + 4))
		assertEquals(secret.compressedSize.toLong(), readU32Le(copied, descriptor + 8))
		assertEquals(secret.uncompressedSize.toLong(), readU32Le(copied, descriptor + 12))
		assertContentEquals(source.rawPayload(source.entry("secret")!!), archive.rawPayload(secret), "and the payload is unchanged")
	}

	/**
	 * A raw copy keeps a version needed higher than its method's, as an entry this writer never produces declares.
	 */
	@Test
	fun rawCopyKeepsTheVersionNeeded() {
		val bytes = archiveOf(Triple("aes", ByteArray(50) { 9 }, false))
		// ZIP: method 99 is AES encryption, which needs version 5.1 (APPNOTE.TXT 4.4.3, 4.4.5); patched into both headers.
		writeU16Le(bytes, centralHeaderOffset(bytes, 0) + 10, 99)
		writeU16Le(bytes, localHeaderOffset(bytes, 0) + 8, 99)
		writeU16Le(bytes, centralHeaderOffset(bytes, 0) + 6, 51)
		val source = ZipArchive.read(bytes)
		val copier = ZipWriter(TEST_DOS_DATE_TIME)
		copier.addRaw(source.entries.single(), source.rawPayload(source.entries.single()))
		val copied = copier.finish()
		assertEquals(51, ZipArchive.read(copied).entries.single().versionNeeded, "the central header keeps it")
		assertEquals(51, readU16Le(copied, localHeaderOffset(copied, 0) + 4), "and so does the local header")
	}

	/**
	 * An entry the writer refuses leaves nothing behind: its name is still free, so the refusal a retry meets is the
	 * one it earned.
	 */
	@Test
	fun aRefusedEntryLeavesTheNameFree() {
		val writer = ZipWriter(TEST_DOS_DATE_TIME)
		val tooLong = "n".repeat(70_000)
		assertFailsWith<IllegalArgumentException> { writer.addStored(tooLong, ByteArray(1)) }
		val retry = assertFailsWith<IllegalArgumentException> { writer.addStored(tooLong, ByteArray(1)) }
		assertTrue(retry.message.orEmpty().contains("longer than a ZIP header can hold"), "the retry meets the length rule again: ${retry.message}")
		writer.addStored("entry", ByteArray(1))
		assertEquals(listOf("entry"), ZipArchive.read(writer.finish()).entries.map { entry -> entry.name }, "and the archive holds only what was accepted")
	}

	/**
	 * The writer refuses what would make an ambiguous or broken archive: a repeated or empty name, a DEFLATE
	 * level out of range, and anything added after finishing.
	 */
	@Test
	fun rejectsInvalidUse() {
		val writer = ZipWriter(TEST_DOS_DATE_TIME)
		writer.addStored("entry", ByteArray(1))
		assertFailsWith<IllegalArgumentException>("repeated name") { writer.addDeflated("entry", ByteArray(1)) }
		assertFailsWith<IllegalArgumentException>("empty name") { writer.addStored("", ByteArray(1)) }
		assertFailsWith<IllegalArgumentException>("level out of range") { writer.addDeflated("other", ByteArray(1), 10) }
		writer.finish()
		assertFailsWith<IllegalStateException>("add after finish") { writer.addStored("late", ByteArray(1)) }
		assertFailsWith<IllegalStateException>("finish twice") { writer.finish() }
	}
}