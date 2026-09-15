package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.cropped
import org.umamo.format.raster.fittedInto
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Our icon fit against the official editor's own icons, over every layer and model image of the
 * corpus sample: the same art fitted into the same square lands on the same pixels - the alpha
 * bounding box within a pixel, the covered pixels' channels close on average.  This is what pins
 * the fit rule (longer edge fills, aspect held, centered) to the editor's, rather than to a
 * guess.  The whole-art fit is the rule the corpus supports (517 of 528 Erica icons within a pixel;
 * a fit of the art trimmed to its non-transparent bounds lands 506); the bounds are loose on
 * purpose, since the editor's resampling filter is its own and a per-pixel match is not the claim.
 *
 * Skips without the corpus sample.
 */
class Cmo3IconDifferentialTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	/** The alpha bounding box of a square icon as (left, top, width, height), or null when blank. */
	private fun alphaBox(image: RasterImage): IntArray? {
		var minX = Int.MAX_VALUE
		var minY = Int.MAX_VALUE
		var maxX = -1
		var maxY = -1
		for (y in 0 until image.height) {
			for (x in 0 until image.width) {
				if ((image.rgba[(y * image.width + x) * 4 + 3].toInt() and 0xFF) > 0) {
					minX = minOf(minX, x)
					minY = minOf(minY, y)
					maxX = maxOf(maxX, x)
					maxY = maxOf(maxY, y)
				}
			}
		}
		return if (maxX < 0) null else intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
	}

	/** The mean absolute channel difference over pixels either image covers, premultiplied so a fringe compares by what it shows. */
	private fun meanDifference(ours: RasterImage, official: RasterImage): Double {
		var total = 0.0
		var count = 0
		for (pixel in 0 until ours.width * ours.height) {
			val offset = pixel * 4
			val ourAlpha = ours.rgba[offset + 3].toInt() and 0xFF
			val theirAlpha = official.rgba[offset + 3].toInt() and 0xFF
			if (ourAlpha == 0 && theirAlpha == 0) {
				continue
			}
			for (channel in 0 until 3) {
				val ourPremultiplied = (ours.rgba[offset + channel].toInt() and 0xFF) * ourAlpha / 255.0
				val theirPremultiplied = (official.rgba[offset + channel].toInt() and 0xFF) * theirAlpha / 255.0
				total += abs(ourPremultiplied - theirPremultiplied)
			}
			total += abs(ourAlpha - theirAlpha)
			count += 4
		}
		return if (count == 0) 0.0 else total / count
	}

	private class Comparison(val name: String, val size: Int, val artWidth: Int, val artHeight: Int, val boxDelta: Int, val trimmedBoxDelta: Int, val meanDifference: Double, val trimmedMeanDifference: Double, val ourBox: IntArray?, val trimmedBox: IntArray?, val theirBox: IntArray?)

	/** The art trimmed to its non-transparent bounds, or the art itself when it has none or is already tight. */
	private fun trimmed(art: RasterImage): RasterImage {
		val box = alphaBox(art) ?: return art
		return art.cropped(org.umamo.format.art.LayerBounds(box[0], box[1], box[2], box[3]))
	}

	private fun boxDeltaOf(ours: IntArray?, theirs: IntArray?, size: Int): Int =
		if (ours == null || theirs == null) {
			if (ours == null && theirs == null) 0 else size
		} else {
			(0 until 4).maxOf { component -> abs(ours[component] - theirs[component]) }
		}

	@Test
	fun ourFitLandsOnTheOfficialIcons() {
		val file = sample
		if (file == null) {
			println("cmo3.sample not present; skipping the icon differential")
			return
		}
		val cmo3 = Cmo3.read(file.readBytes())
		val root = cmo3.root as CModelSource
		val textureManager = root.textureManager as CTextureManager
		val comparisons = ArrayList<Comparison>()

		/**
		 * Compares our fit of [art] at [size] with the official icon at [iconSlot].
		 *
		 * @param String      name     The owner's name, for the report.
		 * @param RasterImage art      The art the icon shows.
		 * @param Int         size     The square's edge.
		 * @param Any?        iconSlot The official CImageIcon.
		 */
		fun compare(name: String, art: RasterImage, size: Int, iconSlot: Any?) {
			val path = Cmo3Icons.archivePathOf(iconSlot) ?: return
			val official = PngCodec.read(cmo3.archive.byPath(path)?.content ?: return)
			if (official.width != size || official.height != size) {
				return
			}
			val ours = art.fittedInto(size)
			val oursTrimmed = trimmed(art).fittedInto(size)
			val theirBox = alphaBox(official)
			val ourBox = alphaBox(ours)
			val trimmedBox = alphaBox(oursTrimmed)
			comparisons.add(
				Comparison(
					name,
					size,
					art.width,
					art.height,
					boxDeltaOf(ourBox, theirBox, size),
					boxDeltaOf(trimmedBox, theirBox, size),
					meanDifference(ours, official),
					meanDifference(oursTrimmed, official),
					ourBox,
					trimmedBox,
					theirBox,
				),
			)
		}
		for (wrapper in Cmo3Import.elementsOf(textureManager._rawImages).filterIsInstance<LayeredImageWrapper>()) {
			val image = wrapper.image as? CLayeredImage ?: continue
			for (walked in walkLayeredImage(image)) {
				val resource = walked.layer.imageResource as? CImageResource ?: continue
				val art = PngCodec.read(cmo3.extractLayerPng(resource) ?: continue)
				compare("layer ${walked.layer.name}", art, 64, walked.layer.icon64)
				compare("layer ${walked.layer.name}", art, 16, walked.layer.icon16)
			}
		}
		for (group in Cmo3Import.elementsOf(textureManager._modelImageGroups).filterIsInstance<CModelImageGroup>()) {
			for (modelImage in Cmo3Import.elementsOf(group._modelImages).filterIsInstance<CModelImage>()) {
				val resource = modelImage._filteredImage as? CImageResource ?: continue
				val art = PngCodec.read(cmo3.extractLayerPng(resource) ?: continue)
				compare("model image ${modelImage.name}", art, 16, modelImage.icon16)
			}
		}
		assertTrue(comparisons.isNotEmpty(), "the sample carries icons to compare")
		println(
			"icon differential: ${comparisons.size} icons; whole-art fit: box<=1 ${comparisons.count { comparison -> comparison.boxDelta <= 1 }}, mean avg ${"%.2f".format(comparisons.map { comparison -> comparison.meanDifference }.average())}; " +
				"trimmed fit: box<=1 ${comparisons.count { comparison -> comparison.trimmedBoxDelta <= 1 }}, mean avg ${"%.2f".format(comparisons.map { comparison -> comparison.trimmedMeanDifference }.average())}",
		)
		for (comparison in comparisons.sortedByDescending { comparison -> comparison.boxDelta }.take(6)) {
			println("  box outlier ${comparison.name}@${comparison.size} art ${comparison.artWidth}x${comparison.artHeight}: ours ${comparison.ourBox?.toList()} trimmed ${comparison.trimmedBox?.toList()} theirs ${comparison.theirBox?.toList()} mean ${"%.1f".format(comparison.meanDifference)} / trimmed ${"%.1f".format(comparison.trimmedMeanDifference)}")
		}
		for (comparison in comparisons.sortedByDescending { comparison -> comparison.meanDifference }.take(6)) {
			println("  mean outlier ${comparison.name}@${comparison.size} art ${comparison.artWidth}x${comparison.artHeight}: ours ${comparison.ourBox?.toList()} trimmed ${comparison.trimmedBox?.toList()} theirs ${comparison.theirBox?.toList()} mean ${"%.1f".format(comparison.meanDifference)} / trimmed ${"%.1f".format(comparison.trimmedMeanDifference)}")
		}
		// The rule is pinned by the population, the per-icon bounds catch a gross error: nearly every
		// icon's box lands within a pixel of the editor's (the rest are soft-alpha layers whose faint
		// fringe the editor's filter drops), and the thin 16px icons carry the largest per-icon means
		// because a one-pixel width difference on a five-pixel sliver is a large fraction of it.
		val withinAPixel = comparisons.count { comparison -> comparison.boxDelta <= 1 }
		assertTrue(withinAPixel >= comparisons.size * 95 / 100, "at least 95% of the boxes land within a pixel: $withinAPixel of ${comparisons.size}")
		val averageMean = comparisons.map { comparison -> comparison.meanDifference }.average()
		assertTrue(averageMean <= AVERAGE_MEAN_DIFFERENCE_BOUND, "the icons are close to the editor's on average: ${"%.2f".format(averageMean)}")
		val boxOutliers = comparisons.filter { comparison -> comparison.boxDelta > BOX_DELTA_BOUND }
		assertTrue(boxOutliers.isEmpty(), "alpha boxes near the official icons: ${boxOutliers.take(8).map { comparison -> "${comparison.name}@${comparison.size}=${comparison.boxDelta}" }}")
		val meanOutliers = comparisons.filter { comparison -> comparison.meanDifference > MEAN_DIFFERENCE_BOUND }
		assertTrue(meanOutliers.isEmpty(), "covered pixels close to the official icons: ${meanOutliers.take(8).map { comparison -> "${comparison.name}@${comparison.size}=${"%.1f".format(comparison.meanDifference)}" }}")
	}

	private companion object {
		/** How far one icon's alpha box may sit from the editor's, in pixels of the square (Erica: 4, a soft shadow at 16px). */
		const val BOX_DELTA_BOUND = 4

		/** The mean premultiplied channel difference, out of 255, one icon may show against the editor's (Erica: 68.8, a thin sliver at 16px). */
		const val MEAN_DIFFERENCE_BOUND = 80.0

		/** The average of those means over every icon (Erica: 16.5). */
		const val AVERAGE_MEAN_DIFFERENCE_BOUND = 24.0
	}
}