package org.umamo.reimport

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.ArtSourceLayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * The matcher over what the inventory knows plus, when the file could be read, the pixels: a layer
 * that kept its pixels under a new name is recognized by its content hash outright; otherwise the
 * name, the folder, where it sits on the canvas, how big it is, and what it looks like each vote,
 * and the votes are averaged over the signals that could be read.  Pixels are compared only for the
 * candidates the metadata already likes, so a file of hundreds of layers costs a handful of small
 * resamples, not hundreds of decodes.
 */

/** The weight each signal carries in the combined score. */
private const val WEIGHT_NAME = 0.35f
private const val WEIGHT_PATH = 0.10f
private const val WEIGHT_BOUNDS = 0.20f
private const val WEIGHT_SIZE = 0.10f
private const val WEIGHT_PIXELS = 0.25f

/** How many metadata-ranked candidates have their pixels compared. */
private const val PIXEL_CANDIDATES = 5

/** The grid both rasters are resampled to for the pixel comparison. */
private const val PIXEL_GRID = 32

/** The alpha byte at or above which a resampled pixel counts as opaque. */
private const val OPAQUE_ALPHA = 128

/**
 * The [LayerMatcher] the Sources space and Match Automatically use.  See the file comment.
 */
object InventoryLayerMatcher : LayerMatcher {
	/** The confidence at or above which Match Automatically applies a match unasked, as a fraction. */
	const val DEFAULT_THRESHOLD: Float = 0.7f

	/**
	 * Ranks [candidates] for [missing]: metadata first, then the pixels of the best few.
	 *
	 * @param ArtSourceLayer       missing       The lost layer as the inventory recorded it.
	 * @param LayerRaster?         missingRaster The pixels the document holds for it, or null.
	 * @param List<MatchCandidate> candidates    The layers it could move to.
	 * @return List<LayerMatch> Every candidate scored, best first.
	 */
	override fun rank(missing: ArtSourceLayer, missingRaster: LayerRaster?, candidates: List<MatchCandidate>): List<LayerMatch> {
		val byMetadata =
			candidates
				.map { candidate -> candidate to metadataSignals(missing, candidate.row) }
				.sortedWith(compareByDescending<Pair<MatchCandidate, MatchSignals>> { (_, signals) -> combine(signals) }.thenBy { (candidate, _) -> candidate.row.name })
		return byMetadata
			.mapIndexed { index, (candidate, signals) ->
				val pixels =
					if (missingRaster != null && index < PIXEL_CANDIDATES) {
						candidate.raster()?.let { raster -> pixelSimilarity(missingRaster, raster) }
					} else {
						null
					}
				val scored = signals.copy(pixels = pixels)
				LayerMatch(candidate.row.key, combine(scored), scored)
			}
			.sortedWith(compareByDescending<LayerMatch> { match -> match.score }.thenBy { match -> candidates.first { candidate -> candidate.row.key == match.key }.row.name })
	}

	/**
	 * The signals the inventory alone can score.
	 *
	 * @param ArtSourceLayer missing   The lost layer.
	 * @param ArtSourceLayer candidate The candidate.
	 * @return MatchSignals The scores, pixels unset.
	 */
	private fun metadataSignals(missing: ArtSourceLayer, candidate: ArtSourceLayer): MatchSignals {
		val missingBounds = boundsOf(missing)
		val candidateBounds = boundsOf(candidate)
		val extentKnown = missingBounds.area > 0L && candidateBounds.area > 0L
		return MatchSignals(
			name = nameSimilarity(missing.name, candidate.name),
			path = pathAgreement(missing.groupPath, candidate.groupPath),
			bounds = if (extentKnown) missingBounds.intersectionOverUnion(candidateBounds) else null,
			size = if (extentKnown) sizeSimilarity(missingBounds, candidateBounds) else null,
			pixels = null,
			hashEqual = missing.contentHash != null && missing.contentHash == candidate.contentHash,
		)
	}

	/**
	 * The combined confidence: 1 on a content-hash match, else the weighted mean of the signals that
	 * could be scored.
	 *
	 * @param MatchSignals signals The per-signal scores.
	 * @return Float The confidence, 0..1.
	 */
	private fun combine(signals: MatchSignals): Float {
		if (signals.hashEqual) {
			return 1f
		}
		var total = WEIGHT_NAME * signals.name + WEIGHT_PATH * signals.path
		var weight = WEIGHT_NAME + WEIGHT_PATH
		val bounds = signals.bounds
		if (bounds != null) {
			total += WEIGHT_BOUNDS * bounds
			weight += WEIGHT_BOUNDS
		}
		val size = signals.size
		if (size != null) {
			total += WEIGHT_SIZE * size
			weight += WEIGHT_SIZE
		}
		val pixels = signals.pixels
		if (pixels != null) {
			total += WEIGHT_PIXELS * pixels
			weight += WEIGHT_PIXELS
		}
		return (total / weight).coerceIn(0f, 1f)
	}

	/**
	 * A layer row's canvas rectangle.
	 *
	 * @param ArtSourceLayer row The row.
	 * @return LayerBounds Its rectangle.
	 */
	private fun boundsOf(row: ArtSourceLayer): LayerBounds = LayerBounds(row.left, row.top, row.width, row.height)

	/**
	 * The smaller area over the larger.
	 *
	 * @param LayerBounds first  One rectangle, with area.
	 * @param LayerBounds second The other, with area.
	 * @return Float The ratio, 0..1.
	 */
	private fun sizeSimilarity(first: LayerBounds, second: LayerBounds): Float =
		min(first.area, second.area).toFloat() / max(first.area, second.area).toFloat()
}

/**
 * How alike two layer names are: one minus the edit distance over the longer length, after lowercasing
 * and collapsing whitespace, so "Hair Front" and "hair  front" agree and "Hair Front" and "Hair
 * Front 2" nearly do.  Two empty names agree.
 *
 * @param String first  One name.
 * @param String second The other.
 * @return Float The similarity, 0..1.
 */
fun nameSimilarity(first: String, second: String): Float {
	val left = normalizeName(first)
	val right = normalizeName(second)
	if (left.isEmpty() && right.isEmpty()) {
		return 1f
	}
	val longest = max(left.length, right.length)
	return 1f - editDistance(left, right).toFloat() / longest.toFloat()
}

/**
 * How far two folder paths agree: 1 for the same path, else the leading segments they share over the
 * longer path's segment count (a layer that moved one folder deeper keeps most of its agreement).
 *
 * @param String first  One slash-joined path ("" at the root).
 * @param String second The other.
 * @return Float The agreement, 0..1.
 */
fun pathAgreement(first: String, second: String): Float {
	if (first == second) {
		return 1f
	}
	val left = first.split('/').filter { segment -> segment.isNotEmpty() }
	val right = second.split('/').filter { segment -> segment.isNotEmpty() }
	val longest = max(left.size, right.size)
	if (longest == 0) {
		return 1f
	}
	var shared = 0
	while (shared < min(left.size, right.size) && left[shared] == right[shared]) {
		shared++
	}
	return shared.toFloat() / longest.toFloat()
}

/**
 * How alike two rasters look, over a coarse grid each is resampled to: half the overlap of their opaque
 * shapes, half one minus the mean color distance where both are opaque.  Sizes need not agree - a
 * layer that grew resamples to the same grid - and two empty rasters count as alike.
 *
 * @param LayerRaster first  One raster.
 * @param LayerRaster second The other.
 * @return Float The similarity, 0..1.
 */
fun pixelSimilarity(first: LayerRaster, second: LayerRaster): Float {
	if (first.width <= 0 || first.height <= 0 || second.width <= 0 || second.height <= 0) {
		return 0f
	}
	var both = 0
	var either = 0
	var colorDistance = 0f
	for (gridY in 0 until PIXEL_GRID) {
		for (gridX in 0 until PIXEL_GRID) {
			val firstIndex = sampleIndex(first, gridX, gridY)
			val secondIndex = sampleIndex(second, gridX, gridY)
			val firstOpaque = (first.rgba[firstIndex + 3].toInt() and 0xFF) >= OPAQUE_ALPHA
			val secondOpaque = (second.rgba[secondIndex + 3].toInt() and 0xFF) >= OPAQUE_ALPHA
			if (firstOpaque || secondOpaque) {
				either++
			}
			if (firstOpaque && secondOpaque) {
				both++
				var channels = 0
				for (channel in 0 until 3) {
					channels += abs((first.rgba[firstIndex + channel].toInt() and 0xFF) - (second.rgba[secondIndex + channel].toInt() and 0xFF))
				}
				colorDistance += channels.toFloat() / (3f * 255f)
			}
		}
	}
	if (either == 0) {
		return 1f
	}
	val shape = both.toFloat() / either.toFloat()
	val color = if (both == 0) 0f else 1f - colorDistance / both.toFloat()
	return (0.5f * shape + 0.5f * color).coerceIn(0f, 1f)
}

/**
 * The byte offset of the raster pixel a grid cell samples (nearest neighbor).
 *
 * @param LayerRaster raster The raster.
 * @param Int         gridX  The cell's column on the grid.
 * @param Int         gridY  The cell's row on the grid.
 * @return Int The offset of the pixel's red byte.
 */
private fun sampleIndex(raster: LayerRaster, gridX: Int, gridY: Int): Int {
	val x = min(raster.width - 1, gridX * raster.width / PIXEL_GRID)
	val y = min(raster.height - 1, gridY * raster.height / PIXEL_GRID)
	return (y * raster.width + x) * 4
}

/**
 * A name lowercased with its whitespace collapsed, so spelling differences that no artist means count for nothing.
 *
 * @param String name The name.
 * @return String The normalized name.
 */
private fun normalizeName(name: String): String = name.trim().lowercase().split(Regex("\\s+")).filter { part -> part.isNotEmpty() }.joinToString(" ")

/**
 * The Levenshtein distance between two strings.
 *
 * @param String first  One string.
 * @param String second The other.
 * @return Int The edit distance.
 */
private fun editDistance(first: String, second: String): Int {
	if (first.isEmpty()) {
		return second.length
	}
	if (second.isEmpty()) {
		return first.length
	}
	var previous = IntArray(second.length + 1) { column -> column }
	var current = IntArray(second.length + 1)
	for (row in 1..first.length) {
		current[0] = row
		for (column in 1..second.length) {
			val substitution = previous[column - 1] + if (first[row - 1] == second[column - 1]) 0 else 1
			current[column] = min(min(previous[column] + 1, current[column - 1] + 1), substitution)
		}
		val swap = previous
		previous = current
		current = swap
	}
	return previous[second.length]
}