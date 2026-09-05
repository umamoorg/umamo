package org.umamo.format.psd

import org.umamo.format.art.LayerRaster
import org.umamo.format.art.isEffectivelyVisible
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end reader test against a real .psd. Corpus-gated: it uses -Dpsd.sample=<file> when given,
 * else auto-discovers a sample under test/corpus/ (or test/) by walking up from the working
 * directory; if none is present it self-skips so CI stays green without a committed corpus.
 *
 * Asserts the structural invariants that matter to the render and re-import paths: a positive canvas
 * size, at least one decoded layer, every layer's RGBA buffer sized to its bounds, non-blank ids,
 * and - the visibility/folder work this guards - that every layer's groupPath resolves to an emitted
 * group, the order values form a 0..n-1 permutation, and the visibility cascade runs cleanly.
 */
class PsdReaderTest {
	/**
	 * Locates every PSD sample: `-Dpsd.sample` when set, else every `.psd` under `test/corpus/psd`
	 * (with `test/corpus` and `test` kept as fallbacks for a loose sample).
	 *
	 * @return List<File> The samples, empty when none are configured.
	 */
	private fun locateSamples(): List<File> {
		System.getProperty("psd.sample")?.let { path ->
			return listOfNotNull(File(path).takeIf(File::isFile))
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			for (subdirectory in listOf("test/corpus/psd", "test/corpus", "test")) {
				val samples =
					File(directory, subdirectory).takeIf(File::isDirectory)
						?.listFiles { file -> file.extension.equals("psd", ignoreCase = true) }
						?.sortedBy { it.name }
						.orEmpty()
				if (samples.isNotEmpty()) {
					return samples
				}
			}
			directory = directory.parentFile
		}
		return emptyList()
	}

	@Test
	fun readsLayersFromRealPsd() {
		val samples = locateSamples()
		if (samples.isEmpty()) {
			println("no psd.sample and no test/corpus PSD samples; skipping PSD reader test")
			return
		}
		for (sample in samples) {
			checkSample(sample)
		}
	}

	/**
	 * Asserts the reader's structural invariants for one sample.
	 *
	 * @param File sample The `.psd` to read.
	 */
	private fun checkSample(sample: File) {
		val bytes = sample.readBytes()
		// Detection is asserted with the decode so matches() cannot rot untested (see KraReader).
		assertTrue(PsdReader.matches(bytes), "${sample.name}: detected as PSD by magic")
		val art = PsdReader.read(bytes)

		assertTrue(art.widthPx > 0 && art.heightPx > 0, "${sample.name}: positive canvas size")
		assertTrue(art.layers.isNotEmpty(), "${sample.name}: at least one layer")

		for (layer in art.layers) {
			assertTrue(layer.id.raw.isNotBlank(), "${sample.name}: layer '${layer.name}' has a stable id")
			// The key's strength must say what the key IS: a lyid is stable, the name-and-order fallback is not.
			assertEquals(
				layer.id.raw.startsWith("lyid:"),
				layer.idIsStable,
				"${sample.name}: layer '${layer.name}' key '${layer.id.raw}' reports its own stability",
			)
			assertTrue(
				layer.raster.rgba.size == layer.raster.width * layer.raster.height * 4,
				"${sample.name}: layer '${layer.name}' raster sized to its dimensions",
			)
		}

		// Order must be a 0..n-1 permutation (folder markers are not emitted, so emitted layers renumber).
		val orderValues = art.layers.map { layer -> layer.order }.sorted()
		assertTrue(
			orderValues == (0 until art.layers.size).toList(),
			"${sample.name}: layer order values form a 0..n-1 permutation",
		)

		// Folder surfacing: every group has a non-blank path, and every layer that names an enclosing
		// folder resolves to one - the section-divider stack and groupPath assignment must stay aligned.
		val groupPaths = art.groups.map { group -> group.path }.toSet()
		for (group in art.groups) {
			assertTrue(group.path.isNotBlank(), "${sample.name}: group '${group.name}' has a path")
		}
		for (layer in art.layers) {
			assertTrue(
				layer.groupPath.isEmpty() || layer.groupPath in groupPaths,
				"${sample.name}: layer '${layer.name}' groupPath '${layer.groupPath}' resolves to a group",
			)
			art.isEffectivelyVisible(layer)
		}

		// Raster layer masks are baked into the alpha, and both halves of that are checked against the
		// unmasked decode of the same record: a masked layer keeps SOME of its art wherever its mask
		// holds a non-zero value over the layer (an all-transparent result is the mask landing off its
		// layer, which is what a mis-read rectangle looks like), and for a black-default mask every
		// layer pixel outside the rectangle decodes transparent.
		var maskedLayers = 0
		var checkedPixels = 0
		val parse = PsdLayerRecords.parse(bytes)
		for (record in parse.records) {
			val mask = record.userMask ?: continue
			if (mask.disabled || mask.bounds.width <= 0 || mask.bounds.height <= 0) {
				continue
			}
			maskedLayers++
			val layer = art.layers.firstOrNull { candidate -> candidate.name == record.name && candidate.bounds == record.bounds } ?: continue
			val raster = layer.raster
			val unmasked = PsdRaster.decodeLayer(bytes, parse.header, parse.colorModeData, record.copy(userMask = null, realUserMask = null))
			val unmaskedOpaque = opaquePixelCount(unmasked.rgba)
			val maskedOpaque = opaquePixelCount(raster.rgba)
			assertTrue(maskedOpaque <= unmaskedOpaque, "${sample.name}: layer '${layer.name}' mask can only hide pixels ($maskedOpaque vs $unmaskedOpaque)")
			val originX = mask.bounds.left - record.bounds.left
			val originY = mask.bounds.top - record.bounds.top
			if (unmaskedOpaque > 0 && maskPlaneHasValueOverLayer(bytes, parse, record, mask, originX, originY, unmasked)) {
				assertTrue(maskedOpaque > 0, "${sample.name}: layer '${layer.name}' lost every pixel to its mask - the mask rectangle is off its layer")
			}
			if (mask.defaultColor != 0) {
				continue
			}
			for (row in 0 until raster.height) {
				for (column in 0 until raster.width) {
					val inside = (row - originY) in 0 until mask.bounds.height && (column - originX) in 0 until mask.bounds.width
					if (inside) {
						continue
					}
					checkedPixels++
					assertEquals(
						0,
						raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF,
						"${sample.name}: layer '${layer.name}' pixel ($column, $row) lies outside its black-default mask and must be transparent",
					)
				}
			}
		}
		println("checked ${sample.name}: ${art.widthPx}x${art.heightPx}, ${art.layers.size} layers, ${art.groups.size} groups, $maskedLayers masked layers ($checkedPixels outside-mask pixels checked)")
	}

	/**
	 * How many pixels of an RGBA8888 raster have any alpha.
	 *
	 * @param ByteArray rgba The raster.
	 * @return Int The count.
	 */
	private fun opaquePixelCount(rgba: ByteArray): Int {
		var count = 0
		var index = 3
		while (index < rgba.size) {
			if (rgba[index].toInt() != 0) {
				count++
			}
			index += 4
		}
		return count
	}

	/**
	 * Whether the mask plane holds a non-zero value at some pixel where the unmasked layer has alpha -
	 * the condition under which the bake must keep at least one pixel.  The plane is decoded through
	 * the reader's own channel path by presenting the mask channel as a color channel of a layer sized
	 * to the mask rectangle.
	 *
	 * @param ByteArray      bytes    The `.psd` bytes.
	 * @param PsdParse       parse    The parsed file.
	 * @param PsdLayerRecord record   The masked layer's record.
	 * @param PsdLayerMask   mask     Its mask geometry.
	 * @param Int            originX  The mask rectangle's left edge in layer pixels.
	 * @param Int            originY  The mask rectangle's top edge in layer pixels.
	 * @param LayerRaster    unmasked The layer decoded without its mask.
	 * @return Boolean True when the mask reveals at least one pixel that has alpha.
	 */
	private fun maskPlaneHasValueOverLayer(
		bytes: ByteArray,
		parse: PsdParse,
		record: PsdLayerRecord,
		mask: PsdLayerMask,
		originX: Int,
		originY: Int,
		unmasked: LayerRaster,
	): Boolean {
		var offset = record.channelDataOffset
		var maskChannel: PsdChannelInfo? = null
		for (channel in record.channels) {
			if (channel.id == -2) {
				maskChannel = channel
				break
			}
			offset += channel.length.toInt()
		}
		val channel = maskChannel ?: return false
		val asColor = record.copy(bounds = mask.bounds, channels = listOf(PsdChannelInfo(0, channel.length)), channelDataOffset = offset, userMask = null, realUserMask = null)
		val plane = PsdRaster.decodeLayer(bytes, parse.header, parse.colorModeData, asColor)
		for (row in 0 until unmasked.height) {
			val maskRow = row - originY
			if (maskRow !in 0 until plane.height) {
				continue
			}
			for (column in 0 until unmasked.width) {
				val maskColumn = column - originX
				if (maskColumn !in 0 until plane.width) {
					continue
				}
				val hasAlpha = unmasked.rgba[(row * unmasked.width + column) * 4 + 3].toInt() != 0
				val maskValue = plane.rgba[(maskRow * plane.width + maskColumn) * 4].toInt() and 0xFF
				if (hasAlpha && maskValue != 0) {
					return true
				}
			}
		}
		return false
	}
}