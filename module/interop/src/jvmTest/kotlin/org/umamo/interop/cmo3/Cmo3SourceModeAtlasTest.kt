package org.umamo.interop.cmo3

import org.junit.Assume
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.storedToArtAffineForTile
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A model saved in source-layer display is read onto the atlas pages the file carries.
 *
 * The editor keeps a model's atlas whichever display it was saved in; saved in source-layer display, its
 * drawables' textures name model images instead, and nothing a drawable samples names the page.  Umamo
 * still reads every placed tile's drawable onto that page, so its atlas display is the editor's atlas.
 * For every such corpus save: the coordinates address pages, every placed drawable is shown from its
 * tile's page image byte for byte, and the page at a drawable's coordinates shows what its tile's raster
 * shows at the same point through the placement - the atlas display and the source display agree.
 *
 * Gated on `cmo3.probe`; skips by assumption when no corpus save is of this shape.
 */
class Cmo3SourceModeAtlasTest {
	private companion object {
		/** The least raster alpha a sample needs to count. */
		const val OPAQUE_ALPHA = 200

		/** The largest per-channel difference the page's (often scaled) copy of the art may show. */
		const val CHANNEL_TOLERANCE = 60

		/** The share of one model's samples on which the page and the raster may disagree. */
		const val MAXIMUM_MISMATCH_FRACTION = 0.03f

		/** Barycentric sample points per triangle: the centroid and one point near each corner. */
		val SAMPLE_WEIGHTS: List<FloatArray> =
			listOf(
				floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f),
				floatArrayOf(0.6f, 0.2f, 0.2f),
				floatArrayOf(0.2f, 0.6f, 0.2f),
				floatArrayOf(0.2f, 0.2f, 0.6f),
			)
	}

	/**
	 * The texel nearest a texture coordinate, or null outside the image.
	 *
	 * @param RasterImage image The image.
	 * @param Float       u     The horizontal coordinate, 0 at the left edge.
	 * @param Float       v     The vertical coordinate, 0 at the top row.
	 * @return IntArray? The RGBA channels, 0 to 255.
	 */
	private fun texelAt(image: RasterImage, u: Float, v: Float): IntArray? {
		val column = floor(u * image.width).toInt()
		val row = floor(v * image.height).toInt()
		if (column < 0 || row < 0 || column >= image.width || row >= image.height) {
			return null
		}
		val offset = (row * image.width + column) * 4
		return IntArray(4) { channelIndex -> image.rgba[offset + channelIndex].toInt() and 0xFF }
	}

	@Test
	fun aSourceModeSaveIsReadOntoTheAtlasPagesItCarries() {
		val files =
			(System.getProperty("cmo3.probe") ?: "").split(',').map { path -> path.trim() }.filter { path -> path.isNotEmpty() }.map(::File).filter { file -> file.isFile }
		Assume.assumeTrue("[source-mode] no cmo3.probe", files.isNotEmpty())

		var checkedFiles = 0
		val failures = ArrayList<String>()
		for (file in files) {
			val cmo3 = Cmo3.read(file)
			val root = cmo3.root as? CModelSource ?: continue
			val textureManager = root.textureManager as? CTextureManager ?: continue
			// CMO3: CTextureManager field _textureAtlases -> CTextureAtlas field cachedAtlasImage.
			val pageResources = Cmo3Import.elementsOf(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>().map { atlas -> atlas.cachedAtlasImage as? CImageResource }
			val imported = Cmo3Import.importModelSource(root)
			val puppet = imported.puppet
			val sourceById =
				Cmo3Import.elementsOf((root.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>()
					.mapNotNull { source -> (source.id as? Id)?.idstr?.let { idStr -> idStr to source } }.toMap()
			// Placed drawables whose file texture names no page: what a source-layer save is made of.
			val sourceModeDrawables =
				puppet.drawables.filter { drawable ->
					val placed = drawable.atlasTileId?.let { tileId -> puppet.atlas.tileById[tileId]?.placement } != null
					val sampled = (sourceById[drawable.id.raw]?.texture as? GTexture2D)?.srcImageResource
					placed && pageResources.none { page -> page === sampled }
				}
			if (sourceModeDrawables.isEmpty()) {
				continue
			}
			checkedFiles++
			if (!puppet.atlas.storedUvsAddressPages) {
				failures.add("${file.name}: its coordinates do not address its pages")
			}
			val renderPages = cmo3AtlasPages(root, cmo3::extractLayerPng)
			val decodedRenderPages = HashMap<Int, RasterImage>()
			val decodedTiles = HashMap<AtlasTileId, RasterImage?>()
			var samples = 0
			var mismatches = 0
			for (drawable in sourceModeDrawables) {
				val tileId = drawable.atlasTileId!!
				val placement = puppet.atlas.tileById.getValue(tileId).placement!!
				val renderPageIndex = renderPages.atlasIndexByDrawableId[drawable.id.raw]
				val pagePng = pageResources.getOrNull(placement.pageIndex)?.let(cmo3::extractLayerPng)
				if (renderPageIndex == null || pagePng == null || !renderPages.pageBytes[renderPageIndex].contentEquals(pagePng)) {
					failures.add("${file.name} ${drawable.id.raw}: not shown from its tile's page image")
					continue
				}
				val page = decodedRenderPages.getOrPut(renderPageIndex) { PngCodec.read(renderPages.pageBytes[renderPageIndex]) }
				val raster = decodedTiles.getOrPut(tileId) { imported.atlasIngest.imageResourceByTile[tileId]?.let(cmo3::extractLayerPng)?.let(PngCodec::read) } ?: continue
				val storedToArt = puppet.atlas.storedToArtAffineForTile(tileId) ?: continue
				val mesh = drawable.mesh ?: continue
				var triangleStart = 0
				while (triangleStart + 2 < mesh.indices.size) {
					for (weights in SAMPLE_WEIGHTS) {
						var modelU = 0f
						var modelV = 0f
						for (cornerIndex in 0 until 3) {
							val vertex = mesh.indices[triangleStart + cornerIndex]
							modelU += weights[cornerIndex] * mesh.uvs[vertex * 2]
							modelV += weights[cornerIndex] * mesh.uvs[vertex * 2 + 1]
						}
						val artU = storedToArt[0] * modelU + storedToArt[1] * modelV + storedToArt[2]
						val artV = storedToArt[3] * modelU + storedToArt[4] * modelV + storedToArt[5]
						val rasterTexel = texelAt(raster, artU, artV) ?: continue
						if (rasterTexel[3] < OPAQUE_ALPHA) {
							continue
						}
						samples++
						val pageTexel = texelAt(page, modelU, modelV)
						if (pageTexel == null || (0 until 4).any { channelIndex -> abs(pageTexel[channelIndex] - rasterTexel[channelIndex]) > CHANNEL_TOLERANCE }) {
							mismatches++
						}
					}
					triangleStart += 3
				}
			}
			val mismatchFraction = if (samples == 0) 0f else mismatches.toFloat() / samples
			println("[source-mode] ${file.name}: ${sourceModeDrawables.size} placed drawables off their page in the file, ${renderPages.pageBytes.size} render page(s), $mismatches of $samples samples disagree ($mismatchFraction)")
			if (samples == 0) {
				failures.add("${file.name}: no opaque sample compared")
			}
			if (mismatchFraction > MAXIMUM_MISMATCH_FRACTION) {
				failures.add("${file.name}: the page and the rasters disagree on $mismatchFraction of samples")
			}
		}
		Assume.assumeTrue("[source-mode] no cmo3.probe model was saved in source-layer display", checkedFiles > 0)
		assertTrue(failures.isEmpty(), "source-mode saves are not read onto their pages:\n" + failures.joinToString("\n"))
	}
}