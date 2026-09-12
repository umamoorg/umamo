package org.umamo.interop.art

import org.umamo.format.FormatRegistry
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.art.analyzeAlpha
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the bridge over every real layered file in the local corpus (PSD, CLIP, KRA), which is the
 * only way to see what a reader actually emits - folder paths no group describes, weak keys that
 * collide, text and empty layers - land on the model as the synthetic test says they should.
 *
 * Corpus-gated the way the readers' own tests are: every sample under `test/corpus/{psd,clip,krita}`
 * found by walking up from the working directory, self-skipping with a printed line when none exist.
 */
class SourceArtImportCorpusTest {
	private fun locateSamples(): List<File> {
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				return listOf("psd", "clip", "krita")
					.flatMap { folder -> File(corpus, folder).listFiles { file -> file.isFile }.orEmpty().toList() }
					.filter { file -> file.extension.lowercase() in setOf("psd", "clip", "kra") }
					.sortedBy { file -> file.name }
			}
			directory = directory.parentFile
		}
		return emptyList()
	}

	@Test
	fun everyCorpusArtworkImportsToARig() {
		val samples = locateSamples()
		if (samples.isEmpty()) {
			println("no test/corpus artwork samples; skipping the artwork import corpus test")
			return
		}
		for (sample in samples) {
			checkSample(sample)
		}
	}

	private fun checkSample(sample: File) {
		val bytes = sample.readBytes()
		val codec = assertNotNull(FormatRegistry.detect(bytes, sample.name), "${sample.name}: detected")
		val art = codec.read(bytes) as SourceArt
		val result = SourceArtImport.fromSourceArt(art, ArtSourceDescriptor(sample.name, sample.path, codec.kind.extension))
		val puppet = result.puppet

		// One drawable per raster layer with art in it, named after the layer, top-most first.
		val expectedNames =
			art.layers
				.sortedBy { layer -> layer.order }
				.filter { layer -> layer.kind == SourceLayerKind.Raster && layer.analyzeAlpha() != null }
				.map { layer -> layer.name }
		assertEquals(expectedNames, puppet.drawables.map { drawable -> drawable.name }, "${sample.name}: drawables")
		assertEquals(puppet.drawables.size, puppet.drawables.map { drawable -> drawable.id }.toSet().size, "${sample.name}: drawable ids are unique")
		assertEquals(puppet.atlas.tiles.size, puppet.atlas.tiles.map { tile -> tile.id }.toSet().size, "${sample.name}: tile ids are unique")

		// Every drawable samples a tile whose size is its layer's raster, bound to that layer's key.
		for (drawable in puppet.drawables) {
			val tile = assertNotNull(puppet.atlas.tileById[assertNotNull(drawable.atlasTileId)], "${sample.name}: ${drawable.name} has a tile")
			val raster = assertNotNull(result.rasterByTile[tile.id], "${sample.name}: ${drawable.name} has pixels")
			assertEquals(raster.width, tile.width, "${sample.name}: ${drawable.name} tile width")
			assertEquals(raster.height, tile.height, "${sample.name}: ${drawable.name} tile height")
			val source = assertNotNull(tile.source, "${sample.name}: ${drawable.name} is bound to its source layer")
			assertEquals(puppet.sources.single().id, source.sourceId)
			assertTrue(puppet.sources.single().layers.any { entry -> entry.key == source.layerKey }, "${sample.name}: the binding names an inventory layer")
		}
		assertEquals(art.layers.size, puppet.sources.single().layers.size, "${sample.name}: the inventory lists every layer")
		assertTrue(puppet.parts.size >= art.groups.size, "${sample.name}: every described folder is a part")
		assertEquals(art.widthPx.toFloat(), puppet.canvasWidth, "${sample.name}: canvas width")
		println("imported ${sample.name}: ${puppet.drawables.size} drawables, ${puppet.parts.size} parts, ${result.notices.size} notes")
	}
}