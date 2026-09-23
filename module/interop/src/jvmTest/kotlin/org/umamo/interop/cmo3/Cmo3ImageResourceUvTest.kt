package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CCachedImage
import org.umamo.format.cmo3.model.gen.CCachedImageManager
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.runtime.model.DrawableId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Locks in the UV frame convention CMO3 ingest must honor (see docs/format/CMO3.md §4):
 *
 *  - An UNPACKED drawable (no CTextureInput_TextureAtlasRegion) stores its UVs in the editor's cache frame,
 *    scaled by GTexture2D.transformImageResource01toLogical01; ingest inverts that affine so the UVs span
 *    its model image's raster.  Skipping it clips the outer margin and enlarges the art.
 *  - A PACKED drawable over an atlas page or its model image's raster stores that image's own frame, and
 *    ingest leaves its UVs VERBATIM.
 *  - A drawable over the editor's reduced cache copy of its model image stores the copy's frame, which is
 *    the cache frame: ingest inverts the affine AND the drawable is shown from its model image's raster.
 *    The two go together - inverting the UVs while still sampling the copy shrinks the art.
 *
 * Corpus-gated by name; self-skips when a sample is absent.
 */
class Cmo3ImageResourceUvTest {
	private fun corpusFile(fileName: String): File? {
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				return corpus.walkTopDown().firstOrNull { it.isFile && it.name == fileName }
			}
			directory = directory.parentFile
		}
		return null
	}

	private fun elements(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	private fun artMeshes(root: CModelSource): List<CArtMeshSource> =
		elements((root.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>()

	private fun hasAtlasRegion(source: CArtMeshSource): Boolean {
		val extension = elements(source._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull() ?: return false
		return elements(extension._textureInputs).any { it is CTextureInput_TextureAtlasRegion }
	}

	private fun maxUvComponent(uvs: FloatArray): Float = uvs.maxOrNull() ?: Float.NaN

	@Test
	fun unpackedPerLayerUvsSpanFullImageAfterInverseTransform() {
		val file =
			corpusFile("MultiplyScreenColors.cmo3") ?: run {
				println("MultiplyScreenColors.cmo3 not present; skipping unpacked-UV test")
				return
			}
		val root = Cmo3.read(file).root as? CModelSource ?: error("root is not a CModelSource")
		val puppet = Cmo3Import.fromModelSource(root)

		// Inset_Pink_Square is unpacked (no atlas); its logical UVs top out at ~0.965 (the affine scale) and
		// must reach the image edge (~1.0) after inversion so the whole PNG - white border included - maps on.
		val insetPink = puppet.drawables.first { it.id == DrawableId("Inset_Pink_Square") }
		val insetUvMax = maxUvComponent(insetPink.mesh!!.uvs)
		assertTrue(insetUvMax > 0.99f, "unpacked UVs must reach the image edge; got max $insetUvMax")
		assertTrue(insetUvMax < 1.05f, "unpacked UVs must not wildly overshoot; got max $insetUvMax")
	}

	/**
	 * Each reduced cache copy in the graph, to the model image it was reduced from - read straight off the
	 * file, so the test's classification owes nothing to the import.
	 *
	 * @param CModelSource root The CMO3's model source.
	 * @return Map<CImageResource, CModelImage> The copies and their owners.
	 */
	private fun reducedCopyOwners(root: CModelSource): Map<CImageResource, CModelImage> {
		val textureManager = root.textureManager as? CTextureManager ?: return emptyMap()
		val owners = HashMap<CImageResource, CModelImage>()
		for (group in elements(textureManager._modelImageGroups).filterIsInstance<CModelImageGroup>()) {
			for (modelImage in elements(group._modelImages).filterIsInstance<CModelImage>()) {
				val manager = modelImage.cachedImageManager as? CCachedImageManager ?: continue
				for (cached in elements(manager.cachedImages).filterIsInstance<CCachedImage>()) {
					val resource = cached._cachedImageResource as? CImageResource ?: continue
					if (resource !== modelImage._filteredImage) {
						owners[resource] = modelImage
					}
				}
			}
		}
		return owners
	}

	/**
	 * Packed drawables over a page or their raster keep their stored UVs verbatim, and drawables over a
	 * reduced copy come out inverted into their raster's frame AND shown from that raster.
	 */
	@Test
	fun packedUvsAreVerbatimAndReducedCopiesMoveToTheirRaster() {
		val file =
			corpusFile("modelA.cmo3") ?: run {
				println("modelA.cmo3 not present; skipping packed-UV test")
				return
			}
		val cmo3 = Cmo3.read(file)
		val root = cmo3.root as? CModelSource ?: error("root is not a CModelSource")
		val puppet = Cmo3Import.fromModelSource(root)
		val renderPages = cmo3AtlasPages(root, cmo3::extractLayerPng)
		val copyOwners = reducedCopyOwners(root)

		val sourcesById = artMeshes(root).associateBy { (it.id as? Id)?.idstr }
		var verbatimChecked = 0
		var reducedCopyChecked = 0
		for (drawable in puppet.drawables) {
			val source = sourcesById[drawable.id.raw] ?: continue
			if (!hasAtlasRegion(source)) {
				continue
			}
			val sourceUvs = source.uvs as? FloatArray ?: continue
			val sampled = (source.texture as? GTexture2D)?.srcImageResource as? CImageResource ?: continue
			val owner = copyOwners[sampled]
			if (owner == null) {
				// A packed drawable over a page or its raster: byte-for-byte, no remap.
				assertContentEquals(sourceUvs, drawable.mesh!!.uvs, "packed drawable ${drawable.id.raw} UVs must be verbatim")
				verbatimChecked++
				continue
			}
			// Over a reduced copy: the UVs leave the cache frame through the affine's inverse ...
			val affine = (source.texture as GTexture2D).transformImageResource01toLogical01 as CAffine
			val determinant = affine.m00 * affine.m11 - affine.m01 * affine.m10
			val modelUvs = drawable.mesh!!.uvs
			var componentIndex = 0
			while (componentIndex + 1 < sourceUvs.size) {
				val cacheU = sourceUvs[componentIndex] - affine.m02
				val cacheV = sourceUvs[componentIndex + 1] - affine.m12
				val expectedU = (affine.m11 * cacheU - affine.m01 * cacheV) / determinant
				val expectedV = (-affine.m10 * cacheU + affine.m00 * cacheV) / determinant
				assertTrue(
					kotlin.math.abs(modelUvs[componentIndex] - expectedU) < 1e-5f && kotlin.math.abs(modelUvs[componentIndex + 1] - expectedV) < 1e-5f,
					"reduced-copy drawable ${drawable.id.raw} UV ${componentIndex / 2} must be inverted into its raster's frame",
				)
				componentIndex += 2
			}
			// ... and the drawable is shown from the raster those UVs now address, never the copy.
			val pageIndex = renderPages.atlasIndexByDrawableId[drawable.id.raw]
			val rasterPng = (owner._filteredImage as? CImageResource)?.let(cmo3::extractLayerPng)
			assertTrue(
				pageIndex != null && rasterPng != null && renderPages.pageBytes[pageIndex].contentEquals(rasterPng),
				"reduced-copy drawable ${drawable.id.raw} must be shown from its model image's raster",
			)
			reducedCopyChecked++
		}
		assertTrue(verbatimChecked > 0, "expected packed drawables over a page or a raster; found none")
		assertTrue(reducedCopyChecked > 0, "expected drawables over a reduced copy (the regression surface); found none")
	}
}