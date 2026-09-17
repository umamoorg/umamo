package org.umamo.format.binary

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the ZIP reader's contract: what it reads back from well-formed archives, and that every kind of
 * damage or deceit fails loudly with a ZipFormatException rather than yielding wrong bytes.  In
 * commonTest, so the same contract compiles for Kotlin/Native.
 */
class ZipArchiveTest {
	// Compressible enough that DEFLATE genuinely shrinks it.
	private val compressible = ByteArray(40_000) { byteIndex -> (byteIndex % 13).toByte() }

	/**
	 * Every entry shape round-trips: stored, deflated, empty under both methods, a directory, and names
	 * outside ASCII - in insertion order, with the stamp and methods declared.
	 */
	@Test
	fun roundTripsEveryEntryShape() {
		val expected =
			listOf(
				Triple("mimetype", "application/vnd.umamo.uma+zip".encodeToByteArray(), false),
				Triple("model/puppet.json", compressible, true),
				Triple("empty-stored", ByteArray(0), false),
				Triple("empty-deflated", ByteArray(0), true),
				Triple("textures/", ByteArray(0), false),
				Triple("テクスチャ/ページ.png", compressible.copyOf(1000), false),
				Triple("émoji-😀.txt", "naïve".encodeToByteArray(), true),
			)
		val archive = ZipArchive.read(archiveOf(*expected.toTypedArray()))

		assertEquals(expected.map { entry -> entry.first }, archive.entries.map { entry -> entry.name }, "entry order")
		for ((name, contents, deflated) in expected) {
			val entry = assertNotNull(archive.entry(name), "entry '$name' is listed")
			assertContentEquals(contents, archive.contents(entry), "entry '$name' contents")
			assertEquals(if (deflated) ZipRecords.METHOD_DEFLATED else ZipRecords.METHOD_STORED, entry.method, "entry '$name' method")
			assertEquals(TEST_DOS_DATE_TIME, entry.dosDateTime, "entry '$name' stamp")
			assertEquals(name.endsWith('/'), entry.isDirectory, "entry '$name' directory flag")
		}
		val deflatedEntry = assertNotNull(archive.entry("model/puppet.json"))
		assertTrue(deflatedEntry.compressedSize < deflatedEntry.uncompressedSize, "the deflated entry actually compressed")
	}

	/**
	 * An archive with no entries is just its end record, and reads as empty.
	 */
	@Test
	fun readsAnEmptyArchive() {
		val bytes = ZipWriter(TEST_DOS_DATE_TIME).finish()
		assertEquals(ZipRecords.END_SIZE, bytes.size, "an empty archive is only its end record")
		assertTrue(ZipArchive.read(bytes).entries.isEmpty(), "no entries")
	}

	/**
	 * A raw payload is the compressed bytes exactly as stored.
	 */
	@Test
	fun rawPayloadIsTheStoredBytes() {
		val bytes = archiveOf(Triple("deflated", compressible, true))
		val archive = ZipArchive.read(bytes)
		val entry = archive.entries.single()
		val payload = archive.rawPayload(entry)
		assertEquals(entry.compressedSize, payload.size, "payload size")
		assertContentEquals(deflateRawDeflate(compressible, 6), payload, "payload is the raw DEFLATE stream")
	}

	/**
	 * Cutting bytes off the end loses the end record, and a file too short for one is refused outright.
	 */
	@Test
	fun truncatedArchiveFails() {
		val bytes = archiveOf(Triple("a", compressible, true), Triple("b", compressible, false))
		assertFailsWith<ZipFormatException>("one byte short") { ZipArchive.read(bytes.copyOf(bytes.size - 1)) }
		assertFailsWith<ZipFormatException>("half the archive") { ZipArchive.read(bytes.copyOf(bytes.size / 2)) }
		assertFailsWith<ZipFormatException>("shorter than an end record") { ZipArchive.read(ByteArray(10)) }
		assertFailsWith<ZipFormatException>("not a ZIP at all") { ZipArchive.read(compressible) }
	}

	/**
	 * An end record whose central directory was cut short does not describe the bytes before it.
	 */
	@Test
	fun truncatedCentralDirectoryFails() {
		val bytes = archiveOf(Triple("first", compressible, true), Triple("second", compressible, true))
		val directoryOffset = centralDirectoryOffset(bytes)
		val endRecord = bytes.copyOfRange(endRecordOffset(bytes), bytes.size)
		val truncated = bytes.copyOf(directoryOffset + ZipRecords.CENTRAL_HEADER_SIZE + 3) + endRecord
		assertFailsWith<ZipFormatException> { ZipArchive.read(truncated) }
	}

	/**
	 * A payload shorter than its stream - here the declared compressed size cut in half - cannot inflate
	 * to the declared size.
	 */
	@Test
	fun truncatedPayloadFails() {
		val bytes = archiveOf(Triple("entry", compressible, true))
		val header = centralHeaderOffset(bytes, 0)
		writeU32Le(bytes, header + 20, readU32Le(bytes, header + 20) / 2)
		val archive = ZipArchive.read(bytes)
		assertFailsWith<ZipFormatException> { archive.contents(archive.entries.single()) }
	}

	/**
	 * A payload whose bytes changed after the CRC-32 was recorded is caught by the CRC-32.
	 */
	@Test
	fun crcMismatchFails() {
		val bytes = archiveOf(Triple("stored", compressible, false))
		val archive = ZipArchive.read(bytes)
		val entry = archive.entries.single()
		bytes[entry.payloadOffset + 100] = (bytes[entry.payloadOffset + 100] + 1).toByte()
		assertFailsWith<ZipFormatException> { archive.contents(entry) }
	}

	/**
	 * An uncompressed size that lies in either direction fails, for a deflated and a stored entry alike.
	 */
	@Test
	fun declaredSizeThatLiesFails() {
		for (delta in listOf(-1L, 1L)) {
			val bytes = archiveOf(Triple("deflated", compressible, true), Triple("stored", compressible, false))
			for (entryIndex in 0..1) {
				val header = centralHeaderOffset(bytes, entryIndex)
				writeU32Le(bytes, header + 24, readU32Le(bytes, header + 24) + delta)
			}
			val archive = ZipArchive.read(bytes)
			for (entry in archive.entries) {
				assertFailsWith<ZipFormatException>("'${entry.name}' with its size off by $delta") { archive.contents(entry) }
			}
		}
	}

	/**
	 * A stream that inflates far past its declared size stops one byte past it and fails, rather than
	 * inflating everything it holds.
	 */
	@Test
	fun decompressionBombStopsAtItsDeclaredSize() {
		val bomb = ByteArray(10 * 1024 * 1024)
		val bytes = archiveOf(Triple("bomb", bomb, true))
		writeU32Le(bytes, centralHeaderOffset(bytes, 0) + 24, 100)
		val archive = ZipArchive.read(bytes)
		val entry = archive.entries.single()
		assertTrue(entry.compressedSize < 64 * 1024, "the bomb is small on disk: ${entry.compressedSize}")
		val failure = assertFailsWith<ZipFormatException> { archive.contents(entry) }
		assertTrue(failure.message.orEmpty().contains("more than 100"), "the failure names the bound: ${failure.message}")
	}

	/**
	 * An encrypted entry cannot be inflated, but its raw payload still copies.
	 */
	@Test
	fun encryptedEntryRefusesContentsButCopiesRaw() {
		val bytes = archiveOf(Triple("secret", compressible, true))
		val header = centralHeaderOffset(bytes, 0)
		writeU16Le(bytes, header + 8, readU16Le(bytes, header + 8) or ZipRecords.FLAG_ENCRYPTED)
		val archive = ZipArchive.read(bytes)
		val entry = archive.entries.single()
		assertTrue(entry.isEncrypted, "the flag reads back")
		assertFailsWith<ZipFormatException> { archive.contents(entry) }
		assertContentEquals(deflateRawDeflate(compressible, 6), archive.rawPayload(entry), "raw payload still copies")
	}

	/**
	 * A method other than stored or DEFLATE lists but cannot be inflated.
	 */
	@Test
	fun unsupportedMethodFailsOnContents() {
		val bytes = archiveOf(Triple("bzip2", compressible, true))
		// ZIP: method 12 is BZIP2 (APPNOTE.TXT 4.4.5), patched into both headers so they agree.
		writeU16Le(bytes, centralHeaderOffset(bytes, 0) + 10, 12)
		writeU16Le(bytes, localHeaderOffset(bytes, 0) + 8, 12)
		val archive = ZipArchive.read(bytes)
		assertEquals(12, archive.entries.single().method, "the method reads back")
		assertFailsWith<ZipFormatException> { archive.contents(archive.entries.single()) }
	}

	/**
	 * A local header that disagrees with its central header about the method or the name fails the read.
	 */
	@Test
	fun localHeaderDisagreementFails() {
		val methodMismatch = archiveOf(Triple("entry", compressible, true))
		writeU16Le(methodMismatch, centralHeaderOffset(methodMismatch, 0) + 10, ZipRecords.METHOD_STORED)
		assertFailsWith<ZipFormatException>("method mismatch") { ZipArchive.read(methodMismatch) }

		val nameMismatch = archiveOf(Triple("abc", compressible, true))
		nameMismatch[localHeaderOffset(nameMismatch, 0) + ZipRecords.LOCAL_HEADER_SIZE] = 'x'.code.toByte()
		assertFailsWith<ZipFormatException>("name mismatch") { ZipArchive.read(nameMismatch) }

		val outOfRange = archiveOf(Triple("entry", compressible, true))
		writeU32Le(outOfRange, centralHeaderOffset(outOfRange, 0) + 42, centralDirectoryOffset(outOfRange).toLong())
		assertFailsWith<ZipFormatException>("local header offset inside the directory") { ZipArchive.read(outOfRange) }
	}

	/**
	 * Two entries with the same name make every lookup ambiguous, so the read refuses them.
	 */
	@Test
	fun duplicateNamesFail() {
		val bytes = archiveOf(Triple("a1", compressible, false), Triple("a2", compressible, false))
		bytes[centralHeaderOffset(bytes, 1) + ZipRecords.CENTRAL_HEADER_SIZE + 1] = '1'.code.toByte()
		bytes[localHeaderOffset(bytes, 1) + ZipRecords.LOCAL_HEADER_SIZE + 1] = '1'.code.toByte()
		assertFailsWith<ZipFormatException> { ZipArchive.read(bytes) }
	}

	/**
	 * A name that is not valid UTF-8 fails rather than decoding to replacement characters.
	 */
	@Test
	fun invalidUtf8NameFails() {
		val bytes = archiveOf(Triple("ab", compressible, false))
		val invalid = 0xFF.toByte()
		bytes[centralHeaderOffset(bytes, 0) + ZipRecords.CENTRAL_HEADER_SIZE] = invalid
		bytes[localHeaderOffset(bytes, 0) + ZipRecords.LOCAL_HEADER_SIZE] = invalid
		assertFailsWith<ZipFormatException> { ZipArchive.read(bytes) }
	}

	/**
	 * A comment holding an end-record look-alike - even one at the very end, which satisfies the comment
	 * rule - does not hijack the read.
	 */
	@Test
	fun endRecordLookAlikeInCommentIsIgnored() {
		val original = archiveOf(Triple("first", compressible, true), Triple("second", "two".encodeToByteArray(), false))
		val lookAlike = ByteArray(ZipRecords.END_SIZE)
		writeU32Le(lookAlike, 0, ZipRecords.END_SIGNATURE.toLong())
		val commented = original + lookAlike
		writeU16Le(commented, endRecordOffset(original) + 20, lookAlike.size)
		val archive = ZipArchive.read(commented)
		assertEquals(listOf("first", "second"), archive.entries.map { entry -> entry.name }, "the genuine directory is read")
		assertContentEquals(compressible, archive.contents(archive.entries.first()), "and its entries inflate")
	}

	/**
	 * A look-alike after the genuine end record that would fail the read if it were examined - one declaring another
	 * disk, one declaring Zip64 over a locator look-alike - is never reached, so the archive reads.
	 */
	@Test
	fun failingLookAlikeInCommentIsNeverExamined() {
		val original = archiveOf(Triple("first", compressible, true), Triple("second", "two".encodeToByteArray(), false))
		val genuineEnd = endRecordOffset(original)

		val multiDisk = original.copyOfRange(genuineEnd, original.size)
		writeU16Le(multiDisk, 4, 1)
		val withMultiDisk = original + multiDisk
		writeU16Le(withMultiDisk, genuineEnd + 20, multiDisk.size)
		assertEquals(listOf("first", "second"), ZipArchive.read(withMultiDisk).entries.map { entry -> entry.name }, "a multi-disk look-alike")

		val locator = ByteArray(ZipRecords.ZIP64_LOCATOR_SIZE)
		writeU32Le(locator, 0, ZipRecords.ZIP64_LOCATOR_SIGNATURE.toLong())
		val zip64 = original.copyOfRange(genuineEnd, original.size)
		writeU16Le(zip64, 8, ZipRecords.UINT16_SENTINEL)
		writeU16Le(zip64, 10, ZipRecords.UINT16_SENTINEL)
		val withZip64 = original + locator + zip64
		writeU16Le(withZip64, genuineEnd + 20, locator.size + zip64.size)
		assertEquals(listOf("first", "second"), ZipArchive.read(withZip64).entries.map { entry -> entry.name }, "a Zip64 look-alike over a locator look-alike")
	}

	/**
	 * A split archive is refused rather than read as if its first disk were the whole of it.
	 */
	@Test
	fun multiDiskArchiveFails() {
		val bytes = archiveOf(Triple("entry", compressible, false))
		writeU16Le(bytes, endRecordOffset(bytes) + 4, 1)
		assertFailsWith<ZipFormatException> { ZipArchive.read(bytes) }
	}

	/**
	 * A central directory whose entry count disagrees with its byte size fails.
	 */
	@Test
	fun entryCountThatLiesFails() {
		val bytes = archiveOf(Triple("first", compressible, false), Triple("second", compressible, false))
		val endOffset = endRecordOffset(bytes)
		writeU16Le(bytes, endOffset + 8, 1)
		writeU16Le(bytes, endOffset + 10, 1)
		assertFailsWith<ZipFormatException> { ZipArchive.read(bytes) }
	}
}