package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.FilterMode
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import java.io.File
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Surveys how the official editor writes a drawable that was never packed into a texture atlas - one with
 * no `CTextureInput_TextureAtlasRegion` - so the fresh-graph synthesis can write a drawable over an
 * unplaced tile in the same shape (docs/format/CMO3.md §4 and §6).
 *
 * What it tallies, per drawable: the texture state, the texture inputs and which one is current, the
 * texture's sampling fields, which image the texture samples (its model image's raster, a reduced copy,
 * or something else), whether the texture's raster-to-cache affine is the raster's padding scale, whether
 * the stored coordinates stay inside the cache frame's art, and whether the drawables over one model image
 * share one texture.  The distinct shapes print with their counts.
 *
 * Corpus-gated: self-skips when `cmo3.probe` names no sample, and fails when the samples it read hold no
 * unpacked drawable at all, since the survey would then prove nothing.
 */
class Cmo3UnpackedDrawableProbeTest {
	/**
	 * The corpus samples this probe runs over, from the `cmo3.probe` property the `umamo.test-corpus`
	 * plugin forwards (comma-separated, resolved against the repo root).
	 *
	 * @return List The readable samples, empty when the property names none.
	 */
	private fun corpusFiles(): List<File> =
		System.getProperty("cmo3.probe")
			?.split(',')
			?.map { entry -> File(entry.trim()) }
			?.filter { file -> file.isFile }
			?.sortedBy { file -> file.name }
			.orEmpty()

	/**
	 * Every model image under the texture manager's groups, by its guid.
	 *
	 * @param CModelSource root The model root.
	 * @return Map The model images.
	 */
	private fun modelImagesByGuid(root: CModelSource): Map<String, CModelImage> {
		// CMO3: CModelSource field textureManager -> CTextureManager field _modelImageGroups ->
		// CModelImageGroup field _modelImages.
		val textureManager = root.textureManager as? CTextureManager ?: return emptyMap()
		return Cmo3Import.elementsOf(textureManager._modelImageGroups)
			.filterIsInstance<CModelImageGroup>()
			.flatMap { group -> Cmo3Import.elementsOf(group._modelImages).filterIsInstance<CModelImage>() }
			.mapNotNull { image -> Cmo3Import.uuidOf(image.guid)?.let { uuid -> uuid to image } }
			.toMap()
	}

	/**
	 * Whether an affine is the diagonal padding scale of an image of the given size, to within a few float
	 * ulps: the corpus writes the same quotient with different last bits in different files (1200/1216 is
	 * 0.98684216 in MultiplyScreenColors and 0.9868421 in miku), so the editor computes it along more than
	 * one path and only the value, not its rounding, is the rule.
	 *
	 * @param CAffine affine The affine.
	 * @param Int     width  The image width in pixels.
	 * @param Int     height The image height in pixels.
	 * @return Boolean True when it matches `Cmo3ImageChainBuilder.paddedFrameAffine(width, height)`.
	 */
	private fun isPaddingScale(affine: CAffine, width: Int, height: Int): Boolean {
		val expected = Cmo3ImageChainBuilder.paddedFrameAffine(width, height)
		val tolerance = 4 * Math.ulp(1f)
		return kotlin.math.abs(affine.m00 - expected.m00) <= tolerance &&
			affine.m01 == 0f &&
			affine.m02 == 0f &&
			affine.m10 == 0f &&
			kotlin.math.abs(affine.m11 - expected.m11) <= tolerance &&
			affine.m12 == 0f
	}

	@Test
	fun surveysDrawablesThatWereNeverPacked() {
		val files = corpusFiles()
		if (files.isEmpty()) {
			println("cmo3.probe lists no readable samples; skipping the unpacked-drawable probe")
			return
		}
		val shapeCounts = sortedMapOf<String, Int>()
		val examples = mutableListOf<String>()
		val otherAffines = mutableListOf<String>()
		var packedCount = 0
		var unpackedCount = 0
		var uvsOutsideCacheArt = 0
		var modelImagesWithSeveralDrawables = 0
		var modelImagesWithSeveralTextures = 0
		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: continue
			val frames = Cmo3TextureFrames(root)
			val modelImages = modelImagesByGuid(root)
			val texturesByModelImage = HashMap<String, MutableSet<Any>>()
			val drawablesByModelImage = HashMap<String, Int>()
			var fileUnpacked = 0
			// CMO3: CModelSource field drawableSourceSet -> CDrawableSourceSet field _sources.
			val meshes = Cmo3Import.elementsOf((root.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>()
			for (mesh in meshes) {
				// CMO3: CArtMeshSource field _extensions -> CTextureInputExtension fields _textureInputs /
				// currentTextureInputData.
				val extension = Cmo3Import.elementsOf(mesh._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull()
				val inputs = Cmo3Import.elementsOf(extension?._textureInputs)
				if (inputs.any { input -> input is CTextureInput_TextureAtlasRegion }) {
					packedCount++
					continue
				}
				unpackedCount++
				fileUnpacked++
				val modelImageGuid = inputs.filterIsInstance<CTextureInput_ModelImage>().firstOrNull()?.let { input -> Cmo3Import.uuidOf(input._modelImageGuid) }
				val modelImage = modelImageGuid?.let(modelImages::get)
				// CMO3: CArtMeshSource field texture -> GTexture2D.
				val texture = mesh.texture as? GTexture2D
				val sampled = texture?.srcImageResource as? CImageResource
				// CMO3: CModelImage field _filteredImage - the model image's raster.
				val raster = modelImage?._filteredImage as? CImageResource
				val sampledKind =
					when {
						sampled == null -> "none"
						sampled === raster -> "raster"
						frames.isReducedCopy(sampled) -> "reducedCopy"
						frames.isAtlasPage(sampled) -> "page"
						else -> "other"
					}
				// CMO3: GTexture2D field transformImageResource01toLogical01 - checked against the RASTER's padding
				// scale, which a texture over a reduced copy carries as well.
				val affine = texture?.transformImageResource01toLogical01 as? CAffine
				val affineKind =
					when {
						affine == null -> "absent"
						raster != null && isPaddingScale(affine, raster.width, raster.height) -> "rasterPadding"
						else -> "other"
					}
				// CMO3: GTexture fields wrapMode / filterMode / anisotropy and GTexture2D fields mipmapLevel /
				// isPremultiplied.
				val filter = texture?.filterMode as? FilterMode
				val shape =
					listOf(
						"state=${mesh.textureState}",
						"inputs=${inputs.joinToString("+") { input -> input?.let { it::class.simpleName }.orEmpty() }}",
						"current=${extension?.currentTextureInputData?.let { it::class.simpleName }}",
						"samples=$sampledKind",
						"affine=$affineKind",
						"wrap=${texture?.wrapMode}",
						"filter=${filter?.minFilter}/${filter?.magFilter}",
						"anisotropy=${texture?.anisotropy}",
						"mip=${texture?.mipmapLevel}",
						"premultiplied=${texture?.isPremultiplied}",
					).joinToString(" ")
				shapeCounts[shape] = (shapeCounts[shape] ?: 0) + 1
				if (affineKind == "other") {
					otherAffines.add(
						"${file.name} ${(mesh.id as? Id)?.idstr} affine=(${affine?.m00}, ${affine?.m01}, ${affine?.m02}, ${affine?.m10}, ${affine?.m11}, ${affine?.m12}) " +
							"raster=${raster?.width}x${raster?.height} sampled=${sampled?.width}x${sampled?.height}",
					)
				}
				if (examples.size < 12) {
					examples.add("${file.name} ${(mesh.id as? Id)?.idstr} texture='${texture?.name}' modelImage='${modelImage?.name}' guidNote='${(texture?.guid as? Guid)?.note}'")
				}
				// CMO3: CArtMeshSource field uvs - stored in the cache frame, whose art spans [0, padding scale].
				val uvs = mesh.uvs as? FloatArray
				if (uvs != null && affine != null) {
					val slack = 1e-3f
					var componentIndex = 0
					while (componentIndex + 1 < uvs.size) {
						if (uvs[componentIndex] > affine.m00 + slack || uvs[componentIndex + 1] > affine.m11 + slack) {
							uvsOutsideCacheArt++
							break
						}
						componentIndex += 2
					}
				}
				if (modelImageGuid != null && texture != null) {
					texturesByModelImage.getOrPut(modelImageGuid) { java.util.Collections.newSetFromMap(IdentityHashMap()) }.add(texture)
					drawablesByModelImage[modelImageGuid] = (drawablesByModelImage[modelImageGuid] ?: 0) + 1
				}
			}
			modelImagesWithSeveralDrawables += drawablesByModelImage.count { (_, count) -> count > 1 }
			modelImagesWithSeveralTextures += texturesByModelImage.count { (_, textures) -> textures.size > 1 }
			println("unpacked drawables: ${file.name} -> $fileUnpacked of ${meshes.size}")
		}
		println("unpacked drawables: $unpackedCount unpacked, $packedCount packed, across ${files.size} files")
		for ((shape, count) in shapeCounts) {
			println("  $count x $shape")
		}
		examples.forEach { example -> println("  e.g. $example") }
		otherAffines.forEach { line -> println("  affine not the raster's padding: $line") }
		println("  stored uvs past the cache frame's art: $uvsOutsideCacheArt drawables")
		println("  model images under several drawables: $modelImagesWithSeveralDrawables, of which with several textures: $modelImagesWithSeveralTextures")
		assertTrue(unpackedCount > 0, "the corpus held no unpacked drawable, so the survey proved nothing")
	}
}