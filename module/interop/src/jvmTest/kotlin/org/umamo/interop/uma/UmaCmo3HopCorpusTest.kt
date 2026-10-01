package org.umamo.interop.uma

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaWriterInfo
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaRenderPagePixels
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.interop.cmo3.modelPageIndexByDrawableId
import org.umamo.interop.cmo3.modelPageRenderIndices
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The CMO3 hop a reopened `.uma` takes (docs/plan/uma-format.md D34): every corpus CMO3 imported, saved as UMA
 * with its pixels, reopened, and exported back to CMO3 through the fresh-graph synthesis, as the app exports a
 * CMO3-origin document it opened from a `.uma` - its stored render pages put into the model's page order, its
 * tiles' own PNGs as the layer art.  The export, read back, must hold every drawable the document held, bound
 * to the same layer of the same file, at the same placement, with the same coordinates to within 1e-4 and the
 * same layer pixels, on as many atlases as the model has pages - so a drawable over never-packed art (miku,
 * modelB, modelD, every drawable of MultiplyScreenColors) survives the hop rather than being reported and left
 * out.  Each export is kept under `build/uma-hop/` for the shape gate and the official-editor check.
 *
 * Corpus-gated on `cmo3.probe`; self-skips when it names nothing, and fails when the whole run met no unplaced
 * drawable, since the case it exists for would then go untested.
 */
class UmaCmo3HopCorpusTest {
	private val writer = UmaWriterInfo("Umamo", "corpus-test")

	// Where each hop export is kept, for Cmo3ShapeGateTest and the official editor.
	private val exportDirectory = File("build/uma-hop")

	// Every sample's failure, so one run reports the whole corpus rather than its first broken sample.
	private val failures = ArrayList<String>()

	/**
	 * The pages the synthesis takes, in the model's order, and each drawable's page among them - the same
	 * resolution the app's CMO3 export policy makes for a UMA document still on its stored render pages.
	 *
	 * @param PuppetModel puppet  The reopened model.
	 * @param List        pageBytes The stored render pages.
	 * @param Map         renderPageByDrawableId Each drawable's render page.
	 * @return Pair The pages and the page map.
	 */
	private fun conversionPagesOf(puppet: PuppetModel, pageBytes: List<ByteArray>, renderPageByDrawableId: Map<String, Int>): Pair<List<Cmo3Conversion.AtlasPage>, Map<String, Int>> {
		if (puppet.atlas.pages.isEmpty()) {
			return pageBytes.map { bytes ->
				val decoded = PngCodec.read(bytes)
				Cmo3Conversion.AtlasPage(bytes, decoded.width, decoded.height, decoded)
			} to renderPageByDrawableId
		}
		val renderIndices = modelPageRenderIndices(puppet, renderPageByDrawableId)
		val pages =
			puppet.atlas.pages.mapIndexed { modelPageIndex, modelPage ->
				val bytes = renderIndices[modelPageIndex]?.let(pageBytes::getOrNull) ?: PngCodec.write(RasterImage(modelPage.width, modelPage.height, ByteArray(modelPage.width * modelPage.height * 4)))
				Cmo3Conversion.AtlasPage(bytes, modelPage.width, modelPage.height)
			}
		return pages to modelPageIndexByDrawableId(puppet, renderIndices, renderPageByDrawableId)
	}

	/**
	 * Whether two placements agree to float precision.
	 *
	 * @param AtlasPlacement expected The placement before the hop.
	 * @param AtlasPlacement actual   The placement after it.
	 * @return Boolean True when every field matches within 1e-3.
	 */
	private fun samePlacement(expected: AtlasPlacement, actual: AtlasPlacement): Boolean =
		expected.pageIndex == actual.pageIndex &&
			abs(expected.positionX - actual.positionX) < 1e-3f &&
			abs(expected.positionY - actual.positionY) < 1e-3f &&
			abs(expected.scaleX - actual.scaleX) < 1e-3f &&
			abs(expected.scaleY - actual.scaleY) < 1e-3f &&
			abs(expected.rotationDegrees - actual.rotationDegrees) < 1e-3f

	@Test
	fun everyCorpusCmo3SurvivesTheHopThroughUma() {
		val samples = System.getProperty("cmo3.probe")?.split(',')?.map(::File)?.filter { file -> file.isFile }?.sortedBy { file -> file.name }.orEmpty()
		if (samples.isEmpty()) {
			println("no cmo3.probe samples; skipping the CMO3 hop through UMA")
			return
		}
		exportDirectory.mkdirs()
		var unplacedDrawableCount = 0
		for (sample in samples) {
			try {
				unplacedDrawableCount += hop(sample)
			} catch (failure: AssertionError) {
				failures += "${sample.name}: ${failure.message}"
			} catch (failure: Exception) {
				failures += "${sample.name}: ${failure::class.simpleName}: ${failure.message}"
			}
		}
		assertTrue(failures.isEmpty(), "${failures.size} samples failed the hop:\n${failures.joinToString("\n") { failure -> failure.take(800) }}")
		assertTrue(unplacedDrawableCount > 0, "the corpus met no drawable over never-packed art, so the case this gate exists for went untested")
		println("uma cmo3 hop: ${samples.size} samples, $unplacedDrawableCount drawables over never-packed art")
	}

	/**
	 * One sample's hop, failing on the first drawable that did not survive it.
	 *
	 * @param File sample The corpus CMO3.
	 * @return Int How many of its drawables sit over never-packed art.
	 */
	private fun hop(sample: File): Int {
		val cmo3 = Cmo3.read(sample)
		val root = cmo3.root as? CModelSource ?: error("root is not a CModelSource")
		val imported = Cmo3Import.importModelSource(root)
		val resourceByTile = imported.atlasIngest.imageResourceByTile
		val loaderPages = cmo3AtlasPages(root, cmo3::extractLayerPng)
		val pixels = UmaPixelSource({ tileId -> resourceByTile[AtlasTileId(tileId)]?.let(cmo3::extractLayerPng) }, UmaRenderPagePixels.Stored(loaderPages.pageBytes, loaderPages.atlasIndexByDrawableId), null)
		val document = Uma.read(Uma.write(UmaDocumentBridge.documentOf(UmaModel.create(writer), imported.puppet, pixels)))
		val reopened = UmaDocumentBridge.modelOf(document)
		val documentPages = UmaDocumentBridge.pagesOf(document)
		val pageSet = documentPages.pageSet ?: error("the render pages did not come back")
		val (pages, pageIndexByDrawableId) = conversionPagesOf(reopened, pageSet.pageBytes, pageSet.atlasIndexByDrawableId)
		val result =
			Cmo3Conversion.freshCmo3(
				puppet = reopened,
				pages = pages,
				pageIndexByDrawableId = pageIndexByDrawableId,
				modelName = sample.nameWithoutExtension,
				nowMillis = 1_700_000_000_000L,
				obfuscateKey = 0x1234ABCD,
				tileRasters = { tileId -> documentPages.tilePng(tileId)?.let(PngCodec::read) },
			)
		val exportedBytes = Cmo3.write(result.model)
		File(exportDirectory, "${sample.nameWithoutExtension}UmaHopExportTest.cmo3").writeBytes(exportedBytes)
		val dropped =
			result.report.notices.filter { notice -> notice is ExportNotice.UnsupportedChange && notice.reason == ExportNoticeReason.CreatedDrawableHasNoTextureSource }
		assertTrue(dropped.isEmpty(), "${dropped.size} drawables were left out: ${dropped.take(5)}")

		val exported = Cmo3.read(exportedBytes)
		val exportedRoot = exported.root as CModelSource
		val back = Cmo3Import.importModelSource(exportedRoot)
		// CMO3: CModelSource field textureManager -> CTextureManager field _textureAtlases.
		val atlasCount = Cmo3Import.elementsOf((exportedRoot.textureManager as? CTextureManager)?._textureAtlases).filterIsInstance<CTextureAtlas>().size
		assertTrue(atlasCount == maxOf(reopened.atlas.pages.size, pages.size), "$atlasCount atlases for ${reopened.atlas.pages.size} model pages")

		val expected = result.puppet
		val sourceNameById = expected.sources.associate { source -> source.id to source.name }
		val backSourceNameById = back.puppet.sources.associate { source -> source.id to source.name }
		val backDrawableById = back.puppet.drawables.associateBy { drawable -> drawable.id }
		// A layer several tiles bind (miku's one "texture" layer under 82 model images) crosses as one layer per
		// tile: the document holds each tile's pixels, not the whole layer's, so the copies re-import under the
		// same name with the reader's order suffix.  Such a binding keeps its base key; every other keeps its key.
		val tilesPerBinding = expected.atlas.tiles.mapNotNull { tile -> tile.source?.let { ref -> ref.sourceId to ref.layerKey } }.groupingBy { binding -> binding }.eachCount()
		var unplaced = 0
		for (drawable in expected.drawables) {
			val label = "${drawable.name} (${drawable.id.raw})"
			val backDrawable = backDrawableById[drawable.id] ?: error("$label is missing from the export")
			val tile = drawable.atlasTileId?.let(expected.atlas.tileById::get) ?: continue
			val backTile = backDrawable.atlasTileId?.let(back.puppet.atlas.tileById::get) ?: error("$label lost its tile")
			if (tile.placement == null) {
				unplaced++
			}
			val source = tile.source
			if (source != null) {
				val backSource = backTile.source ?: error("$label lost its layer binding")
				val shared = (tilesPerBinding[source.sourceId to source.layerKey] ?: 0) > 1
				val sameKey = if (shared) backSource.layerKey.substringBefore('#') == source.layerKey.substringBefore('#') else backSource.layerKey == source.layerKey
				assertTrue(sourceNameById[source.sourceId] == backSourceNameById[backSource.sourceId] && sameKey, "$label is bound to ${backSource.layerKey}, not ${source.layerKey}")
				val raster = documentPages.tilePng(tile.id)?.let(PngCodec::read)
				val backPng = back.atlasIngest.imageResourceByTile[backTile.id]?.let(exported::extractLayerPng)
				if (raster != null) {
					assertTrue(backPng != null && PngCodec.read(backPng).rgba.contentEquals(raster.rgba), "$label's layer pixels changed")
				}
			}
			val placement = tile.placement
			val backPlacement = backTile.placement
			assertTrue(
				(placement == null && backPlacement == null) || (placement != null && backPlacement != null && samePlacement(placement, backPlacement)),
				"$label moved from $placement to $backPlacement",
			)
			val uvs = drawable.mesh?.uvs ?: continue
			val backUvs = backDrawable.mesh?.uvs ?: error("$label lost its mesh")
			assertTrue(uvs.size == backUvs.size, "$label has ${backUvs.size} uv components, not ${uvs.size}")
			val worst = uvs.indices.maxOfOrNull { componentIndex -> abs(uvs[componentIndex] - backUvs[componentIndex]) } ?: 0f
			assertTrue(worst <= 1e-4f, "$label's uvs moved by $worst")
		}
		println("uma cmo3 hop: ${sample.name} -> ${expected.drawables.size} drawables, $unplaced unplaced, $atlasCount atlases")
		return unplaced
	}
}