package org.umamo.format.cmo3.caff

import okio.Buffer
import org.umamo.format.binary.Crc32
import org.umamo.format.binary.deflateRawDeflate
import org.umamo.format.binary.inflateRawDeflate

/**
 * The single-entry zip framing CAFF wraps FAST/SMALL blobs in.
 *
 * A CAFF compressed blob is a PARTIAL zip stream holding one entry named "contents": a local file
 * header, a raw DEFLATE payload, a data descriptor, and then the end of the blob.  It carries no
 * central directory and no EOCD — measured on the corpus, not assumed (CMO3.md §1 has the byte
 * layout and the evidence).  So this is not `ZipOutputStream` output and cannot be produced by it:
 * that class always appends both records on close.  The framing is pure byte manipulation instead.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Compression</a>
 */
internal object CaffZip {
	// CMO3: the compressed blob is a single-entry zip stream; the entry is always named "contents".
	private val ENTRY_NAME = "contents".encodeToByteArray()

	// ZIP: the record signatures this framing uses (APPNOTE.TXT 4.3.7 / 4.3.9), little-endian on disk.
	private const val LOCAL_HEADER_SIGNATURE = 0x04034B50
	private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074B50

	// ZIP: the local header is 30 bytes before name+extra.
	private const val LOCAL_HEADER_SIZE = 30

	// ZIP: the data descriptor closing the blob is 16 bytes — signature, crc32, csize, usize.
	private const val DATA_DESCRIPTOR_SIZE = 16

	// ZIP: version 2.0 — the minimum that understands DEFLATE, and what Java writes.
	private const val VERSION = 20

	// ZIP: general-purpose flags. Bit 3 (0x08) says the CRC and sizes follow in a data descriptor
	// rather than the local header; bit 11 (0x0800) declares the name UTF-8. The editor sets both
	// (measured: 0x0808), so a real .cmo3 carries zeroed sizes up front — a reader that trusts them
	// reads nothing.
	private const val FLAGS = 0x0808

	// ZIP: compression method 8 = DEFLATE.
	private const val METHOD_DEFLATE = 8

	// ZIP: MS-DOS timestamp, pinned to the format's own epoch (1980-01-01 00:00). The editor stamps a
	// wall clock here instead (the corpus sample reads 2022-06-06 17:03:30), which is precisely why we
	// do not: readers ignore the field, and a constant is what makes re-saving the same model produce
	// the same bytes. A deliberate divergence from the editor's bytes — see CMO3.md §1.
	private const val DOS_TIME = 0x0000
	private const val DOS_DATE = 0x0021

	/**
	 * Inflates a single-entry CAFF zip stream back to the raw payload.
	 *
	 * Reads only what the framing guarantees: the local header's name/extra lengths locate the
	 * payload, and the DEFLATE stream ends itself.  The sizes in the local header are ignored because
	 * the editor zeroes them (flag bit 3); the real ones are in the trailing data descriptor, which is
	 * data the inflater simply stops before.
	 *
	 * @param ByteArray zipStream The full zip stream bytes (already de-obfuscated).
	 * @return ByteArray The decompressed "contents" payload.
	 */
	fun unzipSingle(zipStream: ByteArray): ByteArray {
		require(zipStream.size >= LOCAL_HEADER_SIZE) { "Empty CAFF zip stream" }
		require(readU32(zipStream, 0) == LOCAL_HEADER_SIGNATURE.toLong()) { "CAFF blob is not a zip local file header" }
		// ZIP: local header @ +0x1A name length, @ +0x1C extra length; the payload follows both.
		val nameLength = readU16(zipStream, 26)
		val extraLength = readU16(zipStream, 28)
		val payloadStart = LOCAL_HEADER_SIZE + nameLength + extraLength
		require(payloadStart <= zipStream.size) { "Truncated CAFF zip local header" }
		return inflateRawDeflate(zipStream, payloadStart, zipStream.size - payloadStart, declaredSize(zipStream))
	}

	/**
	 * The uncompressed size the blob's trailing data descriptor declares, else [Int.MAX_VALUE].
	 *
	 * This is the only output bound the format offers.  The CAFF file table's storedSize delimits the
	 * INPUT — compressed bytes — and says nothing about how far they inflate: the corpus blob expands
	 * 3.5x, but DEFLATE reaches ~1000:1, so a crafted entry could otherwise balloon toward gigabytes
	 * in memory before anyone notices.  A blob ends at its data descriptor (CMO3.md §1 has the
	 * measured layout), and that record's usize field is exactly the bound wanted.
	 *
	 * Treated as a cap, never a promise: a descriptor that lies truncates only its own entry, and
	 * anything unrecognizable falls back to unbounded rather than corrupting an otherwise valid read.
	 *
	 * @param ByteArray zipStream The full blob.
	 * @return Int The declared uncompressed size, or Int.MAX_VALUE when no usable descriptor is found.
	 */
	private fun declaredSize(zipStream: ByteArray): Int {
		if (zipStream.size < LOCAL_HEADER_SIZE + DATA_DESCRIPTOR_SIZE) {
			return Int.MAX_VALUE
		}
		val descriptorStart = zipStream.size - DATA_DESCRIPTOR_SIZE
		if (readU32(zipStream, descriptorStart) != DATA_DESCRIPTOR_SIGNATURE.toLong()) {
			return Int.MAX_VALUE
		}
		// ZIP: data descriptor @ +0x0C uncompressed size. Zero is what the LOCAL header carries, so a
		// zero here reads as "not really a descriptor"; past Int range needs zip64 and cannot be a
		// ByteArray length anyway. Both fall back rather than truncate.
		val declared = readU32(zipStream, descriptorStart + 12)
		return if (declared in 1..Int.MAX_VALUE.toLong()) declared.toInt() else Int.MAX_VALUE
	}

	/**
	 * Wraps [contents] in a single-entry ("contents") zip stream at the given level.
	 *
	 * @param ByteArray contents The raw payload to compress.
	 * @param Int       level    DEFLATE level (CompressOption.zipLevel).
	 * @return ByteArray The zip stream bytes (before any obfuscation).
	 */
	fun zipSingle(contents: ByteArray, level: Int): ByteArray {
		val deflated = deflateRawDeflate(contents, level)
		val checksum = Crc32().also { it.update(contents) }.value.toInt()
		val out = Buffer()

		// ZIP: local file header (APPNOTE.TXT 4.3.7).
		// https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
		out.writeIntLe(LOCAL_HEADER_SIGNATURE)
		out.writeShortLe(VERSION)
		out.writeShortLe(FLAGS)
		out.writeShortLe(METHOD_DEFLATE)
		out.writeShortLe(DOS_TIME)
		out.writeShortLe(DOS_DATE)
		// Zeroed here and carried in the data descriptor instead — that is what flag bit 3 means.
		out.writeIntLe(0)
		out.writeIntLe(0)
		out.writeIntLe(0)
		out.writeShortLe(ENTRY_NAME.size)
		out.writeShortLe(0)
		out.write(ENTRY_NAME)

		out.write(deflated)

		// ZIP: data descriptor (APPNOTE.TXT 4.3.9) — the real CRC and sizes.
		out.writeIntLe(DATA_DESCRIPTOR_SIGNATURE)
		out.writeIntLe(checksum)
		out.writeIntLe(deflated.size)
		out.writeIntLe(contents.size)

		// Ends here — no central directory, no end-of-central-directory record. That is not an
		// omission, it is what a CAFF blob measurably is: de-obfuscating the corpus sample finds one
		// PK\x03\x04 and one PK\x07\x08, and zero of either record (CMO3.md §1). Re-emitting without
		// them reproduces the original's exact file size, which is the same fact from the other side.
		// Writing them would append 76 bytes the format does not carry, growing the file on each save
		// — and is why this cannot be ZipOutputStream, which emits both unconditionally on close.
		return out.readByteArray()
	}

	/**
	 * Reads a little-endian unsigned 16-bit value.
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the low byte.
	 * @return Int The value in 0..65535.
	 */
	private fun readU16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

	/**
	 * Reads a little-endian unsigned 32-bit value as a Long (so signatures compare unsigned).
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the low byte.
	 * @return Long The value in 0..4294967295.
	 */
	private fun readU32(bytes: ByteArray, at: Int): Long =
		(bytes[at].toLong() and 0xFF) or
			((bytes[at + 1].toLong() and 0xFF) shl 8) or
			((bytes[at + 2].toLong() and 0xFF) shl 16) or
			((bytes[at + 3].toLong() and 0xFF) shl 24)
}
