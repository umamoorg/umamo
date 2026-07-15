package org.umamo.format.kra

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Unit tests for the pure KRA tile primitives - LZF decode and color delinearisation - using
 * hand-built byte vectors, so they run with no external corpus. These verify the trickiest,
 * citation-bound logic in isolation; the corpus-gated KraReaderTest exercises the same code on real
 * Krita output (which is dense with back-references).
 */
class KraLzfTest {
	@Test
	fun lzfDecodesALiteralRun() {
		// A literal run of N bytes is encoded as control byte (N-1) followed by the N raw bytes.
		val stream = byteArrayOf(3, 'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 'D'.code.toByte())
		val output = ByteArray(4)
		val written = lzfDecompress(stream, inputOffset = 0, inputLength = stream.size, output = output)
		assertEquals(4, written, "literal run length")
		assertContentEquals("ABCD".encodeToByteArray(), output, "literal bytes copied verbatim")
	}

	@Test
	fun lzfDecodesAnOverlappingBackReference() {
		// Literal AB, then a back-reference copying 3 bytes at distance 2, an overlapping ABA,
		// yielding ABABA. Control 32 = (stored length 1 shifted left 5) or high distance bits 0; the
		// next byte 1 is the low distance bits (stored distance = actual distance minus 1).
		val stream = byteArrayOf(1, 'A'.code.toByte(), 'B'.code.toByte(), 32, 1)
		val output = ByteArray(5)
		val written = lzfDecompress(stream, inputOffset = 0, inputLength = stream.size, output = output)
		assertEquals(5, written, "back-reference expands output")
		assertContentEquals("ABABA".encodeToByteArray(), output, "overlapping copy expands run")
	}

	@Test
	fun delinearizeReinterleavesPlanarChannels() {
		// 2 pixels times 4 channels: planar input is channel-major (stride = pixel count = 2); output
		// is pixel-major (b,g,r,a,b,g,r,a). Distinct values per channel and pixel prove the mapping.
		val planar = byteArrayOf(10, 11, 20, 21, 30, 31, 40, 41)
		val packed = ByteArray(8)
		delinearizeColors(planar, packed, dataSize = 8, pixelSize = 4)
		assertContentEquals(byteArrayOf(10, 20, 30, 40, 11, 21, 31, 41), packed, "planar to interleaved")
	}
}
