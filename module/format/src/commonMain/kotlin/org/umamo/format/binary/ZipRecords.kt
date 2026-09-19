package org.umamo.format.binary

/*
 * The ZIP record layout shared by the codecs that frame ZIP data: the CAFF container's single-entry
 * blobs (CaffZip) and ordinary multi-entry archives (ZipArchive, ZipWriter).  Constants only - each
 * codec keeps its own policy (which flags it sets, whether it writes a data descriptor, which clock it
 * stamps).
 *
 * Citations are to PKWARE's APPNOTE.TXT: https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT
 */

/**
 * ZIP record signatures, sizes, and field values, as APPNOTE.TXT defines them.  Every multi-byte field
 * on disk is little-endian.
 */
internal object ZipRecords {
	// ZIP: local file header signature (APPNOTE.TXT 4.3.7).
	const val LOCAL_HEADER_SIGNATURE = 0x04034B50

	// ZIP: data descriptor signature (APPNOTE.TXT 4.3.9.3; optional on disk, and what CAFF writes).
	const val DATA_DESCRIPTOR_SIGNATURE = 0x08074B50

	// ZIP: central directory file header signature (APPNOTE.TXT 4.3.12).
	const val CENTRAL_HEADER_SIGNATURE = 0x02014B50

	// ZIP: Zip64 end of central directory record signature (APPNOTE.TXT 4.3.14).
	const val ZIP64_END_SIGNATURE = 0x06064B50

	// ZIP: Zip64 end of central directory locator signature (APPNOTE.TXT 4.3.15).
	const val ZIP64_LOCATOR_SIGNATURE = 0x07064B50

	// ZIP: end of central directory record signature (APPNOTE.TXT 4.3.16).
	const val END_SIGNATURE = 0x06054B50

	// ZIP: fixed sizes before each record's variable-length fields (APPNOTE.TXT 4.3.7, 4.3.12, 4.3.14,
	// 4.3.15, 4.3.16, 4.3.9.3).
	const val LOCAL_HEADER_SIZE = 30
	const val CENTRAL_HEADER_SIZE = 46
	const val ZIP64_END_SIZE = 56
	const val ZIP64_LOCATOR_SIZE = 20
	const val END_SIZE = 22
	const val DATA_DESCRIPTOR_SIZE = 16

	// ZIP: the Zip64 end record's "size of zip64 end of central directory record" excludes its own
	// leading 12 bytes (APPNOTE.TXT 4.3.14.1).
	const val ZIP64_END_RECORD_REMAINDER = ZIP64_END_SIZE - 12

	// ZIP: the end record's comment length is a 16-bit field (APPNOTE.TXT 4.3.16), which bounds how far
	// before the end of the file the record can start.
	const val MAXIMUM_COMMENT_LENGTH = 0xFFFF

	// ZIP: compression methods (APPNOTE.TXT 4.4.5) - 0 stored, 8 DEFLATE.
	const val METHOD_STORED = 0
	const val METHOD_DEFLATED = 8

	// ZIP: "version needed to extract" (APPNOTE.TXT 4.4.3.2) - 1.0 for a stored entry, 2.0 for DEFLATE,
	// 4.5 for Zip64 records.
	const val VERSION_STORED = 10
	const val VERSION_DEFLATED = 20
	const val VERSION_ZIP64 = 45

	// ZIP: general purpose bit flags (APPNOTE.TXT 4.4.4) - bit 0 encrypted, bit 3 sizes and CRC in a
	// trailing data descriptor, bit 11 the name is UTF-8.
	const val FLAG_ENCRYPTED = 0x0001
	const val FLAG_DATA_DESCRIPTOR = 0x0008
	const val FLAG_UTF8_NAME = 0x0800

	// ZIP: the Zip64 extended information extra field's header id (APPNOTE.TXT 4.5.3).
	const val ZIP64_EXTRA_ID = 0x0001

	// ZIP: a 16-bit count or 32-bit size/offset holding all ones means "see the Zip64 record"
	// (APPNOTE.TXT 4.4.1.4).
	const val UINT16_SENTINEL = 0xFFFF
	const val UINT32_SENTINEL = 0xFFFFFFFFL

	// MS-DOS timestamp bounds (APPNOTE.TXT 4.4.6). The date packs year-1980 into 7 bits, so the epoch is
	// 1980 and the ceiling is 2107; a clock outside that range is clamped rather than allowed to wrap into
	// a different year.
	private const val DOS_EPOCH_YEAR = 1980
	private const val DOS_MAXIMUM_YEAR = 2107

	// MS-DOS 1980-01-01 00:00 - the clamp floor, and what an out-of-range clock falls back to.
	private const val DOS_EPOCH_DATE = 0x0021
	private const val DOS_EPOCH_TIME = 0x0000

	/** MS-DOS 1980-01-01 00:00 as a packed stamp: the earliest instant a ZIP header can record. */
	const val DOS_EPOCH_DATE_TIME = (DOS_EPOCH_DATE shl 16) or DOS_EPOCH_TIME

	/**
	 * Packs a local date and time into the MS-DOS format ZIP headers use.
	 *
	 * Date: year-1980 in bits 15..9, month in 8..5, day in 4..0.  Time: hour in 15..11, minute in
	 * 10..5, and seconds HALVED into 4..0 - which is why a DOS stamp has two-second resolution.
	 *
	 * A year outside 1980..2107 cannot be expressed in the 7 bits available, so it clamps to the epoch
	 * rather than wrapping into a wrong-but-plausible year.
	 *
	 * @param Int year   Local year.
	 * @param Int month  Local month, 1..12.
	 * @param Int day    Local day of month, 1..31.
	 * @param Int hour   Local hour, 0..23.
	 * @param Int minute Local minute, 0..59.
	 * @param Int second Local second, 0..59.
	 * @return Int The stamp: date in the high 16 bits, time in the low 16.
	 */
	fun dosDateTimeOf(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Int {
		if (year < DOS_EPOCH_YEAR || year > DOS_MAXIMUM_YEAR) {
			return DOS_EPOCH_DATE_TIME
		}
		val date = ((year - DOS_EPOCH_YEAR) shl 9) or (month shl 5) or day
		val time = (hour shl 11) or (minute shl 5) or (second / 2)
		return (date shl 16) or time
	}
}