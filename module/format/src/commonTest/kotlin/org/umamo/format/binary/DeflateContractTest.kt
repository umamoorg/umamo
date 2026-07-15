package org.umamo.format.binary

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the DEFLATE seam's contract.  In commonTest, so it also runs against Kotlin/Native.
 *
 * Nothing called [inflateZlib] directly before this, so its best-effort behaviour was untested even
 * though every reader leans on it: a truncated or corrupt stream must yield the bytes recovered so
 * far rather than throwing, which is what lets a partly damaged art file still decode.  The
 * `maximumSize` bound matters just as much — it is the only thing standing between a malicious or
 * corrupt stream and an unbounded allocation.
 */
class DeflateContractTest {
	// Compressible, PNG-scanline-ish data: a repeating ramp with enough structure to deflate well.
	private val raw = ByteArray(40_000) { (it % 7).toByte() }
	private val compressed = deflateZlib(raw)

	@Test
	fun roundTripsZlib() {
		assertTrue(compressed.size < raw.size, "must actually compress: ${compressed.size} vs ${raw.size}")
		assertContentEquals(raw, inflateZlib(compressed, 0, compressed.size, raw.size), "zlib round-trip")
	}

	@Test
	fun truncatedStreamRecoversWhatItCan() {
		// The load-bearing case: a half-supplied stream must not throw, and must not silently yield
		// nothing either — the recovered prefix is what a damaged file still renders from.
		val recovered = inflateZlib(compressed, 0, compressed.size / 2, raw.size)
		assertTrue(recovered.isNotEmpty(), "a truncated stream must still recover its decodable prefix")
		assertTrue(recovered.size < raw.size, "a truncated stream must not somehow produce everything")
		assertContentEquals(raw.copyOfRange(0, recovered.size), recovered, "the recovered prefix must be correct, not garbage")
	}

	@Test
	fun corruptStreamYieldsEmptyRatherThanThrowing() {
		val corrupt = compressed.copyOf()
		for (index in 20 until minOf(60, corrupt.size)) {
			corrupt[index] = 0x7F
		}
		assertEquals(0, inflateZlib(corrupt, 0, corrupt.size, raw.size).size, "corrupt stream decodes to nothing, without throwing")
		assertEquals(0, inflateZlib(ByteArray(500) { 0x5A }, 0, 500, raw.size).size, "garbage decodes to nothing, without throwing")
	}

	@Test
	fun respectsTheMaximumSizeBound() {
		// The guard against a decompression bomb: stop at the stated size, however much is available.
		assertEquals(1_000, inflateZlib(compressed, 0, compressed.size, 1_000).size, "output is capped at maximumSize")
		assertContentEquals(raw.copyOfRange(0, 1_000), inflateZlib(compressed, 0, compressed.size, 1_000), "the capped prefix is still correct")
	}

	@Test
	fun readsFromAnEmbeddedSubrange() {
		val padded = ByteArray(11) + compressed + ByteArray(9)
		assertContentEquals(raw, inflateZlib(padded, 11, compressed.size, raw.size), "offset/length select the stream")
	}

	@Test
	fun degenerateInputsYieldEmpty() {
		assertEquals(0, inflateZlib(compressed, 0, 0, raw.size).size, "zero length")
		assertEquals(0, inflateZlib(compressed, compressed.size + 5, 10, raw.size).size, "offset past end")
		assertEquals(0, inflateZlib(compressed, 0, compressed.size, 0).size, "zero maximum")
		assertEquals(0, inflateZlib(ByteArray(0), 0, 0, 16).size, "empty source")
	}

	@Test
	fun roundTripsRawDeflateAtEveryCaffLevel() {
		// Raw (unwrapped) deflate is what a ZIP entry's payload is; CompressOption uses levels 0/1/5.
		// The trailing-dummy-byte requirement of nowrap mode makes this worth pinning explicitly.
		for (level in intArrayOf(0, 1, 5, 9)) {
			val rawStream = deflateRawDeflate(raw, level)
			assertContentEquals(raw, inflateRawDeflate(rawStream, 0, rawStream.size, raw.size), "raw deflate round-trip at level $level")
		}
	}

	@Test
	fun rawDeflateIsExactlyTheZlibStreamWithoutItsFraming() {
		// If the wrapper ever leaked back into the raw path, the CAFF codec's ZIP framing would be
		// silently wrong — a ZIP reader does not expect zlib's 0x78 header or its Adler-32 trailer.
		// Compare at the SAME level (6 == zlib's default), so only the framing differs.
		val zlibStream = deflateZlib(raw)
		val rawStream = deflateRawDeflate(raw, 6)
		assertEquals(0x78, zlibStream[0].toInt() and 0xFF, "zlib framing starts with the 0x78 CMF byte")
		assertContentEquals(
			zlibStream.copyOfRange(2, zlibStream.size - 4),
			rawStream,
			"raw deflate must be exactly the zlib stream minus its 2-byte header and 4-byte Adler-32",
		)
	}
}
