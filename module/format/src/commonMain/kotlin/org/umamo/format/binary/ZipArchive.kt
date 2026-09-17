package org.umamo.format.binary

/*
 * A strict reader for ordinary multi-entry ZIP archives, in commonMain so every target reads the same
 * bytes the same way: the UMA container and the KRA reader both stand on it.
 *
 * Strict where the shared DEFLATE seam is deliberately lenient.  inflateRawDeflate recovers what it can
 * from a damaged stream, which suits a half-readable image; an archive entry instead has a declared size
 * and CRC-32, and this reader holds every entry to both, so a truncated, corrupt, or lying archive fails
 * loudly (docs/format/UMA.md §1).
 */

/**
 * A malformed or unsupported ZIP archive, or an entry whose bytes do not match what the archive declares.
 *
 * @param String message What was wrong, naming the entry when there is one.
 */
internal class ZipFormatException(message: String) : RuntimeException(message)

/**
 * One entry as the central directory declares it.
 *
 * @property String name             The entry name, decoded as UTF-8.
 * @property Int    method           The compression method (APPNOTE.TXT 4.4.5).
 * @property Int    flags            The general purpose bit flags (APPNOTE.TXT 4.4.4).
 * @property Long   crc32            The CRC-32 of the uncompressed bytes, unsigned.
 * @property Int    compressedSize   The payload's size on disk.
 * @property Int    uncompressedSize The entry's size once inflated.
 * @property Int    dosDateTime      The MS-DOS modification stamp: date in the high 16 bits, time in the low 16.
 * @property Int    payloadOffset    Where the payload starts in the archive's bytes.
 */
internal class ZipEntry(
	val name: String,
	val method: Int,
	val flags: Int,
	val crc32: Long,
	val compressedSize: Int,
	val uncompressedSize: Int,
	val dosDateTime: Int,
	val payloadOffset: Int,
) {
	/** True for a directory entry, which ZIP marks with a trailing slash (APPNOTE.TXT 4.4.17.1). */
	val isDirectory: Boolean
		get() = name.endsWith('/')

	/** True when the entry is encrypted, which this reader cannot inflate. */
	val isEncrypted: Boolean
		get() = (flags and ZipRecords.FLAG_ENCRYPTED) != 0
}

/**
 * A parsed ZIP archive over its bytes: the central directory read eagerly, each entry's payload only on
 * request.
 *
 * Lazy payloads are what copy-through needs - a writer can carry an entry it never inflates
 * ([rawPayload]) - and what lets a caller read two entries of a large archive without inflating the rest.
 *
 * @property List<ZipEntry> entries Every entry, in central-directory order.
 */
internal class ZipArchive private constructor(
	private val bytes: ByteArray,
	val entries: List<ZipEntry>,
) {
	private val entryByName: Map<String, ZipEntry> = entries.associateBy { entry -> entry.name }

	/**
	 * The entry named [name], or null when the archive has none.
	 *
	 * @param String name The entry name.
	 * @return ZipEntry? The entry.
	 */
	fun entry(name: String): ZipEntry? = entryByName[name]

	/**
	 * The entry's uncompressed bytes, verified against the sizes and CRC-32 the archive declares.
	 *
	 * The inflate is bounded one byte past the declared size, so an entry that would inflate further
	 * stops there and fails rather than allocating what it claims not to hold.
	 *
	 * @param ZipEntry entry An entry of this archive.
	 * @return ByteArray The uncompressed bytes.
	 * @throws ZipFormatException When the entry is encrypted, uses a method other than stored or DEFLATE,
	 *   or its bytes do not match its declared size or CRC-32.
	 */
	fun contents(entry: ZipEntry): ByteArray = decodeZipPayload(entry, bytes, entry.payloadOffset)

	/**
	 * The entry's payload exactly as stored - compressed, and encrypted if it is - for copying into another
	 * archive without inflating it.
	 *
	 * @param ZipEntry entry An entry of this archive.
	 * @return ByteArray The payload bytes.
	 */
	fun rawPayload(entry: ZipEntry): ByteArray = bytes.copyOfRange(entry.payloadOffset, entry.payloadOffset + entry.compressedSize)

	companion object {
		/** The largest size a ByteArray can hold on every target, and so the largest entry or archive read. */
		private const val MAXIMUM_ARRAY_SIZE = Int.MAX_VALUE - 8

		/**
		 * Parses [bytes] as a ZIP archive: finds the end record, follows Zip64 records where the classic
		 * fields overflow, walks the central directory, and validates every entry's local header.
		 *
		 * @param ByteArray bytes The whole archive.
		 * @return ZipArchive The parsed archive; payloads are read on request.
		 * @throws ZipFormatException When the archive is truncated, malformed, split across disks, or holds
		 *   two entries with the same name.
		 */
		fun read(bytes: ByteArray): ZipArchive {
			val reader = ByteReader(bytes, littleEndian = true)
			val directory = locateCentralDirectory(reader)
			val entries = ArrayList<ZipEntry>(directory.entryCount)
			val names = HashSet<String>(directory.entryCount)
			var position = directory.offset
			val directoryEnd = directory.offset + directory.size
			repeat(directory.entryCount) { entryIndex ->
				val entry = readCentralEntry(reader, position, directoryEnd, directory.offset, entryIndex)
				if (!names.add(entry.first.name)) {
					throw ZipFormatException("the archive holds two entries named '${entry.first.name}'")
				}
				entries += entry.first
				position = entry.second
			}
			if (position != directoryEnd) {
				throw ZipFormatException("the central directory declares ${directory.size} bytes but its ${directory.entryCount} entries span ${position - directory.offset}")
			}
			return ZipArchive(bytes, entries)
		}

		/**
		 * Where the central directory is and how many entries it holds.
		 *
		 * @property Int offset     The directory's first byte.
		 * @property Int size       The directory's length in bytes.
		 * @property Int entryCount The number of entries.
		 */
		private class CentralDirectory(
			val offset: Int,
			val size: Int,
			val entryCount: Int,
		)

		/**
		 * Finds the end of central directory record and, through it, the central directory.
		 *
		 * The record has a variable-length comment, so it is found by scanning the tail of the file.  A
		 * candidate counts only when its comment ends exactly at the end of the file and the directory it
		 * describes is really there.  A comment can hold bytes that look like an end record, and one at the
		 * very end even satisfies the comment rule, so the EARLIEST valid candidate wins: the genuine record
		 * precedes any look-alike its own comment contains.
		 *
		 * @param ByteReader reader The archive, little-endian.
		 * @return CentralDirectory The directory's extent and entry count.
		 */
		private fun locateCentralDirectory(reader: ByteReader): CentralDirectory {
			val size = reader.bytes.size
			if (size < ZipRecords.END_SIZE) {
				throw ZipFormatException("$size bytes is too short to be a ZIP archive")
			}
			val lowestCandidate = maxOf(0, size - ZipRecords.END_SIZE - ZipRecords.MAXIMUM_COMMENT_LENGTH)
			var earliestDirectory: CentralDirectory? = null
			var candidate = size - ZipRecords.END_SIZE
			while (candidate >= lowestCandidate) {
				if (reader.u32(candidate) == ZipRecords.END_SIGNATURE.toLong() &&
					// ZIP: end record @ +0x14 comment length (APPNOTE.TXT 4.3.16).
					candidate + ZipRecords.END_SIZE + reader.u16(candidate + 20) == size
				) {
					centralDirectoryFromEnd(reader, candidate)?.let { directory -> earliestDirectory = directory }
				}
				candidate--
			}
			return earliestDirectory ?: throw ZipFormatException("no end of central directory record: the archive is truncated or not a ZIP")
		}

		/**
		 * The central directory an end record describes, or null when the record does not describe a
		 * directory this archive actually holds (a look-alike inside a comment).
		 *
		 * @param ByteReader reader   The archive, little-endian.
		 * @param Int        endOffset The candidate end record's offset.
		 * @return CentralDirectory? The directory, or null for a false candidate.
		 */
		private fun centralDirectoryFromEnd(reader: ByteReader, endOffset: Int): CentralDirectory? {
			// ZIP: end of central directory record (APPNOTE.TXT 4.3.16) - @ +0x04 this disk, @ +0x06 the
			// directory's disk, @ +0x08 entries on this disk, @ +0x0A total entries, @ +0x0C directory
			// size, @ +0x10 directory offset.
			val diskNumber = reader.u16(endOffset + 4)
			val directoryDisk = reader.u16(endOffset + 6)
			val entriesOnDisk = reader.u16(endOffset + 8)
			val totalEntries = reader.u16(endOffset + 10)
			val directorySize = reader.u32(endOffset + 12)
			val directoryOffset = reader.u32(endOffset + 16)

			val usesZip64 =
				entriesOnDisk == ZipRecords.UINT16_SENTINEL ||
					totalEntries == ZipRecords.UINT16_SENTINEL ||
					directorySize == ZipRecords.UINT32_SENTINEL ||
					directoryOffset == ZipRecords.UINT32_SENTINEL
			val locatorOffset = endOffset - ZipRecords.ZIP64_LOCATOR_SIZE
			val hasLocator = locatorOffset >= 0 && reader.u32(locatorOffset) == ZipRecords.ZIP64_LOCATOR_SIGNATURE.toLong()

			if (usesZip64 && hasLocator) {
				return zip64CentralDirectory(reader, locatorOffset)
			}
			// A classic count of exactly 0xFFFF with no Zip64 records is a legal classic archive, read as is.
			if (diskNumber != 0 || directoryDisk != 0 || entriesOnDisk != totalEntries) {
				// Only a genuine end record can be declared multi-disk; a look-alike is skipped instead.
				return if (plausibleDirectory(reader, directoryOffset, directorySize, totalEntries.toLong(), endOffset)) {
					throw ZipFormatException("multi-disk (split) archives are not supported")
				} else {
					null
				}
			}
			if (!plausibleDirectory(reader, directoryOffset, directorySize, totalEntries.toLong(), endOffset)) {
				return null
			}
			return CentralDirectory(directoryOffset.toInt(), directorySize.toInt(), totalEntries)
		}

		/**
		 * The central directory a Zip64 end record describes.
		 *
		 * @param ByteReader reader        The archive, little-endian.
		 * @param Int        locatorOffset The Zip64 end of central directory locator's offset.
		 * @return CentralDirectory The directory.
		 */
		private fun zip64CentralDirectory(reader: ByteReader, locatorOffset: Int): CentralDirectory {
			// ZIP: Zip64 end of central directory locator (APPNOTE.TXT 4.3.15) - @ +0x04 the disk holding the
			// Zip64 end record, @ +0x08 its offset, @ +0x10 the total disk count.
			val recordDisk = reader.u32(locatorOffset + 4)
			val recordOffset = u64AsOffset(reader, locatorOffset + 8, "Zip64 end record offset")
			val totalDisks = reader.u32(locatorOffset + 16)
			if (recordDisk != 0L || totalDisks > 1L) {
				throw ZipFormatException("multi-disk (split) archives are not supported")
			}
			if (recordOffset > locatorOffset - ZipRecords.ZIP64_END_SIZE || reader.u32(recordOffset) != ZipRecords.ZIP64_END_SIGNATURE.toLong()) {
				throw ZipFormatException("the Zip64 locator points at no Zip64 end record")
			}
			// ZIP: Zip64 end of central directory record (APPNOTE.TXT 4.3.14) - @ +0x10 this disk, @ +0x14 the
			// directory's disk, @ +0x18 entries on this disk, @ +0x20 total entries, @ +0x28 directory size,
			// @ +0x30 directory offset.
			val diskNumber = reader.u32(recordOffset + 16)
			val directoryDisk = reader.u32(recordOffset + 20)
			val entriesOnDisk = u64AsOffset(reader, recordOffset + 24, "Zip64 entries on disk")
			val totalEntries = u64AsOffset(reader, recordOffset + 32, "Zip64 total entries")
			val directorySize = u64AsOffset(reader, recordOffset + 40, "Zip64 central directory size")
			val directoryOffset = u64AsOffset(reader, recordOffset + 48, "Zip64 central directory offset")
			if (diskNumber != 0L || directoryDisk != 0L || entriesOnDisk != totalEntries) {
				throw ZipFormatException("multi-disk (split) archives are not supported")
			}
			if (!plausibleDirectory(reader, directoryOffset.toLong(), directorySize.toLong(), totalEntries.toLong(), recordOffset)) {
				throw ZipFormatException("the Zip64 end record describes a central directory the archive does not hold")
			}
			return CentralDirectory(directoryOffset, directorySize, totalEntries)
		}

		/**
		 * Whether a declared central directory fits before its end record and starts where it should.
		 *
		 * @param ByteReader reader    The archive, little-endian.
		 * @param Long       offset    The declared directory offset.
		 * @param Long       size      The declared directory size.
		 * @param Long       entryCount The declared entry count.
		 * @param Int        limit     The offset the directory must end at or before (its end record).
		 * @return Boolean True when the declaration is consistent with the bytes.
		 */
		private fun plausibleDirectory(reader: ByteReader, offset: Long, size: Long, entryCount: Long, limit: Int): Boolean {
			if (offset < 0 || size < 0 || offset + size > limit) {
				return false
			}
			// An empty directory has no header to check, so it must sit exactly where its end record starts.
			if (entryCount == 0L) {
				return size == 0L && offset == limit.toLong()
			}
			return size >= ZipRecords.CENTRAL_HEADER_SIZE &&
				entryCount * ZipRecords.CENTRAL_HEADER_SIZE <= size &&
				reader.u32(offset.toInt()) == ZipRecords.CENTRAL_HEADER_SIGNATURE.toLong()
		}

		/**
		 * Reads one central directory file header and validates the local header it points at.
		 *
		 * @param ByteReader reader          The archive, little-endian.
		 * @param Int        position        The central header's offset.
		 * @param Int        directoryEnd    The central directory's end, which the header must not cross.
		 * @param Int        directoryOffset The central directory's start, which every payload must end before.
		 * @param Int        entryIndex      The entry's index, for error messages.
		 * @return Pair The entry and the offset of the next central header.
		 */
		private fun readCentralEntry(reader: ByteReader, position: Int, directoryEnd: Int, directoryOffset: Int, entryIndex: Int): Pair<ZipEntry, Int> {
			if (position + ZipRecords.CENTRAL_HEADER_SIZE > directoryEnd || reader.u32(position) != ZipRecords.CENTRAL_HEADER_SIGNATURE.toLong()) {
				throw ZipFormatException("central directory entry $entryIndex is truncated or missing its signature")
			}
			// ZIP: central directory file header (APPNOTE.TXT 4.3.12) - @ +0x08 flags, @ +0x0A method,
			// @ +0x0C time, @ +0x0E date, @ +0x10 crc-32, @ +0x14 compressed size, @ +0x18 uncompressed size,
			// @ +0x1C name length, @ +0x1E extra length, @ +0x20 comment length, @ +0x22 disk number start,
			// @ +0x2A local header offset, @ +0x2E the name.
			val flags = reader.u16(position + 8)
			val method = reader.u16(position + 10)
			val time = reader.u16(position + 12)
			val date = reader.u16(position + 14)
			val crc32 = reader.u32(position + 16)
			var compressedSize = reader.u32(position + 20)
			var uncompressedSize = reader.u32(position + 24)
			val nameLength = reader.u16(position + 28)
			val extraLength = reader.u16(position + 30)
			val commentLength = reader.u16(position + 32)
			var diskStart = reader.u16(position + 34).toLong()
			var localOffset = reader.u32(position + 42)
			val nameStart = position + ZipRecords.CENTRAL_HEADER_SIZE
			val extraStart = nameStart + nameLength
			val nextPosition = extraStart + extraLength + commentLength
			if (nextPosition > directoryEnd) {
				throw ZipFormatException("central directory entry $entryIndex runs past the end of the directory")
			}
			val name = decodeName(reader.bytes, nameStart, nameLength, entryIndex)

			// ZIP: Zip64 extended information extra field (APPNOTE.TXT 4.5.3) - present values follow in this
			// order, each only when its classic field holds the sentinel.
			var extraPosition = extraStart
			val extraEnd = extraStart + extraLength
			while (extraPosition + 4 <= extraEnd) {
				val headerId = reader.u16(extraPosition)
				val dataSize = reader.u16(extraPosition + 2)
				val dataStart = extraPosition + 4
				if (dataStart + dataSize > extraEnd) {
					throw ZipFormatException("entry '$name' has a truncated extra field")
				}
				if (headerId == ZipRecords.ZIP64_EXTRA_ID) {
					var fieldPosition = dataStart
					val dataEnd = dataStart + dataSize

					/**
					 * Reads the next 64-bit value of the Zip64 extra field.
					 *
					 * @param String what The field, for error messages.
					 * @return Long The value.
					 */
					fun nextValue(what: String): Long {
						if (fieldPosition + 8 > dataEnd) {
							throw ZipFormatException("entry '$name' has a Zip64 extra field too short for its $what")
						}
						return u64AsOffset(reader, fieldPosition, "entry '$name' $what").toLong().also { fieldPosition += 8 }
					}
					if (uncompressedSize == ZipRecords.UINT32_SENTINEL) {
						uncompressedSize = nextValue("uncompressed size")
					}
					if (compressedSize == ZipRecords.UINT32_SENTINEL) {
						compressedSize = nextValue("compressed size")
					}
					if (localOffset == ZipRecords.UINT32_SENTINEL) {
						localOffset = nextValue("local header offset")
					}
					if (diskStart == ZipRecords.UINT16_SENTINEL.toLong()) {
						if (fieldPosition + 4 > dataEnd) {
							throw ZipFormatException("entry '$name' has a Zip64 extra field too short for its disk number")
						}
						diskStart = reader.u32(fieldPosition)
					}
				}
				extraPosition = dataStart + dataSize
			}
			if (diskStart != 0L) {
				throw ZipFormatException("entry '$name' starts on another disk: multi-disk archives are not supported")
			}
			if (uncompressedSize > MAXIMUM_ARRAY_SIZE - 1 || compressedSize > MAXIMUM_ARRAY_SIZE) {
				throw ZipFormatException("entry '$name' is larger than a byte array can hold")
			}
			val payloadOffset = validateLocalHeader(reader, localOffset, name, nameStart, nameLength, method, directoryOffset)
			if (payloadOffset.toLong() + compressedSize > directoryOffset) {
				throw ZipFormatException("entry '$name' is truncated: its payload runs past the start of the central directory")
			}
			val entry =
				ZipEntry(
					name = name,
					method = method,
					flags = flags,
					crc32 = crc32,
					compressedSize = compressedSize.toInt(),
					uncompressedSize = uncompressedSize.toInt(),
					dosDateTime = (date shl 16) or time,
					payloadOffset = payloadOffset,
				)
			return entry to nextPosition
		}

		/**
		 * Validates the local file header an entry points at and returns where its payload starts.
		 *
		 * The payload offset comes from the LOCAL header's own name and extra lengths, which may differ from
		 * the central directory's copy (writers put different extra fields in each).
		 *
		 * @param ByteReader reader          The archive, little-endian.
		 * @param Long       localOffset     The local header's declared offset.
		 * @param String     name            The entry's name, for error messages.
		 * @param Int        nameStart       Where the central header's name bytes start.
		 * @param Int        nameLength      The central header's name length.
		 * @param Int        method          The central header's compression method.
		 * @param Int        directoryOffset The central directory's start.
		 * @return Int The payload's offset.
		 */
		private fun validateLocalHeader(
			reader: ByteReader,
			localOffset: Long,
			name: String,
			nameStart: Int,
			nameLength: Int,
			method: Int,
			directoryOffset: Int,
		): Int {
			if (localOffset < 0 || localOffset + ZipRecords.LOCAL_HEADER_SIZE > directoryOffset) {
				throw ZipFormatException("entry '$name' points at a local header outside the archive's entry data")
			}
			val headerOffset = localOffset.toInt()
			if (reader.u32(headerOffset) != ZipRecords.LOCAL_HEADER_SIGNATURE.toLong()) {
				throw ZipFormatException("entry '$name' points at bytes that are not a local file header")
			}
			// ZIP: local file header (APPNOTE.TXT 4.3.7) - @ +0x08 method, @ +0x1A name length, @ +0x1C extra
			// length, @ +0x1E the name.
			val localMethod = reader.u16(headerOffset + 8)
			val localNameLength = reader.u16(headerOffset + 26)
			val localExtraLength = reader.u16(headerOffset + 28)
			val localNameStart = headerOffset + ZipRecords.LOCAL_HEADER_SIZE
			if (localNameStart + localNameLength + localExtraLength > directoryOffset) {
				throw ZipFormatException("entry '$name' has a local header that runs into the central directory")
			}
			if (localMethod != method) {
				throw ZipFormatException("entry '$name' declares method $method centrally but $localMethod locally")
			}
			if (localNameLength != nameLength || !reader.bytes.rangeEquals(localNameStart, reader.bytes, nameStart, nameLength)) {
				throw ZipFormatException("entry '$name' has a local header naming a different entry")
			}
			return localNameStart + localNameLength + localExtraLength
		}

		/**
		 * Decodes an entry name as UTF-8, the encoding the writer flags and the one java.util.zip assumes.
		 *
		 * @param ByteArray bytes      The archive.
		 * @param Int       start      The name's first byte.
		 * @param Int       length     The name's length in bytes.
		 * @param Int       entryIndex The entry's index, for error messages.
		 * @return String The name.
		 */
		private fun decodeName(bytes: ByteArray, start: Int, length: Int, entryIndex: Int): String =
			try {
				bytes.decodeToString(start, start + length, throwOnInvalidSequence = true)
			} catch (_: CharacterCodingException) {
				throw ZipFormatException("central directory entry $entryIndex has a name that is not valid UTF-8")
			}

		/**
		 * Reads an unsigned little-endian 64-bit field that must fit a byte-array offset.
		 *
		 * @param ByteReader reader The archive, little-endian.
		 * @param Int        at     The field's offset.
		 * @param String     what   The field, for error messages.
		 * @return Int The value.
		 */
		private fun u64AsOffset(reader: ByteReader, at: Int, what: String): Int {
			val high = reader.u32(at + 4)
			val low = reader.u32(at)
			if (high != 0L || low > MAXIMUM_ARRAY_SIZE) {
				throw ZipFormatException("$what exceeds what a byte array can address")
			}
			return low.toInt()
		}
	}
}

/**
 * Whether [length] bytes of this array starting at [offset] equal those of [other] starting at [otherOffset].
 *
 * @param Int       offset      The first byte compared here.
 * @param ByteArray other       The array compared against.
 * @param Int       otherOffset The first byte compared there.
 * @param Int       length      How many bytes to compare.
 * @return Boolean True when every byte matches.
 */
private fun ByteArray.rangeEquals(offset: Int, other: ByteArray, otherOffset: Int, length: Int): Boolean {
	for (byteIndex in 0 until length) {
		if (this[offset + byteIndex] != other[otherOffset + byteIndex]) {
			return false
		}
	}
	return true
}

/**
 * An entry's uncompressed bytes from its payload wherever the payload is held - inside the archive it came
 * from, or copied out of it - verified against the sizes and CRC-32 the entry declares.
 *
 * The inflate is bounded one byte past the declared size, so a payload that would inflate further stops there
 * and fails rather than allocating what it claims not to hold.
 *
 * @param ZipEntry  entry         The entry as its archive declared it.
 * @param ByteArray source        The bytes holding the payload.
 * @param Int       payloadOffset Where the payload starts in [source].
 * @return ByteArray The uncompressed bytes.
 * @throws ZipFormatException When the entry is encrypted, uses a method other than stored or DEFLATE, its
 *   payload does not fit [source], or its bytes do not match its declared size or CRC-32.
 */
internal fun decodeZipPayload(entry: ZipEntry, source: ByteArray, payloadOffset: Int): ByteArray {
	if (entry.isEncrypted) {
		throw ZipFormatException("entry '${entry.name}' is encrypted")
	}
	if (payloadOffset < 0 || payloadOffset.toLong() + entry.compressedSize > source.size) {
		throw ZipFormatException("entry '${entry.name}' has a payload that does not fit its bytes")
	}
	val uncompressed =
		when (entry.method) {
			ZipRecords.METHOD_STORED -> {
				if (entry.compressedSize != entry.uncompressedSize) {
					throw ZipFormatException(
						"stored entry '${entry.name}' declares ${entry.compressedSize} bytes on disk but ${entry.uncompressedSize} uncompressed",
					)
				}
				source.copyOfRange(payloadOffset, payloadOffset + entry.compressedSize)
			}

			ZipRecords.METHOD_DEFLATED -> {
				// One byte of headroom is how an entry that inflates past its declared size is caught
				// without inflating the rest of it.
				inflateRawDeflate(source, payloadOffset, entry.compressedSize, entry.uncompressedSize + 1)
			}

			else -> throw ZipFormatException("entry '${entry.name}' uses unsupported compression method ${entry.method}")
		}
	if (uncompressed.size != entry.uncompressedSize) {
		throw ZipFormatException(
			"entry '${entry.name}' inflates to ${if (uncompressed.size > entry.uncompressedSize) "more than" else "only"} " +
				"${minOf(uncompressed.size, entry.uncompressedSize)} bytes, but declares ${entry.uncompressedSize}",
		)
	}
	val checksum = Crc32().also { crc -> crc.update(uncompressed) }.value
	if (checksum != entry.crc32) {
		throw ZipFormatException("entry '${entry.name}' fails its CRC-32 check")
	}
	return uncompressed
}