package org.umamo.render

import org.junit.Assume
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasIngest
import org.umamo.runtime.model.AtlasTileId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The source-artwork display plan on real documents: how many drawables it maps, and how many it
 * leaves on the atlas.
 *
 * The renderer engages the mode only when EVERY layer the plan maps is resident, so a plan that maps
 * nothing leaves the whole puppet on its atlas - silently, because that is also what a document with
 * no artwork looks like.  This is the measurement that tells those two apart.
 */
class SourceLayerPlanCorpusTest {
	private fun corpusFiles(): List<File> =
		System.getProperty("cmo3.probe")
			.orEmpty()
			.split(',')
			.map { entry -> entry.trim() }
			.filter { entry -> entry.isNotEmpty() }
			.map(::File)
			.filter { file -> file.isFile }

	@Test
	fun theArtworkPlanMapsCorpusDrawables() {
		val files = corpusFiles()
		Assume.assumeTrue("no cmo3.probe corpus models", files.isNotEmpty())

		var mappedTotal = 0
		var decodedTotal = 0
		val lines = mutableListOf<String>()
		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: continue
			val puppet = Cmo3Import.fromModelSource(root).let { model -> model.copy(rendersFromSourceLayers = true) }
			if (puppet.atlas.tiles.isEmpty()) {
				continue
			}
			val plan = buildLayerDrawPlan(puppet)
			val meshed = puppet.drawables.count { drawable -> drawable.mesh != null }
			mappedTotal += plan.drawsByDrawableId.size
			// The delivery half, assembled exactly as the document loader does it: the renderer engages
			// only when EVERY mapped layer decodes, so a plan that maps everything and decodes nothing
			// leaves the whole puppet on its atlas - and looks identical to having no artwork at all.
			val model = Cmo3.read(file)
			val tileResources = cmo3AtlasIngest(root).imageResourceByTile
			val rasters = SourceArtRasters { tileId -> tileResources[tileId]?.let(model::extractLayerPng) }
			val decoded = plan.layerByteCostByKey.keys.count { key -> rasters.decodeRaster(AtlasTileId(key)) != null }
			decodedTotal += decoded
			lines.add(
				"${file.name}: ${plan.drawsByDrawableId.size}/$meshed mapped, " +
					"${plan.unresolvedDrawableCount} unresolved, $decoded/${plan.layerByteCostByKey.size} layers decoded, " +
					"pagesAddressed=${puppet.atlas.storedUvsAddressPages}",
			)
		}
		lines.take(40).forEach { line -> println("[plan] $line") }
		assertTrue(mappedTotal > 0, "no corpus drawable mapped to artwork; the plan is empty everywhere")
		assertTrue(decodedTotal > 0, "no mapped layer decoded; the renderer would never engage the mode")
	}
}