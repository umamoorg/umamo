package org.umamo.format.cmo3.caff

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the byte layout of a CAFF compressed blob, measured from the corpus (see CMO3.md §1).
 *
 * [CaffRoundTripTest] cannot cover this: it round-trips through our own reader, which any
 * self-consistent zip passes — including one carrying a central directory the format does not have.
 * The layout is only observable by asserting on the bytes, so it is asserted here, corpus-free, so it
 * runs on every target including Kotlin/Native.
 */
class CaffZipFramingTest {
	// Compressible enough that the payload is unambiguously deflated rather than stored.
	private val contents = ByteArray(20_000) { (it % 11).toByte() }

	/**
	 * Reads a little-endian unsigned 16-bit value.
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the low byte.
	 * @return Int The value.
	 */
	private fun readU16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

	/**
	 * Reads a little-endian unsigned 32-bit value.
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the low byte.
	 * @return Long The value.
	 */
	private fun readU32(bytes: ByteArray, at: Int): Long =
		(0 until 4).fold(0L) { accumulated, byteIndex ->
			accumulated or ((bytes[at + byteIndex].toLong() and 0xFF) shl (8 * byteIndex))
		}

	/**
	 * Finds every offset at which [signature] occurs in [bytes].
	 *
	 * @param ByteArray bytes     The buffer to scan.
	 * @param Long signature      The little-endian 32-bit signature.
	 * @return List<Int> The matching offsets.
	 */
	private fun occurrencesOf(bytes: ByteArray, signature: Long): List<Int> =
		(0..bytes.size - 4).filter { offset -> readU32(bytes, offset) == signature }

	@Test
	fun localHeaderMatchesWhatTheEditorWrites() {
		val framed = CaffZip.zipSingle(contents, 1)
		// Measured from Erica Tamamo.cmo3 after de-obfuscating (XOR 0xB1).
		assertEquals(0x04034B50L, readU32(framed, 0), "local file header signature")
		assertEquals(20, readU16(framed, 4), "version needed = 2.0, the minimum that understands DEFLATE")
		assertEquals(0x0808, readU16(framed, 6), "flags: bit 3 (data descriptor) + bit 11 (UTF-8 name)")
		assertEquals(8, readU16(framed, 8), "method 8 = DEFLATE")
		// Flag bit 3's whole meaning: the real values live in the trailing descriptor, not here.
		assertEquals(0L, readU32(framed, 14), "crc32 is zeroed in the local header")
		assertEquals(0L, readU32(framed, 18), "compressed size is zeroed in the local header")
		assertEquals(0L, readU32(framed, 22), "uncompressed size is zeroed in the local header")
		assertEquals(8, readU16(framed, 26), "name length")
		assertEquals(0, readU16(framed, 28), "extra length")
		assertEquals("contents", framed.copyOfRange(30, 38).decodeToString(), "entry name")
	}

	@Test
	fun blobEndsAtItsDataDescriptorWithNoCentralDirectory() {
		val framed = CaffZip.zipSingle(contents, 1)
		// Sanity FIRST: an absence assertion is worthless if the scanner can never find anything, and
		// a vacuously-empty occurrencesOf would pass both checks below while proving nothing. Pin that
		// it locates the one signature we know is present before trusting it about the ones that are not.
		assertEquals(listOf(0), occurrencesOf(framed, 0x04034B50L), "the scanner must find the local header at offset 0")

		// The load-bearing fact. A CAFF blob is a PARTIAL zip: ZipOutputStream would append both of
		// these on close, adding 76 bytes per compressed entry to every save. The corpus sample has
		// zero of either, and re-emitting without them reproduces its exact file size.
		assertEquals(emptyList(), occurrencesOf(framed, 0x02014B50L), "there must be no central directory record")
		assertEquals(emptyList(), occurrencesOf(framed, 0x06054B50L), "there must be no end-of-central-directory record")

		val descriptorStart = framed.size - 16
		assertEquals(0x08074B50L, readU32(framed, descriptorStart), "the blob ends with its data descriptor")
		assertEquals(contents.size.toLong(), readU32(framed, descriptorStart + 12), "the descriptor declares the uncompressed size")
		assertEquals((framed.size - 16 - 38).toLong(), readU32(framed, descriptorStart + 8), "the descriptor declares the compressed size")
	}

	@Test
	fun timestampIsPinnedSoOutputIsReproducible() {
		// The editor stamps a wall clock here (2022-06-06 17:03:30 in the sample), which is why its
		// own output is not byte-stable and ours is. Pinned to the DOS epoch, 1980-01-01 00:00.
		val framed = CaffZip.zipSingle(contents, 1)
		assertEquals(0x0000, readU16(framed, 10), "DOS time is pinned, not sampled from a clock")
		assertEquals(0x0021, readU16(framed, 12), "DOS date is pinned to 1980-01-01")
		assertContentEquals(framed, CaffZip.zipSingle(contents, 1), "the same input must re-emit the same bytes")
	}

	@Test
	fun inflateIsBoundedByTheDeclaredUncompressedSize() {
		// storedSize bounds the INPUT only; DEFLATE reaches ~1000:1, so the descriptor's usize is the
		// only thing capping the output. A descriptor that lies must truncate its own entry, not
		// allocate on the reader's behalf.
		val framed = CaffZip.zipSingle(contents, 1)
		val lying = framed.copyOf()
		lying[lying.size - 4] = 100
		lying[lying.size - 3] = 0
		lying[lying.size - 2] = 0
		lying[lying.size - 1] = 0
		assertEquals(100, CaffZip.unzipSingle(lying).size, "a declared size of 100 caps the inflate at 100 bytes")
	}

	@Test
	fun unrecognizableDescriptorFallsBackToUnboundedRatherThanTruncating() {
		// The fallback must not corrupt a valid read: better unbounded than silently short.
		val framed = CaffZip.zipSingle(contents, 1)
		val mangled = framed.copyOf()
		mangled[mangled.size - 16] = 0x00
		assertContentEquals(contents, CaffZip.unzipSingle(mangled), "a blob with no usable descriptor still inflates fully")

		val zeroed = framed.copyOf()
		for (offset in zeroed.size - 4 until zeroed.size) {
			zeroed[offset] = 0
		}
		assertContentEquals(contents, CaffZip.unzipSingle(zeroed), "a zero declared size reads as absent, not as an empty entry")
	}

	@Test
	fun roundTripsAtEveryCompressionLevelInUse() {
		// CompressOption's levels; each must frame and unframe identically.
		for (level in intArrayOf(0, 1, 5, 9)) {
			val framed = CaffZip.zipSingle(contents, level)
			assertTrue(framed.size > 38 + 16, "level $level must emit a header, payload, and descriptor")
			assertContentEquals(contents, CaffZip.unzipSingle(framed), "round-trip at level $level")
		}
	}
}
