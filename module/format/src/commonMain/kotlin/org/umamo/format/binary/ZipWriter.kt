package org.umamo.format.binary

import okio.Buffer

/**
 * Builds an ordinary multi-entry ZIP archive in memory, in insertion order.
 *
 * Every entry's sizes and CRC-32 are known before it is written, so each local header carries them and
 * no data descriptor follows.  No extra fields are written, which is what fixes a first stored entry's
 * payload at offset 30 plus its name length - the UMA mimetype probe relies on it (docs/format/UMA.md §1).
 * Output is a pure function of the entries added and [dosDateTime]: no wall clock, no host attributes.
 *
 * @param Int dosDateTime The MS-DOS modification stamp every entry carries: date in the high 16 bits,
 *   time in the low 16 (see ZipRecords.dosDateTimeOf).  A parameter rather than a clock read, so the
 *   caller decides whether archives are byte-reproducible.
 */
internal class ZipWriter(private val dosDateTime: Int) {
	/**
	 * What the central directory needs to repeat about one written entry.
	 *
	 * @property ByteArray nameBytes        The UTF-8 name.
	 * @property Int       versionNeeded    The "version needed to extract".
	 * @property Int       flags            The general purpose bit flags.
	 * @property Int       method           The compression method.
	 * @property Long      crc32            The CRC-32 of the uncompressed bytes.
	 * @property Int       compressedSize   The payload's size.
	 * @property Int       uncompressedSize The uncompressed size.
	 * @property Long      localHeaderOffset Where the entry's local header starts.
	 */
	private class WrittenEntry(
		val nameBytes: ByteArray,
		val versionNeeded: Int,
		val flags: Int,
		val method: Int,
		val crc32: Long,
		val compressedSize: Int,
		val uncompressedSize: Int,
		val localHeaderOffset: Long,
	)

	private val output = Buffer()
	private val writtenEntries = ArrayList<WrittenEntry>()
	private val writtenNames = HashSet<String>()
	private var finished = false

	/**
	 * Adds an entry stored without compression.
	 *
	 * @param String    name     The entry name; a trailing slash makes it a directory entry.
	 * @param ByteArray contents The entry's bytes.
	 */
	fun addStored(name: String, contents: ByteArray) {
		writeEntry(
			name = name,
			method = ZipRecords.METHOD_STORED,
			flags = ZipRecords.FLAG_UTF8_NAME,
			crc32 = crc32Of(contents),
			uncompressedSize = contents.size,
			payload = contents,
		)
	}

	/**
	 * Adds an entry compressed with DEFLATE.
	 *
	 * @param String    name     The entry name.
	 * @param ByteArray contents The entry's uncompressed bytes.
	 * @param Int       level    The DEFLATE level, 0..9.
	 */
	fun addDeflated(name: String, contents: ByteArray, level: Int = DEFAULT_DEFLATE_LEVEL) {
		require(level in 0..9) { "DEFLATE level must be in 0..9: $level" }
		writeEntry(
			name = name,
			method = ZipRecords.METHOD_DEFLATED,
			flags = ZipRecords.FLAG_UTF8_NAME,
			crc32 = crc32Of(contents),
			uncompressedSize = contents.size,
			payload = deflateRawDeflate(contents, level),
		)
	}

	/**
	 * Adds an entry copied from another archive without inflating or recompressing it: the method, CRC-32,
	 * sizes, encryption flag, and payload bytes carry over verbatim.  The name and timestamp are this
	 * writer's, like every other entry.
	 *
	 * @param ZipEntry  entry      The source entry, as its archive declares it.
	 * @param ByteArray rawPayload The source entry's payload exactly as stored (ZipArchive.rawPayload).
	 */
	fun addRaw(entry: ZipEntry, rawPayload: ByteArray) {
		require(rawPayload.size == entry.compressedSize) {
			"entry '${entry.name}' declares ${entry.compressedSize} payload bytes but ${rawPayload.size} were given"
		}
		// A copied entry's sizes now sit in its local header, so a data descriptor flag from its source would
		// send a reader looking for a record that is not there.
		val flags = (entry.flags and ZipRecords.FLAG_DATA_DESCRIPTOR.inv()) or ZipRecords.FLAG_UTF8_NAME
		writeEntry(
			name = entry.name,
			method = entry.method,
			flags = flags,
			crc32 = entry.crc32,
			uncompressedSize = entry.uncompressedSize,
			payload = rawPayload,
		)
	}

	/**
	 * Writes the central directory and the end records and returns the archive.  The writer takes no
	 * entries afterward.
	 *
	 * Zip64 end records are added only when the entry count reaches 0xFFFF, where the classic 16-bit count
	 * field runs out (its all-ones value is reserved to mean "see Zip64").  Sizes and offsets cannot
	 * overflow their 32-bit fields: the archive is a byte array, so it is under 2 GiB by construction.
	 *
	 * @return ByteArray The archive.
	 */
	fun finish(): ByteArray {
		check(!finished) { "the archive has already been finished" }
		finished = true
		val directoryOffset = output.size
		for (entry in writtenEntries) {
			writeCentralHeader(entry)
		}
		val directorySize = output.size - directoryOffset
		val entryCount = writtenEntries.size
		val needsZip64 = entryCount >= ZipRecords.UINT16_SENTINEL
		if (needsZip64) {
			val zip64EndOffset = output.size
			// ZIP: Zip64 end of central directory record (APPNOTE.TXT 4.3.14).
			output.writeIntLe(ZipRecords.ZIP64_END_SIGNATURE)
			output.writeLongLe(ZipRecords.ZIP64_END_RECORD_REMAINDER.toLong())
			output.writeShortLe(ZipRecords.VERSION_ZIP64)
			output.writeShortLe(ZipRecords.VERSION_ZIP64)
			output.writeIntLe(0)
			output.writeIntLe(0)
			output.writeLongLe(entryCount.toLong())
			output.writeLongLe(entryCount.toLong())
			output.writeLongLe(directorySize)
			output.writeLongLe(directoryOffset)
			// ZIP: Zip64 end of central directory locator (APPNOTE.TXT 4.3.15).
			output.writeIntLe(ZipRecords.ZIP64_LOCATOR_SIGNATURE)
			output.writeIntLe(0)
			output.writeLongLe(zip64EndOffset)
			output.writeIntLe(1)
		}
		val classicCount = if (needsZip64) ZipRecords.UINT16_SENTINEL else entryCount
		// ZIP: end of central directory record (APPNOTE.TXT 4.3.16).
		output.writeIntLe(ZipRecords.END_SIGNATURE)
		output.writeShortLe(0)
		output.writeShortLe(0)
		output.writeShortLe(classicCount)
		output.writeShortLe(classicCount)
		output.writeIntLe(directorySize.toInt())
		output.writeIntLe(directoryOffset.toInt())
		output.writeShortLe(0)
		check(output.size <= MAXIMUM_ARCHIVE_SIZE) { "the archive exceeds what a byte array can hold: ${output.size} bytes" }
		return output.readByteArray()
	}

	/**
	 * Writes one entry's local header and payload, and records what its central header will repeat.
	 *
	 * @param String    name             The entry name.
	 * @param Int       method           The compression method.
	 * @param Int       flags            The general purpose bit flags.
	 * @param Long      crc32            The CRC-32 of the uncompressed bytes.
	 * @param Int       uncompressedSize The uncompressed size.
	 * @param ByteArray payload          The bytes to store.
	 */
	private fun writeEntry(name: String, method: Int, flags: Int, crc32: Long, uncompressedSize: Int, payload: ByteArray) {
		check(!finished) { "the archive has already been finished" }
		require(name.isNotEmpty()) { "an entry name cannot be empty" }
		require(writtenNames.add(name)) { "the archive already holds an entry named '$name'" }
		val nameBytes = name.encodeToByteArray()
		require(nameBytes.size <= 0xFFFF) { "entry name '$name' is longer than a ZIP header can hold" }
		val versionNeeded = if (method == ZipRecords.METHOD_STORED) ZipRecords.VERSION_STORED else ZipRecords.VERSION_DEFLATED
		val entry =
			WrittenEntry(
				nameBytes = nameBytes,
				versionNeeded = versionNeeded,
				flags = flags,
				method = method,
				crc32 = crc32,
				compressedSize = payload.size,
				uncompressedSize = uncompressedSize,
				localHeaderOffset = output.size,
			)
		// ZIP: local file header (APPNOTE.TXT 4.3.7).
		output.writeIntLe(ZipRecords.LOCAL_HEADER_SIGNATURE)
		output.writeShortLe(entry.versionNeeded)
		output.writeShortLe(entry.flags)
		output.writeShortLe(entry.method)
		// ZIP: @ +0x0A time, @ +0x0C date.
		output.writeShortLe(dosDateTime and 0xFFFF)
		output.writeShortLe((dosDateTime ushr 16) and 0xFFFF)
		output.writeIntLe(entry.crc32.toInt())
		output.writeIntLe(entry.compressedSize)
		output.writeIntLe(entry.uncompressedSize)
		output.writeShortLe(nameBytes.size)
		output.writeShortLe(0)
		output.write(nameBytes)
		output.write(payload)
		check(output.size <= MAXIMUM_ARCHIVE_SIZE) { "the archive exceeds what a byte array can hold: ${output.size} bytes" }
		writtenEntries += entry
	}

	/**
	 * Writes one entry's central directory file header.
	 *
	 * @param WrittenEntry entry The entry.
	 */
	private fun writeCentralHeader(entry: WrittenEntry) {
		// ZIP: central directory file header (APPNOTE.TXT 4.3.12).  "Version made by" is 2.0 on an MS-DOS
		// host with zero attributes, so nothing about the machine that wrote the archive leaks into it.
		output.writeIntLe(ZipRecords.CENTRAL_HEADER_SIGNATURE)
		output.writeShortLe(ZipRecords.VERSION_DEFLATED)
		output.writeShortLe(entry.versionNeeded)
		output.writeShortLe(entry.flags)
		output.writeShortLe(entry.method)
		output.writeShortLe(dosDateTime and 0xFFFF)
		output.writeShortLe((dosDateTime ushr 16) and 0xFFFF)
		output.writeIntLe(entry.crc32.toInt())
		output.writeIntLe(entry.compressedSize)
		output.writeIntLe(entry.uncompressedSize)
		output.writeShortLe(entry.nameBytes.size)
		// ZIP: @ +0x1E extra length, @ +0x20 comment length, @ +0x22 disk number start, @ +0x24 internal
		// attributes - all zero.
		output.writeShortLe(0)
		output.writeShortLe(0)
		output.writeShortLe(0)
		output.writeShortLe(0)
		// ZIP: @ +0x26 external attributes, zero.
		output.writeIntLe(0)
		output.writeIntLe(entry.localHeaderOffset.toInt())
		output.write(entry.nameBytes)
	}

	private companion object {
		/** zlib's default level, spelled out so the writer's output never depends on a library default. */
		const val DEFAULT_DEFLATE_LEVEL = 6

		/** The largest archive a ByteArray can hold on every target. */
		const val MAXIMUM_ARCHIVE_SIZE = Int.MAX_VALUE - 8L

		/**
		 * The CRC-32 of [bytes].
		 *
		 * @param ByteArray bytes The bytes.
		 * @return Long The checksum, unsigned.
		 */
		fun crc32Of(bytes: ByteArray): Long = Crc32().also { crc -> crc.update(bytes) }.value
	}
}