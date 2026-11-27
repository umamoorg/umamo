package org.umamo.ui.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The synthetic advance of one rotated cluster, in px. */
private const val ROTATED_ADVANCE = 10f

/** The synthetic line box of a rotated run, across the column, in px. */
private const val ROTATED_CROSS = 14f

/** The synthetic em of an upright cell, down the column and across it, in px. */
private const val UPRIGHT_EM = 12f

/** The synthetic line height every case lays out at, in px. */
private const val LINE_HEIGHT = 16f

/** The rotated ellipsis's synthetic size. */
private val ROTATED_ELLIPSIS_METRICS = EllipsisMetrics(length = 9f, crossExtent = ROTATED_CROSS)

/** The upright ellipsis's synthetic size. */
private val UPRIGHT_ELLIPSIS_METRICS = EllipsisMetrics(length = UPRIGHT_EM, crossExtent = UPRIGHT_EM)

/**
 * The geometry behind vertical labels: which way each character stands, how a label breaks into clusters and
 * measured segments, and where the segments land when the label fits and when it is cut short.
 */
class VerticalTextPlacementTest {
	/**
	 * Measures [text] with fixed synthetic sizes: every rotated cluster [ROTATED_ADVANCE] long, every upright
	 * cell [UPRIGHT_EM] square.
	 *
	 * @param String text The label.
	 * @return List The label's segments, measured.
	 */
	private fun syntheticallyMeasured(text: String): List<MeasuredSegment> =
		verticalSegmentsOf(text).map { segment ->
			if (segment.orientation == GlyphOrientation.Rotated) {
				MeasuredSegment(segment, FloatArray(segment.clusters.size) { clusterIndex -> (clusterIndex + 1) * ROTATED_ADVANCE }, ROTATED_CROSS)
			} else {
				MeasuredSegment(segment, floatArrayOf(UPRIGHT_EM), UPRIGHT_EM)
			}
		}

	/**
	 * Lays [text] out with the synthetic sizes under [maxLength].
	 *
	 * @param String text The label.
	 * @param Float maxLength The longest the column may run, in px.
	 * @return VerticalTextPlacement The laid-out label.
	 */
	private fun place(text: String, maxLength: Float = Float.POSITIVE_INFINITY): VerticalTextPlacement =
		placeVerticalSegments(syntheticallyMeasured(text), ROTATED_ELLIPSIS_METRICS, UPRIGHT_ELLIPSIS_METRICS, LINE_HEIGHT, maxLength)

	/**
	 * The kept text of a placement, read back from the segments' kept clusters.
	 *
	 * @param String text The label the placement was made from.
	 * @param VerticalTextPlacement placement The placement.
	 * @return String The text it keeps.
	 */
	private fun keptText(text: String, placement: VerticalTextPlacement): String {
		val segments = verticalSegmentsOf(text)

		return placement.segments.joinToString(separator = "") { kept ->
			val segment = segments[kept.segmentIndex]
			text.substring(segment.start, segment.clusters[kept.keptClusterCount - 1].end)
		}
	}

	/** Latin, halfwidth katakana, the ellipsis, and the space lie on their side. */
	@Test
	fun horizontalScriptsLieOnTheirSide() {
		for (codePoint in listOf('A'.code, 0xFF71, 0x2026, ' '.code)) {
			assertEquals(GlyphOrientation.Rotated, glyphOrientationOf(codePoint), "U+${codePoint.toString(16)}")
		}
	}

	/** Kana, ー, CJK punctuation, fullwidth forms, Hangul, kanji, and the ideographic planes stand upright. */
	@Test
	fun cjkStandsUpright() {
		for (codePoint in listOf(0x30A2, 0x30FC, 0x3002, 0x300C, 0xFF21, 0xAC00, 0x6F22, 0x3000, 0x20000, 0x1B001, 0xFE19)) {
			assertEquals(GlyphOrientation.Upright, glyphOrientationOf(codePoint), "U+${codePoint.toString(16)}")
		}
	}

	/**
	 * A base with its marks, a supplementary ideograph, emoji with a skin tone or joined by a joiner, an
	 * ideograph with an ideographic variation selector, a flag, and a syllable spelled in conjoining jamo are
	 * each one character.
	 */
	@Test
	fun composedCharactersAreOneCluster() {
		val samples =
			listOf(
				"é",
				"が",
				"𠀋",
				"👍🏽",
				"👩‍💻",
				"葛󠄀",
				"🇯🇵",
				"각",
			)

		for (sample in samples) {
			val clusters = textClustersOf(sample)
			assertEquals(1, clusters.size, "\"$sample\" is one character")
			assertEquals(sample.length, clusters.single().end)
		}
	}

	/** Two flags are two characters, and a mark with nothing before it is a character of its own. */
	@Test
	fun clustersStopWhereTheCharacterDoes() {
		assertEquals(2, textClustersOf("🇯🇵🇺🇸").size, "two flags")
		assertEquals(2, textClustersOf("́a").size, "a leading mark stands alone")
	}

	/** A supplementary ideograph stands upright by its whole code point, and a space is known as whitespace. */
	@Test
	fun clustersCarryOrientationAndWhitespace() {
		assertEquals(GlyphOrientation.Upright, textClustersOf("𠀋").single().orientation)
		val clusters = textClustersOf("a b")
		assertEquals(listOf(false, true, false), clusters.map { cluster -> cluster.isWhitespace })
	}

	/** Rotated characters run on in one segment; each upright character is a segment of its own. */
	@Test
	fun segmentsRunOnOrStandAlone() {
		assertEquals(listOf(GlyphOrientation.Rotated), verticalSegmentsOf("Tool").map { segment -> segment.orientation })
		assertEquals(4, verticalSegmentsOf("Tool").single().clusters.size)
		assertEquals(List(3) { GlyphOrientation.Upright }, verticalSegmentsOf("ツール").map { segment -> segment.orientation })
		val mixed = verticalSegmentsOf("UVマップ")
		assertEquals(listOf(GlyphOrientation.Rotated, GlyphOrientation.Upright, GlyphOrientation.Upright, GlyphOrientation.Upright), mixed.map { segment -> segment.orientation })
		assertEquals("UV", "UVマップ".substring(mixed.first().start, mixed.first().end))
		assertEquals(3, verticalSegmentsOf("A ア B").size)
	}

	/** A label that fits is laid out whole, one segment after another, centered across the line height. */
	@Test
	fun aLabelThatFitsIsLaidOutWhole() {
		val placement = place("UVマ", maxLength = 100f)
		assertFalse(placement.truncated)
		assertNull(placement.ellipsis)
		assertEquals(LINE_HEIGHT, placement.thickness)
		assertEquals(2 * ROTATED_ADVANCE + UPRIGHT_EM, placement.length)
		assertEquals(listOf(0f, 2 * ROTATED_ADVANCE), placement.segments.map { segment -> segment.alongOffset })
		assertEquals(listOf((LINE_HEIGHT - ROTATED_CROSS) / 2f, (LINE_HEIGHT - UPRIGHT_EM) / 2f), placement.segments.map { segment -> segment.crossOffset })
	}

	/** A cut falls on a character boundary inside a rotated run, keeping the longest prefix the ellipsis fits after. */
	@Test
	fun aCutInsideARunKeepsTheLongestPrefixThatFits() {
		val placement = place("Toolbox", maxLength = 50f)
		assertTrue(placement.truncated)
		assertEquals("Tool", keptText("Toolbox", placement))
		val ellipsis = assertNotNull(placement.ellipsis)
		assertEquals(GlyphOrientation.Rotated, ellipsis.orientation)
		assertEquals(4 * ROTATED_ADVANCE, ellipsis.alongOffset)
		assertEquals(4 * ROTATED_ADVANCE + ROTATED_ELLIPSIS_METRICS.length, placement.length)
	}

	/** A cut never ends on whitespace, even where the space and the ellipsis would fit. */
	@Test
	fun aCutDropsTrailingWhitespace() {
		val placement = place("Tool box", maxLength = 59f)
		assertEquals("Tool", keptText("Tool box", placement))
	}

	/** The ellipsis matches what it follows: upright after upright text, on its side after rotated text. */
	@Test
	fun theEllipsisFollowsTheLastCharacterKept() {
		val upright = place("アイテム", maxLength = 40f)
		assertEquals("アイ", keptText("アイテム", upright))
		assertEquals(GlyphOrientation.Upright, assertNotNull(upright.ellipsis).orientation)
		val mixed = place("UVマップ", maxLength = 35f)
		assertEquals("UV", keptText("UVマップ", mixed))
		assertEquals(GlyphOrientation.Rotated, assertNotNull(mixed.ellipsis).orientation)
	}

	/** With no room for a character beside it the ellipsis stands alone, and with no room for that, nothing does. */
	@Test
	fun aTinyMaximumKeepsTheEllipsisOrNothing() {
		val alone = place("Tool", maxLength = 9f)
		assertTrue(alone.truncated)
		assertTrue(alone.segments.isEmpty())
		assertEquals(0f, assertNotNull(alone.ellipsis).alongOffset)
		val nothing = place("Tool", maxLength = 5f)
		assertTrue(nothing.truncated)
		assertTrue(nothing.segments.isEmpty())
		assertNull(nothing.ellipsis)
		assertEquals(0f, nothing.length)
	}

	/** A segment wider than the line height widens the column, and the narrower segments center within it. */
	@Test
	fun aWideSegmentWidensTheColumn() {
		val segments = verticalSegmentsOf("Aア")
		val measured =
			listOf(
				MeasuredSegment(segments[0], floatArrayOf(ROTATED_ADVANCE), ROTATED_CROSS),
				MeasuredSegment(segments[1], floatArrayOf(UPRIGHT_EM), 20f),
			)
		val placement = placeVerticalSegments(measured, ROTATED_ELLIPSIS_METRICS, UPRIGHT_ELLIPSIS_METRICS, LINE_HEIGHT, Float.POSITIVE_INFINITY)
		assertEquals(20f, placement.thickness)
		assertEquals(listOf((20f - ROTATED_CROSS) / 2f, 0f), placement.segments.map { segment -> segment.crossOffset })
	}
}