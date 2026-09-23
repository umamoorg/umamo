package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CCachedImage
import org.umamo.format.cmo3.model.gen.CCachedImageManager
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.type.CAffine

/**
 * Which image each CMO3 drawable is shown from, and how its stored texture coordinates reach that image -
 * the one place the CMO3 boundary decides a drawable's texture frame, on import and on export alike.
 *
 * The editor can point a drawable at three kinds of image: an atlas page, its model image's own raster
 * (`_filteredImage`), or a reduced cache copy of that raster (a `CCachedImage` other than the raster itself,
 * the raster padded to a multiple of 64 and scaled down).  Stored coordinates live in one of two frames:
 * the sampled image's own, or the cache frame - the padded raster, which is also the reduced copy's frame.
 * A packed drawable over a page or the raster stores its sampled image's frame; an unpacked drawable, and
 * any drawable over a reduced copy, stores the cache frame.  `GTexture2D.transformImageResource01toLogical01`
 * carries the raster-to-cache scale (raster size over its 64-aligned padding, per axis), so the cache frame
 * reaches the raster through that affine's inverse.
 *
 * Umamo keeps no cache frame and no reduced copy.  Every drawable's model coordinates address either its
 * atlas page or its model image's raster, and a drawable over a reduced copy is shown from the raster - the
 * copy is the editor's display cache and has no meaning outside it.  The export applies the affine forward
 * again, so the file keeps the frame the editor reads.
 *
 * The set of reduced copies is taken once, from the graph as it was read.  An export that rewrites a model
 * image rebuilds its cache and would otherwise make its copy vanish in the middle of the pass; each query
 * reads the drawable's CURRENT texture against that set, so only a deliberate retarget of the texture moves
 * a drawable out of the cache frame.
 *
 * @param CModelSource modelSource The CMO3's root model source, as read.
 */
internal class Cmo3TextureFrames(modelSource: CModelSource) {
	/** Each reduced copy in the graph as read, to the model image it is a copy of; compared by identity. */
	private val ownerByReducedCopy: Map<CImageResource, CModelImage> = reducedCopiesOf(modelSource)

	/**
	 * Whether a drawable's texture is a reduced cache copy of its model image.
	 *
	 * @param CArtMeshSource source The drawable's graph source.
	 * @return Boolean True when it samples a copy rather than a page or a raster.
	 */
	fun samplesReducedCopy(source: CArtMeshSource): Boolean = sampledResourceOf(source)?.let { resource -> resource in ownerByReducedCopy } == true

	/**
	 * Whether a drawable's stored coordinates are in the cache frame rather than its sampled image's own.
	 *
	 * @param CArtMeshSource source The drawable's graph source.
	 * @return Boolean True for an unpacked drawable and for one over a reduced copy.
	 */
	fun storedUvsInCacheFrame(source: CArtMeshSource): Boolean = !Cmo3Import.hasAtlasRegion(source) || samplesReducedCopy(source)

	/**
	 * The image Umamo shows a drawable from: its model image's raster in place of a reduced copy, else the
	 * image its texture names.
	 *
	 * @param CArtMeshSource source The drawable's graph source.
	 * @return CImageResource? The image, or null when the drawable has no texture image.
	 */
	fun renderedImageOf(source: CArtMeshSource): CImageResource? {
		val sampled = sampledResourceOf(source) ?: return null
		// CMO3: CModelImage field _filteredImage - the raster the copy was reduced from.
		return ownerByReducedCopy[sampled]?.let { owner -> owner._filteredImage as? CImageResource } ?: sampled
	}

	/**
	 * The model coordinates of a drawable's stored ones: into the raster frame through the inverse of the
	 * raster-to-cache affine when they are in the cache frame, else verbatim.
	 *
	 * @param CArtMeshSource source     The drawable's graph source.
	 * @param FloatArray     storedUvs  The stored coordinates, interleaved (u, v).
	 * @return FloatArray The model coordinates; the same array when no remap applies.
	 */
	fun modelUvsOf(source: CArtMeshSource, storedUvs: FloatArray): FloatArray {
		if (!storedUvsInCacheFrame(source)) {
			return storedUvs
		}
		val affine = cacheAffineOf(source) ?: return storedUvs
		val determinant = affine.m00 * affine.m11 - affine.m01 * affine.m10
		// A degenerate affine would invert to NaN or infinity; the coordinates stay as stored.
		if (determinant == 0f) {
			return storedUvs
		}
		val modelUvs = FloatArray(storedUvs.size)
		var componentIndex = 0
		while (componentIndex + 1 < storedUvs.size) {
			modelUvs[componentIndex] = inverseU(affine, determinant, storedUvs[componentIndex], storedUvs[componentIndex + 1])
			modelUvs[componentIndex + 1] = inverseV(affine, determinant, storedUvs[componentIndex], storedUvs[componentIndex + 1])
			componentIndex += 2
		}
		return modelUvs
	}

	/**
	 * The stored coordinates of a drawable's model ones: through the raster-to-cache affine when the drawable
	 * stores the cache frame, else verbatim.
	 *
	 * A coordinate pair the edit left alone keeps its stored value, so the affine's float round trip cannot
	 * drift a file the rigger did not touch there.  That needs both the stored and the baseline model
	 * coordinates; without them every pair is converted, and a converted pair re-imports within a few ULPs
	 * of the model's value: through a scale, not every float has a float that maps onto it exactly.
	 *
	 * @param CArtMeshSource source      The drawable's graph source.
	 * @param FloatArray     modelUvs    The model coordinates to store, interleaved (u, v).
	 * @param FloatArray?    storedUvs   The coordinates the graph stores now, or null.
	 * @param FloatArray?    baselineUvs The model coordinates [storedUvs] import to, or null.
	 * @return FloatArray The coordinates to store, a fresh array.
	 */
	fun storedUvsOf(source: CArtMeshSource, modelUvs: FloatArray, storedUvs: FloatArray? = null, baselineUvs: FloatArray? = null): FloatArray {
		val affine = cacheAffineOf(source)?.takeIf { storedUvsInCacheFrame(source) } ?: return modelUvs.copyOf()
		val keptStored = storedUvs?.takeIf { stored -> stored.size == modelUvs.size && baselineUvs?.size == modelUvs.size }
		val result = FloatArray(modelUvs.size)
		var componentIndex = 0
		while (componentIndex + 1 < modelUvs.size) {
			val unchangedPair =
				baselineUvs != null &&
					modelUvs[componentIndex].toRawBits() == baselineUvs[componentIndex].toRawBits() &&
					modelUvs[componentIndex + 1].toRawBits() == baselineUvs[componentIndex + 1].toRawBits()
			if (keptStored != null && unchangedPair) {
				result[componentIndex] = keptStored[componentIndex]
				result[componentIndex + 1] = keptStored[componentIndex + 1]
			} else {
				val u = modelUvs[componentIndex]
				val v = modelUvs[componentIndex + 1]
				result[componentIndex] = affine.m00 * u + affine.m01 * v + affine.m02
				result[componentIndex + 1] = affine.m10 * u + affine.m11 * v + affine.m12
			}
			componentIndex += 2
		}
		return result
	}

	/**
	 * Whether a drawable is shown from a model image: it samples the image's raster, or a reduced copy of it
	 * as the graph was read.
	 *
	 * @param CArtMeshSource source     The drawable's graph source.
	 * @param CModelImage    modelImage The model image.
	 * @return Boolean True when the drawable's texture is the image's raster or one of its copies.
	 */
	fun isShownFrom(source: CArtMeshSource, modelImage: CModelImage): Boolean {
		val sampled = sampledResourceOf(source) ?: return false
		// CMO3: CModelImage field _filteredImage.
		return sampled === modelImage._filteredImage || ownerByReducedCopy[sampled] === modelImage
	}

	/**
	 * Whether a resource was a reduced copy when the graph was read.
	 *
	 * @param CImageResource resource The resource.
	 * @return Boolean True for a copy.
	 */
	fun isReducedCopy(resource: CImageResource): Boolean = resource in ownerByReducedCopy

	private companion object {
		/**
		 * The model u a stored pair imports to: the cache frame's inverse, the one expression import uses.
		 *
		 * @param CAffine affine      The raster-to-cache affine.
		 * @param Float   determinant Its linear part's determinant, nonzero.
		 * @param Float   storedU     The stored u.
		 * @param Float   storedV     The stored v.
		 * @return Float The model u.
		 */
		fun inverseU(affine: CAffine, determinant: Float, storedU: Float, storedV: Float): Float =
			(affine.m11 * (storedU - affine.m02) - affine.m01 * (storedV - affine.m12)) / determinant

		/**
		 * The model v a stored pair imports to: the cache frame's inverse, the one expression import uses.
		 *
		 * @param CAffine affine      The raster-to-cache affine.
		 * @param Float   determinant Its linear part's determinant, nonzero.
		 * @param Float   storedU     The stored u.
		 * @param Float   storedV     The stored v.
		 * @return Float The model v.
		 */
		fun inverseV(affine: CAffine, determinant: Float, storedU: Float, storedV: Float): Float =
			(-affine.m10 * (storedU - affine.m02) + affine.m00 * (storedV - affine.m12)) / determinant

		/**
		 * The image a drawable's texture names.
		 *
		 * @param CArtMeshSource source The drawable's graph source.
		 * @return CImageResource? The image, or null without one.
		 */
		fun sampledResourceOf(source: CArtMeshSource): CImageResource? =
			// CMO3: CArtMeshSource field texture -> GTexture2D field srcImageResource.
			(source.texture as? GTexture2D)?.srcImageResource as? CImageResource

		/**
		 * A drawable's raster-to-cache affine, or null when it has none or it is the identity.
		 *
		 * @param CArtMeshSource source The drawable's graph source.
		 * @return CAffine? The affine.
		 */
		fun cacheAffineOf(source: CArtMeshSource): CAffine? {
			// CMO3: GTexture2D field transformImageResource01toLogical01 - a CAffine taking the raster's [0,1]
			// into the cache frame's [0,1].
			val affine = (source.texture as? GTexture2D)?.transformImageResource01toLogical01 as? CAffine ?: return null
			val isIdentity =
				affine.m00 == 1f &&
					affine.m01 == 0f &&
					affine.m02 == 0f &&
					affine.m10 == 0f &&
					affine.m11 == 1f &&
					affine.m12 == 0f
			return affine.takeUnless { isIdentity }
		}

		/**
		 * Every reduced copy in the graph, to the model image it is a copy of.
		 *
		 * A copy is any cached image of a model image other than the raster itself; an atlas page is never
		 * one, even should a cache ever name it.
		 *
		 * @param CModelSource modelSource The CMO3's root model source.
		 * @return Map<CImageResource, CModelImage> The copies and their owners.
		 */
		fun reducedCopiesOf(modelSource: CModelSource): Map<CImageResource, CModelImage> {
			// CMO3: CModelSource field textureManager.
			val textureManager = modelSource.textureManager as? CTextureManager ?: return emptyMap()
			// CMO3: CTextureManager field _textureAtlases -> CTextureAtlas field cachedAtlasImage.
			val pages =
				Cmo3Import.elementsOf(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>()
					.mapNotNull { atlas -> atlas.cachedAtlasImage as? CImageResource }
					.toHashSet()
			val ownerByCopy = HashMap<CImageResource, CModelImage>()
			// CMO3: CTextureManager field _modelImageGroups -> CModelImageGroup field _modelImages.
			for (group in Cmo3Import.elementsOf(textureManager._modelImageGroups).filterIsInstance<CModelImageGroup>()) {
				for (modelImage in Cmo3Import.elementsOf(group._modelImages).filterIsInstance<CModelImage>()) {
					val raster = modelImage._filteredImage as? CImageResource
					// CMO3: CModelImage field cachedImageManager -> CCachedImageManager field cachedImages ->
					// CCachedImage field _cachedImageResource - the raster itself at SCALE_1, and a reduced
					// copy beside it when the editor made one.
					val manager = modelImage.cachedImageManager as? CCachedImageManager ?: continue
					for (cached in Cmo3Import.elementsOf(manager.cachedImages).filterIsInstance<CCachedImage>()) {
						val resource = cached._cachedImageResource as? CImageResource ?: continue
						if (resource !== raster && resource !in pages) {
							ownerByCopy[resource] = modelImage
						}
					}
				}
			}
			return ownerByCopy
		}
	}
}