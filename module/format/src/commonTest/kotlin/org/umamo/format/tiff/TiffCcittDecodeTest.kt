package org.umamo.format.tiff

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates [TiffReader]'s CCITT fax decode against golden fixtures.  Each fixture is a tiny bi-level
 * TIFF encoded by libtiff (via Pillow) in one of the three CCITT variants the reader supports, and the
 * expected pixels are the ones libtiff itself decodes them to.  CCITT is lossless, so the decode must
 * reproduce them exactly.  The images are embedded base64 and the expected pixels are written as ASCII
 * art ('#' black, '.' white) so a failure reads as a picture rather than a byte diff.
 */
class TiffCcittDecodeTest {
	/**
	 * Decodes an embedded fixture and asserts its pixels match the expected ASCII art.
	 *
	 * @param String tiffBase64        Base64 of the `.tif` bytes.
	 * @param List<String> expectedRows The expected image, one string per row, '#' black and '.' white.
	 */
	private fun assertDecodes(tiffBase64: String, expectedRows: List<String>) {
		val decoded = TiffReader.read(Base64.decode(tiffBase64))
		assertEquals(expectedRows[0].length, decoded.width, "width")
		assertEquals(expectedRows.size, decoded.height, "height")

		val actualRows =
			(0 until decoded.height).map { row ->
				buildString {
					for (column in 0 until decoded.width) {
						val pixel = (row * decoded.width + column) * 4
						val red = decoded.rgba[pixel].toInt() and 0xFF
						val alpha = decoded.rgba[pixel + 3].toInt() and 0xFF
						assertEquals(255, alpha, "bi-level TIFF must decode opaque at ($column, $row)")
						append(if (red == 0) '#' else '.')
					}
				}
			}
		assertEquals(expectedRows.joinToString("\n"), actualRows.joinToString("\n"), "CCITT decode must match libtiff pixel-for-pixel")
	}

	/** Modified Huffman RLE (compression 2); PhotometricInterpretation 1. */
	@Test
	fun decodesModifiedHuffmanRle() {
		assertDecodes(
			tiffBase64 =
				"SUkqAE4AAADx+Ozx+Ozx+Ozx+Ow1n/H7NTHXj9g1h+Y/YDUfj9j0Of47sPx36HOw+HplYPY/47A1h7zHXsA1H7H/YDWHp497NR+n" +
					"fsAACQAAAQMAAQAAACAAAAABAQMAAQAAABAAAAACAQMAAQAAAAEAAAADAQMAAQAAAAIAAAAGAQMAAQAAAAEAAAARAQQAAQAAAAgA" +
					"AAAWAQMAAQAAABAAAAAXAQQAAQAAAEUAAAAcAQMAAQAAAAEAAAAAAAAA",
			expectedRows =
				listOf(
					"#######.......#######.......####",
					"#######.......#######.......####",
					"#######.......#######.......####",
					"#######.......#######.......####",
					"...##..#######.......#######....",
					".....#.#######.......#######....",
					"...#..########.......#######....",
					".......#######.......#######....",
					"#######.#.....#######..#....####",
					"#######..#....#######.#.....####",
					"#######...#...########......####",
					"#######....#..#######.......####",
					"...#...#######.....#.#######....",
					".......#######....#..#######....",
					"...#...########..#...#######....",
					".......#######.##....#######....",
				),
		)
	}

	/** Group 3 / T.4 (compression 3); PhotometricInterpretation 1. */
	@Test
	fun decodesGroup3() {
		assertDecodes(
			tiffBase64 =
				"SUkqAGAAAAAAHx+OwAfH47AB8fjsAHx+OwATWf8fsAE1MdeP2ACaw/MfsAE1H4/YAPoc/x3YAP479DnYAPw9MrAB9j/jsAE1h7zH" +
					"XsAE1H7H/YAJrD08e9gAmo/Tv2AACQAAAQMAAQAAACAAAAABAQMAAQAAABAAAAACAQMAAQAAAAEAAAADAQMAAQAAAAMAAAAGAQMA" +
					"AQAAAAEAAAARAQQAAQAAAAgAAAAWAQMAAQAAABAAAAAXAQQAAQAAAFcAAAAcAQMAAQAAAAEAAAAAAAAA",
			expectedRows =
				listOf(
					"#######.......#######.......####",
					"#######.......#######.......####",
					"#######.......#######.......####",
					"#######.......#######.......####",
					"...##..#######.......#######....",
					".....#.#######.......#######....",
					"...#..########.......#######....",
					".......#######.......#######....",
					"#######.#.....#######..#....####",
					"#######..#....#######.#.....####",
					"#######...#...########......####",
					"#######....#..#######.......####",
					"...#...#######.....#.#######....",
					".......#######....#..#######....",
					"...#...########..#...#######....",
					".......#######.##....#######....",
				),
		)
	}

	/** Group 4 / T.6 (compression 4); PhotometricInterpretation 1. */
	@Test
	fun decodesGroup4() {
		assertDecodes(
			tiffBase64 =
				"SUkqADoAAAA+M+P//yawR/I/wyOvhBP4vz6I5n8jvZHdLsjz7I+smsCN5Mcr8dLzD2l40EuACACAAAkAAAEDAAEAAAAgAAAAAQED" +
					"AAEAAAAQAAAAAgEDAAEAAAABAAAAAwEDAAEAAAAEAAAABgEDAAEAAAABAAAAEQEEAAEAAAAIAAAAFgEDAAEAAAAQAAAAFwEEAAEA" +
					"AAAxAAAAHAEDAAEAAAABAAAAAAAAAA==",
			expectedRows =
				listOf(
					"#######.......#######.......####",
					"#######.......#######.......####",
					"#######.......#######.......####",
					"#######.......#######.......####",
					"...##..#######.......#######....",
					".....#.#######.......#######....",
					"...#..########.......#######....",
					".......#######.......#######....",
					"#######.#.....#######..#....####",
					"#######..#....#######.#.....####",
					"#######...#...########......####",
					"#######....#..#######.......####",
					"...#...#######.....#.#######....",
					".......#######....#..#######....",
					"...#...########..#...#######....",
					".......#######.##....#######....",
				),
		)
	}
}
