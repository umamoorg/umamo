package org.umamo.format.art

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** AlphaChannel.opaqueBounds: the rectangle the alpha analysis reports, read through a channel. */
class OpaqueBoundsTest {
	@Test
	fun agreesWithTheAlphaAnalysisAtEveryThreshold() {
		val raster = rasterOfRows("......", "..1...", ".#9#..", "..#...", "......")

		for (threshold in listOf(1, 2, 9, 10, 128, 255)) {
			val analysis = raster.analyzeAlpha(alphaThreshold = threshold)

			assertEquals(analysis?.opaqueBounds, raster.alphaChannel().opaqueBounds(threshold), "threshold $threshold")
		}
	}

	@Test
	fun countsAPixelExactlyAtTheThreshold() {
		val raster = rasterOfAlphas(127, 128, 0, 0)

		assertEquals(LayerBounds(left = 1, top = 0, width = 1, height = 1), raster.alphaChannel().opaqueBounds(128))
		assertNull(raster.alphaChannel().opaqueBounds(129))
	}

	@Test
	fun transparentArtHasNoBounds() {
		assertNull(rasterOfRows("...", "...").alphaChannel().opaqueBounds(1))
	}

	@Test
	fun readsOnlyWhatTheChannelAnswers() {
		val raster = rasterOfRows("#....", ".....", "....#")
		val rightHalf =
			object : AlphaChannel {
				override val width: Int = raster.width
				override val height: Int = raster.height

				override fun alphaAt(column: Int, row: Int): Int = if (column >= 3) raster.alphaChannel().alphaAt(column, row) else 0
			}

		assertEquals(LayerBounds(left = 4, top = 2, width = 1, height = 1), assertNotNull(rightHalf.opaqueBounds(1)))
	}

	@Test
	fun rejectsAThresholdOutsideTheAlphaRange() {
		val channel = rasterOfRows("#").alphaChannel()

		assertFailsWith<IllegalArgumentException> { channel.opaqueBounds(0) }
		assertFailsWith<IllegalArgumentException> { channel.opaqueBounds(256) }
	}
}