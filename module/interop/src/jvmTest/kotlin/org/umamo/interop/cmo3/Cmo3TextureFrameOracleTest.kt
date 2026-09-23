package org.umamo.interop.cmo3

import org.junit.Assume
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CCachedImage
import org.umamo.format.cmo3.model.gen.CCachedImageManager
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.moc3.Moc3
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.interop.moc3.import.moc3AtlasPages
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.storedToArtAffineForTile
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every CMO3 drawable's texture mapping, checked against the Cubism Editor's own MOC3 export of the same
 * model: its atlas sampled at its own coordinates is what the drawable really shows, so it is ground truth
 * for both of Umamo's displays and owes nothing to Umamo's reading of the CMO3.
 *
 * The atlas display is the document's render image sampled at the model's coordinates; the source display
 * is the tile's raster sampled through the tile's stored-to-art mapping.  Both must show the art the MOC3
 * shows at the same point of the same mesh.  A drawable whose texture is the editor's reduced cache copy
 * of its model image is the class this exists for: its stored coordinates address the padded copy, not
 * the model image's own raster, so a reading that confuses the two draws the art squeezed and cut off.
 *
 * Pairs `cmo3/<stem>.cmo3` from `cmo3.probe` with `moc3/<stem>/<stem>.moc3` under `moc3.samples`; skips by
 * assumption when no pair exists, so an unfed run reports skipped rather than green.
 */
class Cmo3TextureFrameOracleTest {
	private companion object {
		/** The share of one model's samples a display may miss: edge texels land differently at the two resolutions. */
		const val MAXIMUM_MISMATCH_FRACTION = 0.03f

		/** The least MOC3 alpha a sample needs to count: a translucent edge texel says nothing about placement. */
		const val OPAQUE_ALPHA = 200

		/** The largest per-channel difference two texels of the same art may show across resampling. */
		const val CHANNEL_TOLERANCE = 60

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
	 * One model's tally.
	 *
	 * @property String name The CMO3's file name.
	 */
	private class ModelTally(val name: String) {
		var drawables = 0
		var reducedCopyDrawables = 0
		var reducedCopySamples = 0
		var samples = 0
		var atlasMismatches = 0
		var sourceMismatches = 0
		var renderImageBytes = 0L
		val worstSourceDrawables = ArrayList<String>()
	}

	/**
	 * Flattens a CMO3 collection field (held as `Any?`) to a plain list.
	 *
	 * @param Any? collection The raw field.
	 * @return List<Any?> The elements, empty for any other shape.
	 */
	private fun elements(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	/**
	 * The CMO3/MOC3 pairs the corpus holds.
	 *
	 * @return List<Pair<File, File>> Each CMO3 with its MOC3 twin.
	 */
	private fun corpusPairs(): List<Pair<File, File>> {
		val mocDirectory = System.getProperty("moc3.samples")?.let(::File)?.takeIf { directory -> directory.isDirectory } ?: return emptyList()
		return (System.getProperty("cmo3.probe") ?: "")
			.split(',')
			.map { path -> path.trim() }
			.filter { path -> path.isNotEmpty() }
			.map(::File)
			.filter { file -> file.isFile }
			.mapNotNull { cmo3File ->
				val stem = cmo3File.nameWithoutExtension
				File(File(mocDirectory, stem), "$stem.moc3").takeIf { file -> file.isFile }?.let { mocFile -> cmo3File to mocFile }
			}
	}

	/**
	 * The resources that are a model image's reduced cache copy rather than its raster or an atlas page -
	 * worked out here from the file alone, so the oracle's own classification owes nothing to the import.
	 *
	 * @param CModelSource root The CMO3's model source.
	 * @return Set<CImageResource> The copies, compared by identity.
	 */
	private fun reducedCopiesOf(root: CModelSource): Set<CImageResource> {
		val textureManager = root.textureManager as? CTextureManager ?: return emptySet()
		val pages = elements(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>().mapNotNull { atlas -> atlas.cachedAtlasImage as? CImageResource }.toHashSet()
		val copies = HashSet<CImageResource>()
		for (group in elements(textureManager._modelImageGroups).filterIsInstance<CModelImageGroup>()) {
			for (modelImage in elements(group._modelImages).filterIsInstance<CModelImage>()) {
				val raster = modelImage._filteredImage as? CImageResource
				val manager = modelImage.cachedImageManager as? CCachedImageManager ?: continue
				for (cached in elements(manager.cachedImages).filterIsInstance<CCachedImage>()) {
					val resource = cached._cachedImageResource as? CImageResource ?: continue
					if (resource !== raster && resource !in pages) {
						copies.add(resource)
					}
				}
			}
		}
		return copies
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
		if (!u.isFinite() || !v.isFinite()) {
			return null
		}
		val column = kotlin.math.floor(u * image.width).toInt()
		val row = kotlin.math.floor(v * image.height).toInt()
		if (column < 0 || row < 0 || column >= image.width || row >= image.height) {
			return null
		}
		val offset = (row * image.width + column) * 4
		return IntArray(4) { channelIndex -> image.rgba[offset + channelIndex].toInt() and 0xFF }
	}

	/**
	 * Whether a displayed texel shows the art the reference texel shows.
	 *
	 * @param IntArray  reference The MOC3 texel.
	 * @param IntArray? displayed The texel a display samples, or null when it samples outside its image.
	 * @return Boolean True when every channel is within [CHANNEL_TOLERANCE].
	 */
	private fun matches(reference: IntArray, displayed: IntArray?): Boolean =
		displayed != null && (0 until 4).all { channelIndex -> abs(reference[channelIndex] - displayed[channelIndex]) <= CHANNEL_TOLERANCE }

	/**
	 * Measures one CMO3 against its MOC3 twin.
	 *
	 * @param File cmo3File The CMO3.
	 * @param File mocFile  Its MOC3 twin, beside its model3.json and texture files.
	 * @return ModelTally? The tally, or null when the MOC3 family on disk is incomplete.
	 */
	private fun measure(cmo3File: File, mocFile: File): ModelTally? {
		val manifestFile = File(mocFile.parentFile, "${Moc3Sidecars.basenameFor(mocFile.name)}.model3.json")
		if (!manifestFile.isFile) {
			println("[texture-frame] ${cmo3File.name}: no ${manifestFile.name} beside its MOC3; skipped")
			return null
		}
		val manifest = Moc3.readModel3(manifestFile.readText())
		// model3: FileReferences.Textures - the page paths, relative to the manifest.
		val mocPageFiles = manifest.fileReferences.textures.map { path -> File(manifestFile.parentFile, path) }
		if (mocPageFiles.isEmpty() || mocPageFiles.any { file -> !file.isFile }) {
			println("[texture-frame] ${cmo3File.name}: its MOC3's texture files are not all on disk; skipped")
			return null
		}
		val tally = ModelTally(cmo3File.name)
		val cmo3: Cmo3Model = Cmo3.read(cmo3File)
		val root = cmo3.root as CModelSource
		val imported = Cmo3Import.importModelSource(root)
		val puppet: PuppetModel = imported.puppet
		val renderPages = cmo3AtlasPages(root, cmo3::extractLayerPng)
		val decodedRenderPages = HashMap<Int, RasterImage>()
		val decodedTiles = HashMap<AtlasTileId, RasterImage?>()
		val reducedCopies = reducedCopiesOf(root)
		val sampledResourceByDrawableId =
			elements((root.drawableSourceSet as? CDrawableSourceSet)?._sources)
				.filterIsInstance<CArtMeshSource>()
				.mapNotNull { source ->
					val drawableId = (source.id as? Id)?.idstr ?: return@mapNotNull null
					val resource = (source.texture as? GTexture2D)?.srcImageResource as? CImageResource ?: return@mapNotNull null
					drawableId to resource
				}.toMap()

		val mocDocument = Moc3.read(mocFile.readBytes())
		val mocPages = moc3AtlasPages(mocDocument, mocPageFiles.map { file -> file.readBytes() })
		val decodedMocPages = HashMap<Int, RasterImage>()
		val mocMeshById = Moc3Import.fromMocDocument(mocDocument, null).drawables.associate { drawable -> drawable.id.raw to drawable.mesh }

		for (drawable in puppet.drawables) {
			val mesh = drawable.mesh ?: continue
			val mocMesh = mocMeshById[drawable.id.raw] ?: continue
			if (mocMesh.uvs.size != mesh.uvs.size || mesh.indices.isEmpty()) {
				continue
			}
			val mocPageIndex = mocPages.atlasIndexByDrawableId[drawable.id.raw] ?: continue
			val mocPage = decodedMocPages.getOrPut(mocPageIndex) { PngCodec.read(mocPages.pageBytes[mocPageIndex]) }
			val renderPage =
				renderPages.atlasIndexByDrawableId[drawable.id.raw]?.let { pageIndex ->
					decodedRenderPages.getOrPut(pageIndex) { PngCodec.read(renderPages.pageBytes[pageIndex]) }
				}
			val tileId = drawable.atlasTileId
			val tile =
				tileId?.let { id ->
					decodedTiles.getOrPut(id) { imported.atlasIngest.imageResourceByTile[id]?.let(cmo3::extractLayerPng)?.let(PngCodec::read) }
				}
			val storedToArt = tileId?.let { id -> puppet.atlas.storedToArtAffineForTile(id) }
			val reducedCopy = sampledResourceByDrawableId[drawable.id.raw]?.let { resource -> resource in reducedCopies } == true
			tally.drawables++
			if (reducedCopy) {
				tally.reducedCopyDrawables++
			}
			var drawableSamples = 0
			var drawableSourceMismatches = 0
			var triangleStart = 0
			while (triangleStart + 2 < mesh.indices.size) {
				for (weights in SAMPLE_WEIGHTS) {
					var mocU = 0f
					var mocV = 0f
					var modelU = 0f
					var modelV = 0f
					for (cornerIndex in 0 until 3) {
						val vertex = mesh.indices[triangleStart + cornerIndex]
						mocU += weights[cornerIndex] * mocMesh.uvs[vertex * 2]
						mocV += weights[cornerIndex] * mocMesh.uvs[vertex * 2 + 1]
						modelU += weights[cornerIndex] * mesh.uvs[vertex * 2]
						modelV += weights[cornerIndex] * mesh.uvs[vertex * 2 + 1]
					}
					val reference = texelAt(mocPage, mocU, mocV) ?: continue
					if (reference[3] < OPAQUE_ALPHA) {
						continue
					}
					tally.samples++
					drawableSamples++
					if (reducedCopy) {
						tally.reducedCopySamples++
					}
					if (renderPage != null && !matches(reference, texelAt(renderPage, modelU, modelV))) {
						tally.atlasMismatches++
					}
					if (tile != null && storedToArt != null) {
						val artU = storedToArt[0] * modelU + storedToArt[1] * modelV + storedToArt[2]
						val artV = storedToArt[3] * modelU + storedToArt[4] * modelV + storedToArt[5]
						if (!matches(reference, texelAt(tile, artU, artV))) {
							tally.sourceMismatches++
							drawableSourceMismatches++
						}
					}
				}
				triangleStart += 3
			}
			if (drawableSamples > 0 && drawableSourceMismatches * 5 > drawableSamples && tally.worstSourceDrawables.size < 5) {
				tally.worstSourceDrawables.add("${drawable.id.raw} '${drawable.name}' $drawableSourceMismatches/$drawableSamples")
			}
		}
		tally.renderImageBytes = decodedRenderPages.values.sumOf { page -> page.width.toLong() * page.height.toLong() * 4L }
		return tally
	}

	/**
	 * Both displays of every paired corpus model show the art Cubism's own export shows.
	 */
	@Test
	fun bothDisplaysMatchTheEditorsOwnExport() {
		val pairs = corpusPairs()
		Assume.assumeTrue("[texture-frame] no cmo3.probe / moc3.samples pair", pairs.isNotEmpty())

		val failures = ArrayList<String>()
		var checkedSamples = 0
		var reducedCopyDrawables = 0
		var reducedCopySamples = 0
		for ((cmo3File, mocFile) in pairs) {
			val tally = measure(cmo3File, mocFile) ?: continue
			checkedSamples += tally.samples
			reducedCopyDrawables += tally.reducedCopyDrawables
			reducedCopySamples += tally.reducedCopySamples
			val atlasFraction = if (tally.samples == 0) 0f else tally.atlasMismatches.toFloat() / tally.samples
			val sourceFraction = if (tally.samples == 0) 0f else tally.sourceMismatches.toFloat() / tally.samples
			println(
				"[texture-frame] ${tally.name}: ${tally.drawables} drawables (${tally.reducedCopyDrawables} over a reduced copy), " +
					"${tally.samples} samples, atlas display $atlasFraction, source display $sourceFraction mismatched, " +
					"render images ${tally.renderImageBytes / (1024 * 1024)} MiB decoded",
			)
			if (atlasFraction > MAXIMUM_MISMATCH_FRACTION) {
				failures.add("${tally.name}: the atlas display misses $atlasFraction of the export's samples")
			}
			if (sourceFraction > MAXIMUM_MISMATCH_FRACTION) {
				failures.add("${tally.name}: the source display misses $sourceFraction of the export's samples; worst ${tally.worstSourceDrawables}")
			}
		}
		assertTrue(checkedSamples > 0, "no opaque sample was compared; the oracle checked nothing")
		assertTrue(reducedCopyDrawables == 0 || reducedCopySamples > 0, "reduced-copy drawables were paired but none was sampled")
		assertTrue(failures.isEmpty(), "displays disagree with the editor's own export:\n" + failures.joinToString("\n"))
	}
}