package org.umamo.ui.document

import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.binary.contentHashOf
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.fittedInto
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaWriterInfo
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaRenderPagePixels
import org.umamo.interop.uma.UmaDocumentBridge
import org.umamo.reimport.ArtworkReloadPlanner
import org.umamo.reimport.ReconcileResult
import org.umamo.reimport.ReloadPlan
import org.umamo.render.SourceArtRasters
import org.umamo.render.deriveAtlasTextures
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.ui.model.DrawableThumbnailer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The native format's promise for artwork documents, end to end through the app's own open (docs/format/UMA.md §5,
 * §6): each corpus PSD, CLIP, and KRA opened packed, saved as UMA with its tiles and a thumbnail, and reopened comes
 * back with the same atlas and source records; the pages composed from the reopened tiles are byte for byte the
 * pages the open composed; and a reload of changed art planned before the save and one planned after the reopen are
 * the same plan.
 *
 * Corpus-gated: the PSD / CLIP / KRA files under test/corpus found by walking up from the working directory,
 * self-skipping with a printed line when none exist.
 */
class UmaArtDocumentGateTest {
	private val writer = UmaWriterInfo("Umamo", "gate-test")

	/**
	 * A layer the artist repainted: the same layer with every color channel inverted.
	 *
	 * @property SourceLayer original The layer as it was.
	 */
	private class RepaintedLayer(private val original: SourceLayer) : SourceLayer by original {
		override val raster: LayerRaster =
			LayerRaster(
				original.raster.width,
				original.raster.height,
				original.raster.rgba.copyOf().also { rgba ->
					for (channelIndex in rgba.indices) {
						if (channelIndex % 4 != 3) {
							rgba[channelIndex] = (255 - (rgba[channelIndex].toInt() and 0xFF)).toByte()
						}
					}
				},
			)
	}

	/**
	 * Art with its layers replaced.
	 *
	 * @property SourceArt   original The art as it was.
	 * @property List        layers   The new layers.
	 */
	private class EditedArt(private val original: SourceArt, override val layers: List<SourceLayer>) : SourceArt by original

	/**
	 * A reload plan reduced to text that is equal exactly when the plans are: meshes by their arrays, rasters by their
	 * content hash, and everything else by its data-class form.
	 *
	 * @param ReloadPlan? plan The plan, or null when there is nothing to do.
	 * @return List<String> The lines.
	 */
	private fun fingerprintOf(plan: ReloadPlan?): List<String> {
		if (plan == null) {
			return listOf("no plan")
		}

		/**
		 * A mesh as text.
		 *
		 * @param DrawableMesh? mesh The mesh.
		 * @return String The text.
		 */
		fun meshText(mesh: DrawableMesh?): String = mesh?.let { present -> "${present.positions.contentToString()} ${present.uvs.contentToString()} ${present.indices.contentToString()}" } ?: "none"
		val reload = plan.reload
		val lines = ArrayList<String>()
		lines += "source ${reload.source}"
		reload.replacedTiles.forEach { replaced -> lines += "replaced ${replaced.oldId.raw} with ${replaced.tile}" }
		reload.drawableMeshes.entries.sortedBy { (drawableId, _) -> drawableId.raw }.forEach { (drawableId, mesh) -> lines += "mesh ${drawableId.raw} ${meshText(mesh)}" }
		reload.additions?.let { additions ->
			lines += "added source ${additions.source}"
			additions.tiles.forEach { tile -> lines += "added tile $tile" }
			additions.drawables.forEach { drawable -> lines += "added drawable ${drawable.copy(mesh = null)} ${meshText(drawable.mesh)}" }
			additions.parts.forEach { part -> lines += "added part $part" }
			lines += "added root ${additions.rootChildren} insertions ${additions.insertions}"
		}
		lines += "outgrown ${reload.outgrown} retired ${reload.retiredTiles} visibility ${reload.drawableVisibility}"
		plan.rasterByTile.entries.sortedBy { (tileId, _) -> tileId.raw }.forEach { (tileId, raster) -> lines += "raster ${tileId.raw} ${raster.width}x${raster.height} ${contentHashOf(raster.rgba)}" }
		lines += "report ${plan.report.results}"
		lines += "notices ${plan.notices}"
		lines += "leftovers ${plan.leftovers}"
		return lines
	}

	/**
	 * Every PSD, CLIP, and KRA under test/corpus, found by walking up from the working directory.
	 *
	 * @return List<File> The samples, empty when there is no corpus.
	 */
	private fun locateArtworkSamples(): List<File> {
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

	/**
	 * Every corpus artwork file opens, saves, reopens, derives, and reloads as the promise says.
	 */
	@Test
	fun artworkDocumentsReopenAndReloadAsSaved() {
		val samples = locateArtworkSamples()
		if (samples.isEmpty()) {
			println("no test/corpus artwork samples; skipping the UMA artwork document gate")
			return
		}
		val failures = ArrayList<String>()
		var openedSamples = 0
		var reloadedSamples = 0
		for (sample in samples) {
			try {
				when (checkSample(sample)) {
					SampleOutcome.NotOpenable -> println("[Umamo][uma] ${sample.name}: the app does not open it as a document, so there is nothing to save")
					SampleOutcome.Reopened -> openedSamples++
					SampleOutcome.Reloaded -> {
						openedSamples++
						reloadedSamples++
					}
				}
			} catch (failure: AssertionError) {
				failures += "${sample.name}: ${failure.message?.take(600)}"
			}
		}
		assertTrue(failures.isEmpty(), "${failures.size} samples failed:\n${failures.joinToString("\n")}")
		assertTrue(openedSamples > 0 && reloadedSamples > 0, "the gate saved $openedSamples documents and reloaded $reloadedSamples, so it compared nothing")
		println("[Umamo][uma] $openedSamples artwork documents reopen as saved; $reloadedSamples reload identically after the reopen")
	}

	/** How far one sample got. */
	private enum class SampleOutcome {
		/** The app refuses to open the file as a document (it has no layer with pixels). */
		NotOpenable,

		/** The document saved, reopened, and derived as it should. */
		Reopened,

		/** And a reload planned after the reopen matched one planned before the save. */
		Reloaded,
	}

	/**
	 * One sample's open, save, reopen, derivation, and reload.  The file is parsed once and the document built from
	 * that parse, as the app's open does, so the reload leg reuses the same layers rather than holding a second copy.
	 *
	 * @param File sample The artwork file.
	 * @return SampleOutcome How far the sample got.
	 */
	private fun checkSample(sample: File): SampleOutcome {
		val bytes = sample.readBytes()
		val read = assertNotNull(readArtwork(bytes, sample.name), "${sample.name}: read")
		val load = buildArtDocument(read.art, read.kind, sample.name, sample.path, artworkImportOptions(), read.contentHash)
		if (load is DocumentLoad.Failed) {
			return SampleOutcome.NotOpenable
		}
		val document = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(load).document)
		val puppet = document.puppet
		val tilePngs = puppet.atlas.tiles.associate { tile -> tile.id.raw to document.artRasters.rasterFor(tile.id)?.let { raster -> PngCodec.write(RasterImage(raster.width, raster.height, raster.rgba)) } }
		val thumbnail = DrawableThumbnailer(puppet, document.textures).modelRasterFor()?.fittedInto(256)?.let(PngCodec::write)
		val saved = UmaDocumentBridge.documentOf(UmaModel.create(writer), puppet, UmaPixelSource({ tileId -> tilePngs[tileId] }, UmaRenderPagePixels.Derived, thumbnail))
		val uma = Uma.read(Uma.write(saved))
		val reopened = UmaDocumentBridge.modelOf(uma)
		assertEquals(puppet.atlas, reopened.atlas, "${sample.name}: the atlas")
		assertEquals(puppet.sources, reopened.sources, "${sample.name}: the sources")
		if (thumbnail != null) {
			assertEquals(256, assertNotNull(uma.textures?.thumbnail, "${sample.name}: the thumbnail").width)
		}

		val pages = UmaDocumentBridge.pagesOf(uma)
		assertEquals(null, pages.pageSet, "${sample.name}: a packed artwork document's pages derive")
		val reopenedRasters = SourceArtRasters.fromPng(pages.tilePng)
		val derived = assertNotNull(deriveAtlasTextures(reopened, reopenedRasters, premultipliedAlpha = false), "${sample.name}: the reopened pages derive")
		assertEquals(document.textures.atlases.size, derived.atlases.size, "${sample.name}: page count")
		for ((pageIndex, page) in document.textures.atlases.withIndex()) {
			val reopenedPage = derived.atlases[pageIndex]
			assertTrue(page.width == reopenedPage.width && page.height == reopenedPage.height && page.rgba.contentEquals(reopenedPage.rgba), "${sample.name}: page $pageIndex composes from the reopened tiles byte for byte")
		}
		assertEquals(document.textures.atlasIndexByDrawableId, derived.atlasIndexByDrawableId, "${sample.name}: each drawable's page")

		// The reload leg: the art as the artist might save it next - the first layer repainted, the second deleted,
		// and a copy of the third added under a new key.
		val art = read.art
		val rasterLayers = art.layers.filter { layer -> layer.kind == SourceLayerKind.Raster && puppet.atlas.tiles.any { tile -> tile.source?.layerKey == layer.id.raw } }
		if (rasterLayers.size < 3) {
			return SampleOutcome.Reopened
		}
		val added =
			object : SourceLayer by rasterLayers[2] {
				override val id: LayerId = LayerId("umamo-gate-added")
				override val name: String = "Added by the gate"
			}
		val editedLayers = art.layers.mapNotNull { layer -> if (layer === rasterLayers[0]) RepaintedLayer(layer) else layer.takeIf { candidate -> candidate !== rasterLayers[1] } } + added
		val editedArt = EditedArt(art, editedLayers)
		val sourceId = puppet.sources.single().id
		val options = artworkImportOptions()
		val newHash = contentHashOf("${sample.name} saved again".encodeToByteArray())
		val before = ArtworkReloadPlanner.plan(puppet, sourceId, editedArt, options, { tileId -> document.artRasters.rasterFor(tileId)?.let { raster -> LayerRaster(raster.width, raster.height, raster.rgba) } }, newHash)
		val after = ArtworkReloadPlanner.plan(reopened, sourceId, editedArt, options, { tileId: AtlasTileId -> reopenedRasters.decodeRaster(tileId)?.let { raster -> LayerRaster(raster.width, raster.height, raster.rgba) } }, newHash)
		val plan = assertNotNull(before, "${sample.name}: the edited art plans a reload")
		assertTrue(plan.reload.replacedTiles.isNotEmpty(), "${sample.name}: the repainted layer supersedes its tile")
		assertTrue(plan.report.results.any { result -> result !is ReconcileResult.Matched }, "${sample.name}: the deleted and added layers are more than matches")
		assertEquals(fingerprintOf(before), fingerprintOf(after), "${sample.name}: the reload after the reopen")
		return SampleOutcome.Reloaded
	}
}