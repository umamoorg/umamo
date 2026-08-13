package org.umamo.render

import org.junit.Assume
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.identity.Guid
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the rule that lets the per-drawable "is this drawable packed" decision be reconstructed from
 * MODEL state alone, so the recovery can move out of the renderer and into the importer.
 *
 * The recovery decides it today by resource identity - whether the resource a drawable's GTexture2D
 * samples is one of the texture manager's own atlas page images - which is a fact about the CMO3 graph
 * that a PuppetModel cannot hold.  The claim is that the same answer falls out of two things the model
 * DOES carry: the document's display mode, and whether the drawable's tile was ever packed at all.
 *
 *     samples a page  ==  !isTextureInputModelImageMode  &&  the tile has a ModelImageEntry
 *
 * If that holds across the corpus the move is behaviour-preserving; if it does not, a drawable would
 * silently swap between the layer frame and the atlas frame in the UV editor, which is why this is
 * measured against the live implementation rather than assumed.  It also pins the page dimensions,
 * which the recovery reads off the sampled resource and the model would read off the page record.
 *
 * Gated on `cmo3.probe` (the whole corpus, comma-separated); self-skips by JUnit assumption when it
 * names no readable file, so an unfed run reports skipped rather than green.
 */
class Cmo3PlacementRuleCorpusTest {
	private fun corpusFiles(): List<File> =
		System.getProperty("cmo3.probe")
			.orEmpty()
			.split(',')
			.map { entry -> entry.trim() }
			.filter { entry -> entry.isNotEmpty() }
			.map(::File)
			.filter { file -> file.isFile }

	@Test
	fun theModelReconstructsTheSamplesAPageDecision() {
		val samples = corpusFiles()
		Assume.assumeTrue("cmo3.probe names no readable file; skipping", samples.isNotEmpty())

		var checkedDrawables = 0
		var packedDrawables = 0
		val disagreements = mutableListOf<String>()
		val dimensionDisagreements = mutableListOf<String>()

		for (sample in samples) {
			val model = Cmo3.read(sample.readBytes())
			val root = model.root as? CModelSource ?: continue
			val layers = cmo3LayerTextures(root) { resource -> model.extractLayerPng(resource) }
			if (layers.isEmpty) {
				continue
			}
			val textureManager = root.textureManager as? CTextureManager ?: continue
			val displaysFromSourceLayers = textureManager.isTextureInputModelImageMode

			// The two model-side facts: which tiles were packed, and each page's recorded size.
			val atlases = elementsOfAtlases(textureManager)
			val pageSizeByGuid = HashMap<String, Pair<Int, Int>>()
			val packedGuids = HashSet<String>()
			for (atlas in atlases) {
				for (entry in atlasEntries(atlas)) {
					val guid = (entry.modelImageGuid as? Guid)?.uuid?.takeIf { candidate -> candidate.isNotEmpty() } ?: continue
					packedGuids.add(guid)
					pageSizeByGuid[guid] = atlas.width to atlas.height
				}
			}

			for ((drawableId, binding) in layers.bindingsByDrawableId) {
				checkedDrawables++
				val recovered = binding.placement != null
				val reconstructed = !displaysFromSourceLayers && binding.layerKey in packedGuids
				if (recovered != reconstructed) {
					disagreements.add(
						"${sample.name}: '$drawableId' recovered=$recovered reconstructed=$reconstructed " +
							"(layerMode=$displaysFromSourceLayers packed=${binding.layerKey in packedGuids})",
					)
					continue
				}
				if (!recovered) {
					continue
				}
				packedDrawables++
				val pageSize = pageSizeByGuid[binding.layerKey]
				if (pageSize == null || pageSize.first != binding.pageWidth || pageSize.second != binding.pageHeight) {
					dimensionDisagreements.add(
						"${sample.name}: '$drawableId' sampled=${binding.pageWidth}x${binding.pageHeight} " +
							"pageRecord=${pageSize?.first}x${pageSize?.second}",
					)
				}
			}
		}

		println(
			"checked $checkedDrawables bound drawables across ${samples.size} samples, $packedDrawables packed",
		)
		assertEquals(
			emptyList(),
			disagreements.take(20),
			"the packed decision must be reconstructible from the display mode plus the tile's packing",
		)
		assertEquals(
			emptyList(),
			dimensionDisagreements.take(20),
			"a packed drawable's page dimensions must match its page record",
		)
	}

	/** The texture manager's atlas pages, in its own order. */
	private fun elementsOfAtlases(textureManager: CTextureManager): List<CTextureAtlas> =
		when (val atlases = textureManager._textureAtlases) {
			is Map<*, *> -> atlases.values.toList()
			is Iterable<*> -> atlases.toList()
			is Array<*> -> atlases.toList()
			else -> emptyList()
		}.filterIsInstance<CTextureAtlas>()

	/** One page's packed model-image entries. */
	private fun atlasEntries(atlas: CTextureAtlas): List<ModelImageEntry> =
		when (val entries = atlas.modelImages) {
			is Map<*, *> -> entries.values.toList()
			is Iterable<*> -> entries.toList()
			is Array<*> -> entries.toList()
			else -> emptyList()
		}.filterIsInstance<ModelImageEntry>()
}