package org.umamo.ui.tracks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the drawn marks actually track the sheet's mark radius.
 *
 * A pixel test rather than a logic one, because the question it answers is exactly "does changing the
 * radius change what is on screen" - a rendering fact no assertion over the geometry helpers alone can
 * settle.
 */
class TrackMarkSizeTest {
	/** A single track with one mark in the middle of its domain. */
	private val rows =
		listOf(TrackRow(key = "track", label = "Track", marks = listOf(TrackKeyMark(0, 0f))))

	/** Doubling the radius makes the drawn mark cover materially more pixels. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theDrawnMarkScalesWithTheRadius() {
		val smallArea = markPixelCount(4.dp)
		val largeArea = markPixelCount(12.dp)
		assertTrue(smallArea > 0, "the small mark must draw at all (got $smallArea px)")
		assertTrue(
			largeArea > smallArea * 2,
			"tripling the radius must grow the mark's area well past double (small=$smallArea large=$largeArea)",
		)
	}

	/**
	 * The default render is exactly the [TRACK_MARK_RADIUS] render, and changing that constant changes it.
	 *
	 * Pins the whole chain the sheet actually uses - the constant, through TrackSheet's default argument
	 * and the lane's markRadiusPx, into drawMark - rather than only the explicit-argument path: a break
	 * anywhere in that chain would show only when the constant itself changes, not when a radius is passed
	 * explicitly, so the default path needs its own assertion rather than riding on the explicit one.
	 */
	@Test
	fun theDefaultIsTheConstant() {
		assertEquals(
			markPixelCount(TRACK_MARK_RADIUS),
			markPixelCount(null),
			"omitting markRadius must draw exactly what TRACK_MARK_RADIUS draws",
		)
		assertTrue(
			markPixelCount(TRACK_MARK_RADIUS * 2f) > markPixelCount(null),
			"doubling the constant must grow the drawn mark",
		)
	}

	/**
	 * Renders one sheet at [markRadius] and counts the pixels the mark differs from the bare lane in.
	 *
	 * Differenced against a mark-less render rather than thresholded absolutely, so the count isolates the
	 * mark from the lane's tone band and baseline whatever the theme resolves those to.
	 *
	 * @param Dp? markRadius The radius to render at, or null to exercise the sheet's own default.
	 * @return Int The number of pixels the mark covers.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun markPixelCount(markRadius: Dp?): Int {
		var withMark: IntArray? = null
		var without: IntArray? = null
		for (drawMark in listOf(true, false)) {
			runComposeUiTest {
				setContent {
					Box(modifier = Modifier.size(width = 400.dp, height = 60.dp).testTag("sheet")) {
						val sheetRows = if (drawMark) rows else listOf(rows.single().copy(marks = emptyList()))
						if (markRadius == null) {
							TrackSheet(
								rows = sheetRows,
								axis = TrackAxis(-30f, 30f),
								playhead = null,
								modifier = Modifier.fillMaxSize(),
							)
						} else {
							TrackSheet(
								rows = sheetRows,
								axis = TrackAxis(-30f, 30f),
								playhead = null,
								modifier = Modifier.fillMaxSize(),
								markRadius = markRadius,
							)
						}
					}
				}
				val image = onNodeWithTag("sheet").captureToImage()
				val pixels = IntArray(image.width * image.height)
				image.readPixels(pixels)
				if (drawMark) {
					withMark = pixels
				} else {
					without = pixels
				}
			}
		}
		val marked = requireNotNull(withMark)
		val bare = requireNotNull(without)
		return marked.indices.count { pixelIndex -> marked[pixelIndex] != bare[pixelIndex] }
	}
}