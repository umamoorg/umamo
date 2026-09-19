package org.umamo.format.cmo3.caff

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import okio.Buffer
import org.umamo.format.binary.ByteReader
import org.umamo.format.binary.Crc32
import org.umamo.format.binary.ZipRecords
import org.umamo.format.binary.deflateRawDeflate
import org.umamo.format.binary.inflateRawDeflate
import kotlin.time.Clock

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

	// ZIP: general-purpose flags. Bit 3 (0x08) says the CRC and sizes follow in a data descriptor
	// rather than the local header; bit 11 (0x0800) declares the name UTF-8. The editor sets both
	// (measured: 0x0808), so a real .cmo3 carries zeroed sizes up front — a reader that trusts them
	// reads nothing.
	private const val FLAGS = ZipRecords.FLAG_DATA_DESCRIPTOR or ZipRecords.FLAG_UTF8_NAME

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
		require(zipStream.size >= ZipRecords.LOCAL_HEADER_SIZE) { "Empty CAFF zip stream" }
		val reader = ByteReader(zipStream, littleEndian = true)
		require(reader.u32(0) == ZipRecords.LOCAL_HEADER_SIGNATURE.toLong()) { "CAFF blob is not a zip local file header" }
		// ZIP: local header @ +0x1A name length, @ +0x1C extra length; the payload follows both.
		val nameLength = reader.u16(26)
		val extraLength = reader.u16(28)
		val payloadStart = ZipRecords.LOCAL_HEADER_SIZE + nameLength + extraLength
		require(payloadStart <= zipStream.size) { "Truncated CAFF zip local header" }
		return inflateRawDeflate(zipStream, payloadStart, zipStream.size - payloadStart, declaredSize(reader))
	}

	/**
	 * The uncompressed size the blob's trailing data descriptor declares, else [Int.MAX_VALUE].
	 *
	 * This is the only output bound the format offers.  The CAFF file table's storedSize delimits the
	 * INPUT — compressed bytes — and says nothing about how far they inflate: the corpus blobs expand
	 * 3.5x to 6.7x, but DEFLATE reaches ~1000:1, so a crafted entry could otherwise balloon toward
	 * gigabytes in memory before anyone notices.  A blob ends at its data descriptor (CMO3.md §1 has
	 * the measured layout and the per-sample numbers), and that record's usize field is exactly the
	 * bound wanted.
	 *
	 * Treated as a cap, never a promise: a descriptor that lies truncates only its own entry, and
	 * anything unrecognizable falls back to unbounded rather than corrupting an otherwise valid read.
	 *
	 * @param ByteReader reader The full blob, little-endian.
	 * @return Int The declared uncompressed size, or Int.MAX_VALUE when no usable descriptor is found.
	 */
	private fun declaredSize(reader: ByteReader): Int {
		val blobSize = reader.bytes.size
		if (blobSize < ZipRecords.LOCAL_HEADER_SIZE + ZipRecords.DATA_DESCRIPTOR_SIZE) {
			return Int.MAX_VALUE
		}
		val descriptorStart = blobSize - ZipRecords.DATA_DESCRIPTOR_SIZE
		if (reader.u32(descriptorStart) != ZipRecords.DATA_DESCRIPTOR_SIGNATURE.toLong()) {
			return Int.MAX_VALUE
		}
		// ZIP: data descriptor @ +0x0C uncompressed size. Zero is what the LOCAL header carries, so a
		// zero here reads as "not really a descriptor"; past Int range needs zip64 and cannot be a
		// ByteArray length anyway. Both fall back rather than truncate.
		val declared = reader.u32(descriptorStart + 12)
		return if (declared in 1..Int.MAX_VALUE.toLong()) declared.toInt() else Int.MAX_VALUE
	}

	/**
	 * Wraps [contents] in a single-entry ("contents") zip stream at the given level.
	 *
	 * @param ByteArray contents  The raw payload to compress.
	 * @param Int       level     DEFLATE level (CompressOption.zipLevel).
	 * @param Int dosDateTime     The MS-DOS modification stamp: date in the high 16 bits, time in the
	 *                            low 16.  Defaults to the local wall clock, which is what the editor
	 *                            writes; injectable so tests can assert on the emitted bytes.
	 * @return ByteArray The zip stream bytes (before any obfuscation).
	 */
	fun zipSingle(contents: ByteArray, level: Int, dosDateTime: Int = currentDosDateTime()): ByteArray {
		val deflated = deflateRawDeflate(contents, level)
		val checksum = Crc32().also { it.update(contents) }.value.toInt()
		val out = Buffer()

		// ZIP: local file header (APPNOTE.TXT 4.3.7).
		// https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
		out.writeIntLe(ZipRecords.LOCAL_HEADER_SIGNATURE)
		// ZIP: version 2.0 — the minimum that understands DEFLATE, and what Java writes.
		out.writeShortLe(ZipRecords.VERSION_DEFLATED)
		out.writeShortLe(FLAGS)
		out.writeShortLe(ZipRecords.METHOD_DEFLATED)
		// ZIP: local header @ +0x0A time, @ +0x0C date. Both little-endian 16-bit, in LOCAL time.
		out.writeShortLe(dosDateTime and 0xFFFF)
		out.writeShortLe((dosDateTime ushr 16) and 0xFFFF)
		// Zeroed here and carried in the data descriptor instead — that is what flag bit 3 means.
		out.writeIntLe(0)
		out.writeIntLe(0)
		out.writeIntLe(0)
		out.writeShortLe(ENTRY_NAME.size)
		out.writeShortLe(0)
		out.write(ENTRY_NAME)

		out.write(deflated)

		// ZIP: data descriptor (APPNOTE.TXT 4.3.9) — the real CRC and sizes.
		out.writeIntLe(ZipRecords.DATA_DESCRIPTOR_SIGNATURE)
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
	 * The local wall clock now, as an MS-DOS date/time pair.
	 *
	 * Local, not UTC: a DOS timestamp has no zone, and the editor writes local time — matching it is
	 * the point of stamping a clock here at all.
	 *
	 * @return Int The stamp: date in the high 16 bits, time in the low 16.
	 */
	private fun currentDosDateTime(): Int {
		val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
		return ZipRecords.dosDateTimeOf(now.year, now.month.number, now.day, now.hour, now.minute, now.second)
	}
}