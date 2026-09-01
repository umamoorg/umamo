package org.umamo.format.atlas

import org.umamo.format.art.ArtReader
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.clip.ClipReader
import org.umamo.format.kra.KraReader
import org.umamo.format.png.PngCodec
import org.umamo.format.psd.PsdReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Packs every layered corpus sample (PSD, CLIP, KRA) and asserts the packer's contract on real art.
 * Corpus-gated: auto-discovers test/corpus by walking up from the working directory and self-skips
 * when absent so CI stays green without a committed corpus.
 *
 * Four claims, all invariant rather than golden - a stored page image would pin this algorithm
 * instead of the contract, and the contract is what a repack has to keep:
 *
 * - every packed tile reads back out of its page byte for byte,
 * - no two tiles come within the gutter of each other,
 * - each page survives a PNG encode/decode round trip unchanged,
 * - and packing the same document twice produces the same pages.
 */
class AtlasPackCorpusTest {
	/**
	 * Locates every layered corpus sample with the reader that decodes it.
	 *
	 * @return List<Pair<File, ArtReader>> The samples, empty when no corpus is present.
	 */
	private fun locateSamples(): List<Pair<File, ArtReader>> {
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				val samples = mutableListOf<Pair<File, ArtReader>>()
				for ((subdirectory, reader) in listOf("psd" to PsdReader, "clip" to ClipReader, "krita" to KraReader)) {
					File(corpus, subdirectory).listFiles { file -> file.isFile }
						?.sortedBy { file -> file.name }
						?.forEach { file -> samples.add(file to reader) }
				}
				return samples
			}
			directory = directory.parentFile
		}
		return emptyList()
	}

	@Test
	fun packingInvariantsHoldOnCorpusDocuments() {
		val samples = locateSamples()
		if (samples.isEmpty()) {
			println("no test/corpus directory; skipping atlas-pack corpus test")
			return
		}
		for ((sample, reader) in samples) {
			checkSample(sample, reader)
		}
	}

	/**
	 * Packs one sample's raster layers and asserts the invariants.
	 *
	 * @param File sample      The layered source file to read.
	 * @param ArtReader reader The reader that decodes it.
	 */
	private fun checkSample(sample: File, reader: ArtReader) {
		val options = AtlasPackOptions(maxPageSize = 4096)
		val art = reader.read(sample.readBytes())
		val items = art.atlasPackItems { layer -> layer.kind == SourceLayerKind.Raster }
		if (items.isEmpty()) {
			println("checked ${sample.name}: no raster layers")
			return
		}

		val startNanos = System.nanoTime()
		val result = packAtlas(items, options)
		val packedNanos = System.nanoTime()

		assertEquals(
			items.map { item -> item.key }.sorted(),
			(result.placements.map { placement -> placement.key } + result.skipped.map { skip -> skip.key }).sorted(),
			"${sample.name}: every layer must be placed or reported",
		)
		assertTilesRoundTripByteExact(items, result)
		assertNoTileOverlap(result, options.gutter)
		for ((pageIndex, page) in result.pages.withIndex()) {
			val decoded = PngCodec.read(PngCodec.write(page))
			assertEquals(page.width, decoded.width, "${sample.name}: page $pageIndex width survives PNG")
			assertEquals(page.height, decoded.height, "${sample.name}: page $pageIndex height survives PNG")
			assertContentEquals(page.rgba, decoded.rgba, "${sample.name}: page $pageIndex pixels survive PNG")
		}

		val repacked = packAtlas(items, options)
		assertContentEquals(
			result.placements,
			repacked.placements,
			"${sample.name}: an unchanged document must repack identically",
		)
		for (pageIndex in result.pages.indices) {
			assertContentEquals(
				result.pages[pageIndex].rgba,
				repacked.pages[pageIndex].rgba,
				"${sample.name}: page $pageIndex pixels must repack identically",
			)
		}
		val occupancy =
			result.pages.indices.joinToString(", ") { pageIndex ->
				val page = result.pages[pageIndex]
				"${page.width}x${page.height} ${(result.pageOccupancy(pageIndex) * 100f).toInt()}%"
			}
		// Oversized layers are printed rather than asserted away: real source art does contain a
		// layer wider than a 4096 page (ricardo_en.clip), and the packer reporting it is the
		// contract - packing it would need downscaling, which the packer deliberately does not do.
		val oversized = result.skipped.filter { skip -> skip.reason == AtlasPackSkipReason.LargerThanPage }
		val oversizedNote =
			if (oversized.isEmpty()) {
				""
			} else {
				", oversized: ${oversized.joinToString(", ") { skip -> skip.key }}"
			}
		println(
			"checked ${sample.name}: ${result.placements.size} packed, ${result.skipped.size} skipped, " +
				"${result.pages.size} page(s) [$occupancy], ${(packedNanos - startNanos) / 1_000_000} ms$oversizedNote",
		)
	}
}