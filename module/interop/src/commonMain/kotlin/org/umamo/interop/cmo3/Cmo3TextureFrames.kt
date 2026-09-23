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
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.invertUvAffine

/**
 * The frame a drawable's stored texture coordinates are in, read off the image its texture names.
 */
internal enum class Cmo3StoredFrame {
	/** An atlas page's own [0,1]: the drawable samples the page. */
	Page,

	/** Its model image's raster's own [0,1]: a packed drawable sampling the raster. */
	Raster,

	/** The editor's cache frame, the raster padded to a multiple of 64: unpacked, or over a reduced copy. */
	Cache,
}

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
 * Umamo keeps no cache frame and no reduced copy, and reads every placed tile off its page.  A drawable
 * whose tile the editor packed (it has an atlas entry, so the tile has a placement) takes model coordinates
 * in that PAGE's frame whatever its texture names: a model saved in source-layer display points its
 * drawables at model images or their copies, yet its atlas page is in the file and is what the editor shows
 * with the source artwork off.  Any other drawable takes its model image's raster frame, and a drawable
 * over a reduced copy is shown from the raster - the copy is the editor's display cache and has no meaning
 * outside it.  The export runs the same chain backward, so the file keeps the frame the editor reads.
 *
 * The tile's placement arrives as its stored-to-art affine ([org.umamo.runtime.model.storedToArtAffineForTile]):
 * the import's atlas on the way in, the EDITED model's on the way out, so a repack's new placement converts
 * correctly while the art frame - which a repack never moves - is what reaches the file.
 *
 * The set of reduced copies is taken once, from the graph as it was read.  An export that rewrites a model
 * image rebuilds its cache and would otherwise make its copy vanish in the middle of the pass; each query
 * reads the drawable's CURRENT texture against that set, so only a deliberate retarget of the texture moves
 * a drawable out of the cache frame.  The atlas pages, by contrast, are read live: a pack-in points its
 * drawable at a page the same export may have just minted, and that drawable stores the page's frame.
 *
 * @param CModelSource modelSource The CMO3's root model source, as read.
 */
internal class Cmo3TextureFrames(private val modelSource: CModelSource) {
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
	 * The frame a drawable's stored coordinates are in.
	 *
	 * @param CArtMeshSource source The drawable's graph source.
	 * @return Cmo3StoredFrame Page over an atlas page; Cache when unpacked or over a reduced copy; else Raster.
	 */
	fun storedFrameOf(source: CArtMeshSource): Cmo3StoredFrame {
		val sampled = sampledResourceOf(source)
		return when {
			sampled != null && isAtlasPage(sampled) -> Cmo3StoredFrame.Page
			!Cmo3Import.hasAtlasRegion(source) || samplesReducedCopy(source) -> Cmo3StoredFrame.Cache
			else -> Cmo3StoredFrame.Raster
		}
	}

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
	 * The model coordinates of a drawable's stored ones.  Page coordinates are taken verbatim.  Otherwise
	 * the stored coordinates reach the raster frame - verbatim from Raster, through the inverse of the
	 * raster-to-cache affine from Cache - and a placed tile's then go on through its placement onto its page.
	 *
	 * @param CArtMeshSource source      The drawable's graph source.
	 * @param FloatArray     storedUvs   The stored coordinates, interleaved (u, v).
	 * @param FloatArray?    storedToArt The drawable's tile's model-to-art affine, or null (or the identity)
	 *   when the tile is not placed.
	 * @return FloatArray The model coordinates; the same array when no remap applies.
	 */
	fun modelUvsOf(source: CArtMeshSource, storedUvs: FloatArray, storedToArt: FloatArray? = null): FloatArray {
		val frame = storedFrameOf(source)
		if (frame == Cmo3StoredFrame.Page) {
			return storedUvs
		}
		val artUvs = if (frame == Cmo3StoredFrame.Cache) rasterUvsOfCache(source, storedUvs) else storedUvs
		val artToModel = storedToArt?.takeUnless(::isIdentityAffine)?.let(::invertUvAffine) ?: return artUvs
		return applyUvAffine(artUvs, artToModel)
	}

	/**
	 * Cache-frame coordinates brought into the raster frame through the inverse of the raster-to-cache affine.
	 *
	 * @param CArtMeshSource source    The drawable's graph source.
	 * @param FloatArray     cacheUvs  The coordinates in the cache frame.
	 * @return FloatArray The raster-frame coordinates; the same array when the affine is absent or degenerate.
	 */
	private fun rasterUvsOfCache(source: CArtMeshSource, cacheUvs: FloatArray): FloatArray {
		val affine = cacheAffineOf(source) ?: return cacheUvs
		val determinant = affine.m00 * affine.m11 - affine.m01 * affine.m10
		// A degenerate affine would invert to NaN or infinity; the coordinates stay as stored.
		if (determinant == 0f) {
			return cacheUvs
		}
		val rasterUvs = FloatArray(cacheUvs.size)
		var componentIndex = 0
		while (componentIndex + 1 < cacheUvs.size) {
			rasterUvs[componentIndex] = inverseU(affine, determinant, cacheUvs[componentIndex], cacheUvs[componentIndex + 1])
			rasterUvs[componentIndex + 1] = inverseV(affine, determinant, cacheUvs[componentIndex], cacheUvs[componentIndex + 1])
			componentIndex += 2
		}
		return rasterUvs
	}

	/**
	 * The stored coordinates of a drawable's model ones - [modelUvsOf] run backward.  Page coordinates are
	 * stored verbatim.  Otherwise a placed tile's model coordinates come off its page through the placement
	 * into the raster frame, which Raster stores verbatim and Cache through the raster-to-cache affine.
	 *
	 * A coordinate pair the edit left alone keeps its stored value, so the float round trip cannot drift a
	 * file the rigger did not touch there.  That needs both the stored and the baseline model coordinates,
	 * and is only sound when the tile's placement is the one those were read under - the caller passes them
	 * only then.  Without them every pair is converted, and a converted pair re-imports within a few ULPs of
	 * the model's value: through a scale, not every float has a float that maps onto it exactly.
	 *
	 * @param CArtMeshSource source      The drawable's graph source.
	 * @param FloatArray     modelUvs    The model coordinates to store, interleaved (u, v).
	 * @param FloatArray?    storedToArt The drawable's tile's model-to-art affine in the model being stored,
	 *   or null (or the identity) when the tile is not placed.
	 * @param FloatArray?    storedUvs   The coordinates the graph stores now, or null.
	 * @param FloatArray?    baselineUvs The model coordinates [storedUvs] import to, or null.
	 * @return FloatArray The coordinates to store, a fresh array.
	 */
	fun storedUvsOf(
		source: CArtMeshSource,
		modelUvs: FloatArray,
		storedToArt: FloatArray? = null,
		storedUvs: FloatArray? = null,
		baselineUvs: FloatArray? = null,
	): FloatArray {
		val frame = storedFrameOf(source)
		if (frame == Cmo3StoredFrame.Page) {
			return modelUvs.copyOf()
		}
		val artUvs = storedToArt?.takeUnless(::isIdentityAffine)?.let { affine -> applyUvAffine(modelUvs, affine) } ?: modelUvs
		val cacheAffine = if (frame == Cmo3StoredFrame.Cache) cacheAffineOf(source) else null
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
			} else if (cacheAffine != null) {
				val u = artUvs[componentIndex]
				val v = artUvs[componentIndex + 1]
				result[componentIndex] = cacheAffine.m00 * u + cacheAffine.m01 * v + cacheAffine.m02
				result[componentIndex + 1] = cacheAffine.m10 * u + cacheAffine.m11 * v + cacheAffine.m12
			} else {
				result[componentIndex] = artUvs[componentIndex]
				result[componentIndex + 1] = artUvs[componentIndex + 1]
			}
			componentIndex += 2
		}
		return result
	}

	/**
	 * Whether a resource is one of the graph's atlas pages right now.
	 *
	 * The page's own instance, or a twin naming the same archive entry: a graph built rather than read can
	 * hand a texture a second resource record for the page's pixels, and it samples the page all the same.
	 *
	 * @param CImageResource resource The resource.
	 * @return Boolean True when some texture atlas holds it, or its archive entry, as its page image.
	 */
	fun isAtlasPage(resource: CImageResource): Boolean {
		// CMO3: CImageResource field imageFileBuf - the archive entry holding the pixels.
		val archivePath = resource.imageFileBuf?.archivePath
		// CMO3: CModelSource field textureManager -> CTextureManager field _textureAtlases -> CTextureAtlas
		// field cachedAtlasImage.
		return Cmo3Import.elementsOf((modelSource.textureManager as? CTextureManager)?._textureAtlases).any { atlas ->
			val page = (atlas as? CTextureAtlas)?.cachedAtlasImage as? CImageResource
			page != null && (page === resource || (archivePath != null && page.imageFileBuf?.archivePath == archivePath))
		}
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
		 * Whether a 2x3 uv affine changes nothing.
		 *
		 * @param FloatArray affine The affine (m00, m01, m02, m10, m11, m12).
		 * @return Boolean True for the identity.
		 */
		fun isIdentityAffine(affine: FloatArray): Boolean =
			affine[0] == 1f && affine[1] == 0f && affine[2] == 0f && affine[3] == 0f && affine[4] == 1f && affine[5] == 0f

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