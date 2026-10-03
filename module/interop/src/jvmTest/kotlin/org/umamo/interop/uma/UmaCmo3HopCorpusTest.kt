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
import org.umamo.interop.cmo3.ModelOrderPages
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.interop.cmo3.modelOrderPages
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.positionsFromDeltas
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The CMO3 hop a reopened `.uma` takes, since a `.uma` carries no retained CMO3 graph: every corpus CMO3
 * imported, saved as UMA with its pixels, reopened, and exported back to CMO3 through the fresh-graph
 * synthesis, as the app exports a CMO3-origin document it opened from a `.uma` - its stored render pages put
 * into the model's page order, its tiles' own PNGs as the layer art.  The export, read back, must hold every
 * drawable the document held, bound to the same layer of the same file, at the same placement, with the same
 * coordinates to within 1e-4 and the same layer pixels, on as many atlases as the model has pages - so a
 * drawable over never-packed art (miku, modelB, modelD, every drawable of MultiplyScreenColors) survives the
 * hop rather than being reported and left out.  Every keyform survives too: the `.uma` keeps each mesh array
 * and delta bit for bit, and each keyform the export writes back at one of the original keys rebuilds within
 * one ulp of the larger of its value and its base - bit for bit almost everywhere - with no triangle turned
 * over, modelA's ArtMesh193 ("Display 1 Line Out", a line squashed to 0.002 units) included.  Each export is
 * kept under `build/uma-hop/` for the shape gate and the official-editor check.
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
	 * The pages the synthesis takes, in the model's order, and each drawable's page among them: the resolution
	 * the app's CMO3 export policy runs for a UMA document still on its stored render pages, over the stored
	 * bytes.  The app falls back to the page the tiles compose before a transparent one; there is no derived
	 * page set here, so a model page nothing shows is written transparent.
	 *
	 * @param PuppetModel puppet  The reopened model.
	 * @param List        pageBytes The stored render pages.
	 * @param Map         renderPageByDrawableId Each drawable's render page.
	 * @return ModelOrderPages The pages and the page map.
	 */
	private fun conversionPagesOf(puppet: PuppetModel, pageBytes: List<ByteArray>, renderPageByDrawableId: Map<String, Int>): ModelOrderPages<Cmo3Conversion.AtlasPage> {
		val resolved =
			modelOrderPages(puppet, pageBytes, renderPageByDrawableId) { modelPageIndex ->
				val modelPage = puppet.atlas.pages[modelPageIndex]
				PngCodec.write(RasterImage(modelPage.width, modelPage.height, ByteArray(modelPage.width * modelPage.height * 4)))
			}
		// A model page's size is recorded; a render page kept as it is (a MOC3's texture order) is decoded for its.
		val pages =
			resolved.pages.mapIndexed { pageIndex, bytes ->
				val modelPage = puppet.atlas.pages.getOrNull(pageIndex)
				if (modelPage != null) {
					Cmo3Conversion.AtlasPage(bytes, modelPage.width, modelPage.height)
				} else {
					val decoded = PngCodec.read(bytes)
					Cmo3Conversion.AtlasPage(bytes, decoded.width, decoded.height, decoded)
				}
			}
		return ModelOrderPages(pages, resolved.pageIndexByDrawableId)
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

	/**
	 * How one drawable's keyforms came back: the first place they moved past their space's precision, how many
	 * keyforms were compared, and how many triangles turned over among them.
	 *
	 * @property String? mismatch The first mismatch, or null.
	 * @property Int     compared The keyforms compared.
	 * @property Int     flipped  The triangles that wind the other way.
	 */
	private class KeyformComparison(
		val mismatch: String?,
		val compared: Int,
		val flipped: Int,
	)

	/**
	 * Compares [back]'s keyforms with [original]'s at every key the original grid has: each grid cell's shape and
	 * each blend-shape form's, rebuilt from the base and the deltas.  A cell of the export at a key the original
	 * lacks (the union refinement inserts some) has no original to match, and an axis the export added carries the
	 * same geometry along it, so each export cell is projected onto the original's axes.  Each component may move
	 * by one ulp of the larger of its value and either base, the precision the keyforms' space has: a component far
	 * smaller than its base has no float delta that reaches it exactly.
	 *
	 * @param String   label    The drawable, for the report.
	 * @param Drawable original The drawable as first imported, whose keyforms are the file's floats.
	 * @param Drawable back     The drawable read back from the export.
	 * @return KeyformComparison The comparison.
	 */
	private fun compareKeyforms(label: String, original: Drawable, back: Drawable): KeyformComparison {
		val originalBase = original.mesh?.localPositions ?: return KeyformComparison(null, 0, 0)
		val backBase = back.mesh?.localPositions ?: return KeyformComparison("$label lost its mesh", 0, 0)
		val indices = original.mesh?.indices ?: IntArray(0)
		var compared = 0
		var flipped = 0

		/**
		 * The first component two keyforms differ in past the space's precision, or null.
		 *
		 * @param FloatArray expected The original keyform.
		 * @param FloatArray actual   The exported keyform.
		 * @return Int? The component.
		 */
		fun firstDifference(expected: FloatArray, actual: FloatArray): Int? {
			if (expected.size != actual.size) {
				return 0
			}
			return expected.indices.firstOrNull { componentIndex ->
				val bound = Math.ulp(maxOf(abs(expected[componentIndex]), abs(originalBase.getOrElse(componentIndex) { 0f }), abs(backBase.getOrElse(componentIndex) { 0f })))
				abs(expected[componentIndex] - actual[componentIndex]) > bound
			}
		}

		/**
		 * How many triangles wind the other way in [actual] than in [expected].
		 *
		 * @param FloatArray expected The original keyform.
		 * @param FloatArray actual   The exported keyform.
		 * @return Int The count.
		 */
		fun flips(expected: FloatArray, actual: FloatArray): Int {
			if (expected.size != actual.size) {
				return 0
			}
			var count = 0
			for (triangleIndex in 0 until indices.size / 3) {
				val expectedArea = signedArea(expected, indices, triangleIndex)
				if (expectedArea != 0.0 && expectedArea * signedArea(actual, indices, triangleIndex) < 0.0) {
					count++
				}
			}
			return count
		}
		val originalGrid = original.geometryGrid
		val backGrid = back.geometryGrid
		if (originalGrid != null) {
			backGrid ?: return KeyformComparison("$label lost its keyform grid", 0, 0)
			val expectedByKeys =
				originalGrid.cells.associate { cell ->
					originalGrid.axes.mapIndexed { axisIndex, axis -> axis.keys[cell.coordinate[axisIndex]] } to positionsFromDeltas(originalBase, cell.form.positionDeltas)
				}
			val backAxisIndices = originalGrid.axes.map { axis -> backGrid.axes.indexOfFirst { backAxis -> backAxis.parameterId == axis.parameterId } }
			if (backAxisIndices.any { axisIndex -> axisIndex < 0 }) {
				return KeyformComparison("$label lost a keyform axis", 0, 0)
			}
			for (cell in backGrid.cells) {
				val keys = backAxisIndices.map { axisIndex -> backGrid.axes[axisIndex].keys[cell.coordinate[axisIndex]] }
				val expected = expectedByKeys[keys] ?: continue
				val actual = positionsFromDeltas(backBase, cell.form.positionDeltas)
				firstDifference(expected, actual)?.let { componentIndex ->
					return KeyformComparison("$label's keyform at $keys moved: component $componentIndex is ${actual.getOrNull(componentIndex)}, not ${expected.getOrNull(componentIndex)}", compared, flipped)
				}
				flipped += flips(expected, actual)
				compared++
			}
		}
		val backBindings = back.blendShapes.associateBy { binding -> binding.parameterId }
		for (binding in original.blendShapes) {
			val backBinding = backBindings[binding.parameterId] ?: return KeyformComparison("$label lost its blend shape on ${binding.parameterId.raw}", compared, flipped)
			for ((keyIndex, form) in binding.forms.withIndex()) {
				form ?: continue
				val backKeyIndex = backBinding.keys.indexOfFirst { key -> key == binding.keys[keyIndex] }
				val backForm = backBinding.forms.getOrNull(backKeyIndex) ?: return KeyformComparison("$label lost its blend form at ${binding.keys[keyIndex]}", compared, flipped)
				val expected = positionsFromDeltas(originalBase, form.positionDeltas)
				val actual = positionsFromDeltas(backBase, backForm.positionDeltas)
				firstDifference(expected, actual)?.let { componentIndex ->
					return KeyformComparison("$label's blend form on ${binding.parameterId.raw} at ${binding.keys[keyIndex]} moved: component $componentIndex is ${actual.getOrNull(componentIndex)}, not ${expected.getOrNull(componentIndex)}", compared, flipped)
				}
				compared++
			}
		}
		return KeyformComparison(null, compared, flipped)
	}

	/**
	 * Twice the signed area of one triangle, in double so a sliver keeps its sign.
	 *
	 * @param FloatArray positions     The interleaved positions.
	 * @param IntArray   indices       The triangle indices.
	 * @param Int        triangleIndex The triangle.
	 * @return Double The doubled signed area.
	 */
	private fun signedArea(positions: FloatArray, indices: IntArray, triangleIndex: Int): Double {
		val first = indices[triangleIndex * 3] * 2
		val second = indices[triangleIndex * 3 + 1] * 2
		val third = indices[triangleIndex * 3 + 2] * 2
		if (maxOf(first, second, third) + 1 >= positions.size) {
			return 0.0
		}
		val firstX = positions[first].toDouble()
		val firstY = positions[first + 1].toDouble()
		return (positions[second] - firstX) * (positions[third + 1] - firstY) - (positions[second + 1] - firstY) * (positions[third] - firstX)
	}

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
		val reopened = UmaDocumentBridge.readModel(document).model
		// The .uma keeps every mesh array and delta bit for bit (UMA §4.10, §4.11).
		val reopenedById = reopened.drawables.associateBy { drawable -> drawable.id }
		for (drawable in imported.puppet.drawables) {
			val reopenedDrawable = reopenedById[drawable.id] ?: continue
			val label = "${drawable.name} (${drawable.id.raw})"
			drawable.mesh?.let { mesh ->
				val reopenedMesh = reopenedDrawable.mesh
				assertTrue(reopenedMesh != null && reopenedMesh.positions.contentEquals(mesh.positions) && reopenedMesh.localPositions.contentEquals(mesh.localPositions), "$label's mesh changed in the .uma")
			}
			val reopenedCells = reopenedDrawable.geometryGrid?.cells.orEmpty()
			drawable.geometryGrid?.cells?.forEachIndexed { cellIndex, cell ->
				assertTrue(reopenedCells.getOrNull(cellIndex)?.form?.positionDeltas?.contentEquals(cell.form.positionDeltas) == true, "$label's deltas changed in the .uma")
			}
		}
		val documentPages = UmaDocumentBridge.pagesOf(document)
		val pageSet = documentPages.pageSet ?: error("the render pages did not come back")
		val conversion = conversionPagesOf(reopened, pageSet.pageBytes, pageSet.atlasIndexByDrawableId)
		val pages = conversion.pages
		val result =
			Cmo3Conversion.freshCmo3(
				puppet = reopened,
				pages = pages,
				pageIndexByDrawableId = conversion.pageIndexByDrawableId,
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
		var comparedKeyforms = 0
		var flippedTriangles = 0
		val comparedByDrawable = HashMap<DrawableId, Int>()
		val originalById = imported.puppet.drawables.associateBy { drawable -> drawable.id }
		for (drawable in expected.drawables) {
			val label = "${drawable.name} (${drawable.id.raw})"
			val backDrawable = backDrawableById[drawable.id] ?: error("$label is missing from the export")
			originalById[drawable.id]?.let { original ->
				val comparison = compareKeyforms(label, original, backDrawable)
				assertTrue(comparison.mismatch == null, comparison.mismatch)
				comparedKeyforms += comparison.compared
				flippedTriangles += comparison.flipped
				comparedByDrawable[drawable.id] = comparison.compared
			}
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
		assertTrue(flippedTriangles == 0, "$flippedTriangles triangles turned over in the export's keyforms")
		if (sample.nameWithoutExtension == "modelA") {
			assertTrue((comparedByDrawable[DrawableId("ArtMesh193")] ?: 0) > 0, "modelA's ArtMesh193, the drawable Cubism reported folding, had no keyform compared")
		}
		println("uma cmo3 hop: ${sample.name} -> ${expected.drawables.size} drawables, $unplaced unplaced, $atlasCount atlases, $comparedKeyforms keyforms within their space's precision, $flippedTriangles triangles flipped")
		return unplaced
	}
}