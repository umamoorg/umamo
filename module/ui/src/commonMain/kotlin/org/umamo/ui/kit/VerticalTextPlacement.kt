package org.umamo.ui.kit

/*
 * The geometry behind VerticalText, kept free of Compose so it stays a pure function the tests drive
 * without a composition: which way each piece of a label stands, how the label breaks into the runs and
 * cells that are measured one at a time, and where each lands down the column once measured, cut short
 * with an ellipsis when it would run past its maximum length.
 */

/** Which way a piece of a vertical label stands in its column. */
internal enum class GlyphOrientation {
	/** Stands upright and takes a cell of its own, stacked under the one before: kana, kanji, Hangul. */
	Upright,

	/** Lies on its side, turned a quarter turn clockwise, and runs on with its neighbours: Latin. */
	Rotated,
}

/**
 * The code point ranges that stand upright, in ascending order.
 *
 * Unicode's vertical orientation property (UAX #50, Vertical_Orientation) reduced to two values: its U, Tu,
 * and Tr classes stand upright, and Tu and Tr take their vertical forms from the font's `vert` feature when
 * drawn.  The table holds the CJK blocks a UI label uses, block by block; everything else lies on its side,
 * the halfwidth katakana included (R in UAX #50).  Symbols UAX #50 also stands upright outside these blocks
 * (§, ©, ※, ①, ★, emoji) lie on their side here, which chrome labels do not use.
 */
private val UPRIGHT_RANGES =
	listOf(
		// Hangul Jamo
		0x1100..0x11FF,
		// CJK Radicals Supplement through CJK Unified Ideographs: radicals, ideographic description, CJK
		// symbols and punctuation, kana, Bopomofo, Hangul compatibility jamo, Kanbun, CJK strokes, enclosed
		// CJK, CJK compatibility, Extension A, Yijing hexagrams, and the unified ideographs.
		0x2E80..0x9FFF,
		// Yi Syllables and Yi Radicals
		0xA000..0xA4CF,
		// Hangul Jamo Extended-A
		0xA960..0xA97F,
		// Hangul Syllables and Hangul Jamo Extended-B
		0xAC00..0xD7FF,
		// CJK Compatibility Ideographs
		0xF900..0xFAFF,
		// Vertical Forms
		0xFE10..0xFE1F,
		// CJK Compatibility Forms and Small Form Variants
		0xFE30..0xFE6F,
		// Fullwidth ASCII variants and fullwidth brackets
		0xFF01..0xFF60,
		// Fullwidth signs
		0xFFE0..0xFFE7,
		// Kana Extended-B, Kana Supplement, Kana Extended-A, and Small Kana Extension
		0x1AFF0..0x1B16F,
		// Enclosed Ideographic Supplement
		0x1F200..0x1F2FF,
		// Supplementary and Tertiary Ideographic Planes
		0x20000..0x3FFFD,
	)

/**
 * Which way [codePoint] stands in a vertical label (see [UPRIGHT_RANGES]).
 *
 * @param Int codePoint The Unicode code point.
 * @return GlyphOrientation Upright for the CJK blocks, Rotated for everything else.
 */
internal fun glyphOrientationOf(codePoint: Int): GlyphOrientation {
	val rangeIndex =
		UPRIGHT_RANGES.binarySearch { range ->
			when {
				codePoint < range.first -> 1
				codePoint > range.last -> -1
				else -> 0
			}
		}

	return if (rangeIndex >= 0) GlyphOrientation.Upright else GlyphOrientation.Rotated
}

/**
 * One user-perceived character of a label: a base code point with the marks and joiners that stay with it,
 * so a cell or a cut never separates them.
 *
 * @property Int start The cluster's first UTF-16 index in the label.
 * @property Int end The UTF-16 index just past the cluster.
 * @property GlyphOrientation orientation Which way the cluster stands, decided by its base code point.
 * @property Boolean isWhitespace Whether the base code point is whitespace, which a cut never ends on.
 */
internal class TextCluster(
	val start: Int,
	val end: Int,
	val orientation: GlyphOrientation,
	val isWhitespace: Boolean,
)

/** The zero width joiner, which joins the code point after it to the cluster before it. */
private const val ZERO_WIDTH_JOINER = 0x200D

/** How a Hangul code point takes part in a syllable built from conjoining jamo. */
private enum class HangulPart {
	/** Not a Hangul jamo or syllable. */
	None,

	/** A leading consonant jamo. */
	Leading,

	/** A vowel jamo. */
	Vowel,

	/** A trailing consonant jamo. */
	Trailing,

	/** A precomposed syllable of a leading consonant and a vowel, open to a trailing consonant. */
	LeadingVowel,

	/** A precomposed syllable that already has its trailing consonant. */
	LeadingVowelTrailing,
}

/**
 * How [codePoint] takes part in a Hangul syllable, by the conjoining jamo ranges and the arithmetic layout
 * of the precomposed syllables (28 trailing slots per leading-vowel pair).
 *
 * @param Int codePoint The Unicode code point.
 * @return HangulPart The part it plays, or None.
 */
private fun hangulPartOf(codePoint: Int): HangulPart =
	when (codePoint) {
		in 0x1100..0x115F, in 0xA960..0xA97C -> HangulPart.Leading
		in 0x1160..0x11A7, in 0xD7B0..0xD7C6 -> HangulPart.Vowel
		in 0x11A8..0x11FF, in 0xD7CB..0xD7FB -> HangulPart.Trailing
		in 0xAC00..0xD7A3 -> if ((codePoint - 0xAC00) % 28 == 0) HangulPart.LeadingVowel else HangulPart.LeadingVowelTrailing
		else -> HangulPart.None
	}

/**
 * Whether a Hangul code point playing [next] continues the syllable a code point playing [previous] is in.
 *
 * @param HangulPart previous The part the code point before plays.
 * @param HangulPart next The part the code point after plays.
 * @return Boolean True when the two belong to one syllable.
 */
private fun continuesHangulSyllable(previous: HangulPart, next: HangulPart): Boolean =
	when (previous) {
		HangulPart.Leading -> {
			next == HangulPart.Leading || next == HangulPart.Vowel || next == HangulPart.LeadingVowel || next == HangulPart.LeadingVowelTrailing
		}

		HangulPart.LeadingVowel, HangulPart.Vowel -> {
			next == HangulPart.Vowel || next == HangulPart.Trailing
		}

		HangulPart.LeadingVowelTrailing, HangulPart.Trailing -> {
			next == HangulPart.Trailing
		}

		HangulPart.None -> {
			false
		}
	}

/**
 * Whether [codePoint] is a regional indicator, two of which make one flag.
 *
 * @param Int codePoint The Unicode code point.
 * @return Boolean True for the regional indicator letters.
 */
private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

/**
 * Whether [codePoint] never starts a cluster of its own but stays with the code point before it: a combining
 * mark, a variation selector, a joiner, an emoji skin tone or tag, or a halfwidth voiced mark.
 *
 * @param Int codePoint The Unicode code point.
 * @return Boolean True when it extends the cluster before it.
 */
private fun extendsCluster(codePoint: Int): Boolean {
	if (codePoint <= 0xFFFF) {
		val category = codePoint.toChar().category

		if (category == CharCategory.NON_SPACING_MARK || category == CharCategory.ENCLOSING_MARK || category == CharCategory.COMBINING_SPACING_MARK) {
			return true
		}
	}

	return codePoint in 0xFE00..0xFE0F ||
		codePoint in 0xE0100..0xE01EF ||
		codePoint == ZERO_WIDTH_JOINER ||
		codePoint in 0x1F3FB..0x1F3FF ||
		codePoint in 0xE0020..0xE007F ||
		codePoint in 0xFF9E..0xFF9F
}

/**
 * The code point at [index] in [text], decoding a surrogate pair by hand (the common standard library has
 * no code point API).
 *
 * @param String text The text.
 * @param Int index The UTF-16 index of the code point's first unit.
 * @return Int The code point; a lone surrogate is returned as itself.
 */
private fun codePointAt(text: String, index: Int): Int {
	val first = text[index]

	if (first.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
		return ((first.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00) + 0x10000
	}

	return first.code
}

/**
 * The number of UTF-16 units [codePoint] takes.
 *
 * @param Int codePoint The Unicode code point.
 * @return Int 2 for a supplementary code point, else 1.
 */
private fun unitCountOf(codePoint: Int): Int = if (codePoint > 0xFFFF) 2 else 1

/**
 * Whether [codePoint] is whitespace.
 *
 * @param Int codePoint The Unicode code point.
 * @return Boolean True for a whitespace character in the Basic Multilingual Plane.
 */
private fun isWhitespaceCodePoint(codePoint: Int): Boolean = codePoint <= 0xFFFF && codePoint.toChar().isWhitespace()

/**
 * Splits [text] into its user-perceived characters, close enough to Unicode's extended grapheme clusters for
 * UI labels: marks, variation selectors (the ideographic ones included), skin tones, tags, and halfwidth
 * voiced marks stay with the code point before them, a joiner takes the code point after it too, regional
 * indicators pair into flags, and conjoining Hangul jamo build their syllable.
 *
 * @param String text The label.
 * @return List The clusters, in order, covering the whole label.
 */
internal fun textClustersOf(text: String): List<TextCluster> {
	val clusters = ArrayList<TextCluster>()
	var clusterStart = 0
	var baseCodePoint = 0
	var previousCodePoint = -1
	var regionalIndicatorCount = 0
	var index = 0

	while (index < text.length) {
		val codePoint = codePointAt(text, index)
		val joinsPrevious =
			previousCodePoint >= 0 &&
				(
					extendsCluster(codePoint) ||
						previousCodePoint == ZERO_WIDTH_JOINER ||
						(isRegionalIndicator(codePoint) && regionalIndicatorCount == 1) ||
						continuesHangulSyllable(hangulPartOf(previousCodePoint), hangulPartOf(codePoint))
				)

		if (!joinsPrevious) {
			if (previousCodePoint >= 0) {
				clusters.add(TextCluster(clusterStart, index, glyphOrientationOf(baseCodePoint), isWhitespaceCodePoint(baseCodePoint)))
			}

			clusterStart = index
			baseCodePoint = codePoint
			regionalIndicatorCount = 0
		}

		if (isRegionalIndicator(codePoint)) {
			regionalIndicatorCount++
		}

		previousCodePoint = codePoint
		index += unitCountOf(codePoint)
	}

	if (previousCodePoint >= 0) {
		clusters.add(TextCluster(clusterStart, text.length, glyphOrientationOf(baseCodePoint), isWhitespaceCodePoint(baseCodePoint)))
	}

	return clusters
}

/**
 * One piece of a vertical label measured as a whole: a run of rotated clusters, which keeps its own spacing
 * and kerning, or a single upright cluster, which takes a cell of its own.
 *
 * @property Int start The segment's first UTF-16 index in the label.
 * @property Int end The UTF-16 index just past the segment.
 * @property GlyphOrientation orientation Which way every cluster in it stands.
 * @property List clusters The segment's clusters, in order.
 */
internal class VerticalSegment(
	val start: Int,
	val end: Int,
	val orientation: GlyphOrientation,
	val clusters: List<TextCluster>,
)

/**
 * Splits [text] into its measured pieces: each run of rotated clusters is one segment, and each upright
 * cluster is a segment of its own.
 *
 * @param String text The label.
 * @return List The segments, in order, covering the whole label.
 */
internal fun verticalSegmentsOf(text: String): List<VerticalSegment> {
	val segments = ArrayList<VerticalSegment>()
	val pendingRun = ArrayList<TextCluster>()

	/**
	 * Closes the run of rotated clusters gathered so far into one segment.
	 */
	fun closeRotatedRun() {
		if (pendingRun.isEmpty()) {
			return
		}

		segments.add(VerticalSegment(pendingRun.first().start, pendingRun.last().end, GlyphOrientation.Rotated, pendingRun.toList()))
		pendingRun.clear()
	}

	for (cluster in textClustersOf(text)) {
		if (cluster.orientation == GlyphOrientation.Rotated) {
			pendingRun.add(cluster)
		} else {
			closeRotatedRun()
			segments.add(VerticalSegment(cluster.start, cluster.end, GlyphOrientation.Upright, listOf(cluster)))
		}
	}

	closeRotatedRun()

	return segments
}

/**
 * A segment once measured: how far down the column each of its clusters ends, and how wide it is across.
 *
 * @property VerticalSegment segment The segment measured.
 * @property FloatArray clusterEndLengths The distance from the segment's start to the end of each of its
 *   clusters, in px, ascending; the last is the segment's length.
 * @property Float crossExtent The segment's extent across the column, in px.
 */
internal class MeasuredSegment(
	val segment: VerticalSegment,
	val clusterEndLengths: FloatArray,
	val crossExtent: Float,
) {
	/** The segment's length down the column, in px. */
	val length: Float get() = if (clusterEndLengths.isEmpty()) 0f else clusterEndLengths.last()
}

/**
 * The measured size of one ellipsis mark.
 *
 * @property Float length Its length down the column, in px.
 * @property Float crossExtent Its extent across the column, in px.
 */
internal class EllipsisMetrics(
	val length: Float,
	val crossExtent: Float,
)

/**
 * Where one kept segment lands.
 *
 * @property Int segmentIndex The segment's index in the measured list.
 * @property Int keptClusterCount How many of its clusters are kept; fewer than all only for the segment a cut
 *   falls inside.
 * @property Float alongOffset Its start down the column, in px.
 * @property Float length Its kept length down the column, in px.
 * @property Float crossOffset Its offset across the column, in px, centering it in the thickness.
 */
internal class SegmentPlacement(
	val segmentIndex: Int,
	val keptClusterCount: Int,
	val alongOffset: Float,
	val length: Float,
	val crossOffset: Float,
)

/**
 * Where the ellipsis lands after a cut.
 *
 * @property GlyphOrientation orientation Which ellipsis it is: lying on its side after rotated text, standing
 *   upright (the vertical ellipsis) after upright text.
 * @property Float alongOffset Its start down the column, in px.
 * @property Float length Its length down the column, in px.
 * @property Float crossOffset Its offset across the column, in px.
 */
internal class EllipsisPlacement(
	val orientation: GlyphOrientation,
	val alongOffset: Float,
	val length: Float,
	val crossOffset: Float,
)

/**
 * A vertical label laid out down its column.
 *
 * @property Float thickness The column's width, in px.
 * @property Float length The column's height, in px.
 * @property Boolean truncated Whether the label was cut short.
 * @property List segments The kept segments, top to bottom.
 * @property EllipsisPlacement? ellipsis The ellipsis after a cut, or null when the label was not cut (or not
 *   even the ellipsis fit).
 */
internal class VerticalTextPlacement(
	val thickness: Float,
	val length: Float,
	val truncated: Boolean,
	val segments: List<SegmentPlacement>,
	val ellipsis: EllipsisPlacement?,
)

/**
 * One place a cut could fall: after a given cluster of a given segment.
 *
 * @property Int segmentIndex The segment the cluster is in.
 * @property Int keptClusterCount How many of that segment's clusters the cut keeps.
 * @property Float prefixLength The length of everything kept, in px.
 * @property TextCluster lastCluster The last cluster kept.
 */
private class CutPoint(
	val segmentIndex: Int,
	val keptClusterCount: Int,
	val prefixLength: Float,
	val lastCluster: TextCluster,
)

/**
 * Lays [measured] down a column, one segment after another, centered across it.  When the whole would run
 * past [maxLength] the label is cut after the longest run of clusters that leaves room for the ellipsis,
 * never after whitespace, and the ellipsis matches the last cluster kept: lying on its side after rotated
 * text, the upright vertical ellipsis after upright text.  When no cluster fits beside it, the ellipsis
 * stands alone, and when not even that fits nothing is placed.
 *
 * @param List measured The label's segments, measured, in order.
 * @param EllipsisMetrics rotatedEllipsis The ellipsis that follows rotated text.
 * @param EllipsisMetrics uprightEllipsis The ellipsis that follows upright text.
 * @param Float minimumThickness The narrowest the column may be, in px: the line height, so labels in one
 *   style share one width whatever their script.
 * @param Float maxLength The longest the column may be, in px; positive infinity for no limit.
 * @return VerticalTextPlacement The laid-out label.
 */
internal fun placeVerticalSegments(
	measured: List<MeasuredSegment>,
	rotatedEllipsis: EllipsisMetrics,
	uprightEllipsis: EllipsisMetrics,
	minimumThickness: Float,
	maxLength: Float,
): VerticalTextPlacement {
	val totalLength = measured.sumOf { segment -> segment.length.toDouble() }.toFloat()

	if (totalLength <= maxLength) {
		val keptCounts = measured.map { segment -> segment.segment.clusters.size }

		return placeKept(measured, keptCounts, null, minimumThickness, truncated = false)
	}

	val cutPoints = ArrayList<CutPoint>()
	var segmentStart = 0f

	for ((segmentIndex, segment) in measured.withIndex()) {
		for ((clusterIndex, cluster) in segment.segment.clusters.withIndex()) {
			cutPoints.add(CutPoint(segmentIndex, clusterIndex + 1, segmentStart + segment.clusterEndLengths[clusterIndex], cluster))
		}

		segmentStart += segment.length
	}

	// Every cut keeps less than the whole, so the last point (the whole label) is never a candidate.
	for (pointIndex in cutPoints.size - 2 downTo 0) {
		val point = cutPoints[pointIndex]

		if (point.lastCluster.isWhitespace) {
			continue
		}

		val ellipsis = if (point.lastCluster.orientation == GlyphOrientation.Upright) uprightEllipsis else rotatedEllipsis

		if (point.prefixLength + ellipsis.length <= maxLength) {
			val keptCounts =
				measured.indices.map { segmentIndex ->
					when {
						segmentIndex < point.segmentIndex -> measured[segmentIndex].segment.clusters.size
						segmentIndex == point.segmentIndex -> point.keptClusterCount
						else -> 0
					}
				}

			return placeKept(measured, keptCounts, point.lastCluster.orientation to ellipsis, minimumThickness, truncated = true)
		}
	}

	// No cluster fits beside the ellipsis: the ellipsis alone, matching the label's first cluster, if it fits.
	val firstOrientation = measured.firstOrNull()?.segment?.orientation ?: GlyphOrientation.Rotated
	val loneEllipsis = if (firstOrientation == GlyphOrientation.Upright) uprightEllipsis else rotatedEllipsis
	val keptNothing = measured.map { 0 }

	if (loneEllipsis.length <= maxLength) {
		return placeKept(measured, keptNothing, firstOrientation to loneEllipsis, minimumThickness, truncated = true)
	}

	return placeKept(measured, keptNothing, null, minimumThickness, truncated = true)
}

/**
 * Places the kept part of each segment and the ellipsis, if any, down the column and centers each across it.
 *
 * @param List measured The label's segments, measured, in order.
 * @param List keptCounts How many clusters of each segment are kept.
 * @param Pair? ellipsis The ellipsis's orientation and size, or null for none.
 * @param Float minimumThickness The narrowest the column may be, in px.
 * @param Boolean truncated Whether the label was cut short.
 * @return VerticalTextPlacement The laid-out label.
 */
private fun placeKept(
	measured: List<MeasuredSegment>,
	keptCounts: List<Int>,
	ellipsis: Pair<GlyphOrientation, EllipsisMetrics>?,
	minimumThickness: Float,
	truncated: Boolean,
): VerticalTextPlacement {
	var thickness = minimumThickness

	for ((segmentIndex, segment) in measured.withIndex()) {
		if (keptCounts[segmentIndex] > 0) {
			thickness = maxOf(thickness, segment.crossExtent)
		}
	}

	if (ellipsis != null) {
		thickness = maxOf(thickness, ellipsis.second.crossExtent)
	}

	val placements = ArrayList<SegmentPlacement>()
	var alongOffset = 0f

	for ((segmentIndex, segment) in measured.withIndex()) {
		val keptClusterCount = keptCounts[segmentIndex]

		if (keptClusterCount == 0) {
			continue
		}

		val keptLength = segment.clusterEndLengths[keptClusterCount - 1]
		placements.add(SegmentPlacement(segmentIndex, keptClusterCount, alongOffset, keptLength, (thickness - segment.crossExtent) / 2f))
		alongOffset += keptLength
	}

	val ellipsisPlacement =
		ellipsis?.let { (orientation, metrics) ->
			EllipsisPlacement(orientation, alongOffset, metrics.length, (thickness - metrics.crossExtent) / 2f)
		}
	val length = alongOffset + (ellipsisPlacement?.length ?: 0f)

	return VerticalTextPlacement(thickness, length, truncated, placements, ellipsisPlacement)
}