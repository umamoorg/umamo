package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CSize
import org.umamo.format.cmo3.model.gen.Anisotropy
import org.umamo.format.cmo3.model.gen.AutoLayoutLock
import org.umamo.format.cmo3.model.gen.CCachedImage
import org.umamo.format.cmo3.model.gen.CCachedImageManager
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CachedImageType
import org.umamo.format.cmo3.model.gen.FilterMode
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.MagFilter
import org.umamo.format.cmo3.model.gen.MinFilter
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.gen.WrapMode
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.composeAffine
import org.umamo.runtime.model.inversePlacementAffine

/**
 * The page half of an image chain: an atlas page's image resource, its CTextureAtlas, the one texture
 * every packed drawable on the page shares, the raster texture an unpacked drawable samples, the
 * padded cache every image carries, and the entry that packs a model image onto a page.
 */
internal object Cmo3PageWeb {
	/**
	 * CMO3: GTexture2D field mipmapLevel - what every corpus texture over a full-resolution image (an atlas
	 * page or a model image's raster) writes; a texture over a reduced cache copy writes that copy's 32.
	 */
	internal const val FULL_RESOLUTION_MIPMAP_LEVEL = 64

	/**
	 * A page's image resource: the embedded page PNG's dimensions, type, and archive link.
	 *
	 * Shared by the fresh conversion and the export's page mint, so a page minted into a retained
	 * graph is field-for-field the shape the fresh path writes.
	 *
	 * @param String path    The archive path the page PNG is stored under.
	 * @param Int    width   The page width in pixels.
	 * @param Int    height  The page height in pixels.
	 * @param Int    pngSize The encoded PNG's byte size.
	 * @return CImageResource The page resource.
	 */
	internal fun pageImageResource(path: String, width: Int, height: Int, pngSize: Int): CImageResource =
		CImageResource().apply {
			// CMO3: CImageResource attrs width/height/type + the imageFileBuf file child.
			this.width = width
			this.height = height
			type = "INT_ARGB"
			imageFileBuf = FileRef().apply { archivePath = path }
			imageFileBuf_size = pngSize
		}

	/**
	 * A page's texture atlas element, empty of entries: the editor's naming, a fresh guid, and the
	 * cached-image manager whose padding diagonal the editor's source-image sampling depends on.
	 *
	 * @param Int            pageOrdinal The zero-based page index ("TextureAtlas1" is page 0).
	 * @param Int            width       The page width in pixels.
	 * @param Int            height      The page height in pixels.
	 * @param CImageResource resource    The page's image resource.
	 * @return CTextureAtlas The atlas element.
	 */
	internal fun pageAtlas(pageOrdinal: Int, width: Int, height: Int, resource: CImageResource): CTextureAtlas =
		CTextureAtlas().apply {
			// CMO3: CTextureAtlas fields name/width/height/cachedAtlasImage/guid/modelImages/
			// cachedImageManager.
			name = "TextureAtlas${pageOrdinal + 1}"
			this.width = width
			this.height = height
			cachedAtlasImage = resource
			guid = freshGuid("CTextureAtlasGuid")
			modelImages = CArrayList<Any?>()
			cachedImageManager = paddedCacheManager(resource, width, height)
		}

	/**
	 * A page's ONE shared texture: every drawable on the page references this instance (the writer
	 * hoists it), like the editor's own atlas files.
	 *
	 * @param String?        atlasName The owning atlas's name, which the texture shares.
	 * @param CImageResource resource  The page's image resource the texture samples.
	 * @return GTexture2D The page texture.
	 */
	internal fun pageTexture(atlasName: String?, resource: CImageResource): GTexture2D {
		val texture =
			GTexture2D().apply {
				// CMO3: GTexture2D - the page's shared texture.
				name = atlasName
				// CMO3: GTexture fields wrapMode / filterMode / anisotropy - the editor's fixed
				// sampling setup on every corpus texture.
				wrapMode = WrapMode.CLAMP_TO_BORDER
				guid = freshGuid("GTextureGuid")
				anisotropy = Anisotropy.ON
				srcImageResource = resource
				transformImageResource01toLogical01 = CAffine()
				mipmapLevel = FULL_RESOLUTION_MIPMAP_LEVEL
				// CMO3: GTexture2D field isPremultiplied - true on EVERY corpus texture (178 of
				// 178, every era), including the many whose embedded PNG bytes are straight
				// alpha.  The flag records the editor's texture-render/upload convention, not
				// the byte storage (see the Premultiplied section), so a MOC3 sidecar page -
				// straight alpha like the corpus ones - writes true as well.
				isPremultiplied = true
			}
		texture.filterMode =
			FilterMode().apply {
				minFilter = MinFilter.LINEAR_MIPMAP_LINEAR
				magFilter = MagFilter.LINEAR
				owner = texture
			}

		return texture
	}

	/**
	 * The texture a drawable over never-packed art samples: its model image's raster, through the raster's
	 * padding scale, shared by every drawable over that art.  Every corpus drawable with no atlas region
	 * writes this shape (`Cmo3UnpackedDrawableProbeTest`, 10 drawables in 6 files): the raster as
	 * `srcImageResource`, its dims over its 64-aligned padding as `transformImageResource01toLogical01` (so
	 * the stored coordinates are in the cache frame), mip level 64, and the page texture's fixed sampling
	 * fields.
	 *
	 * @param String?        textureName The texture's name; the corpus names it after its model image.
	 * @param CImageResource resource    The model image's raster, which is also its layer's resource.
	 * @return GTexture2D The raster texture.
	 */
	internal fun rasterTexture(textureName: String?, resource: CImageResource): GTexture2D {
		val texture = pageTexture(textureName, resource)
		// CMO3: GTexture2D field transformImageResource01toLogical01 - the raster's padding scale, which is
		// what puts an unpacked drawable's stored coordinates in the cache frame (Cmo3TextureFrames).
		texture.transformImageResource01toLogical01 = paddedFrameAffine(resource.width, resource.height)

		return texture
	}

	/**
	 * The raster-to-cache affine of an image: its size over its 64-aligned padding, per axis.  The cache
	 * frame the editor keeps for every image, and the scale a texture sampling the raster carries as
	 * `GTexture2D.transformImageResource01toLogical01` - every corpus texture over a model image's raster
	 * writes exactly its cache's value.
	 *
	 * @param Int width  The image width in pixels.
	 * @param Int height The image height in pixels.
	 * @return CAffine A fresh diagonal affine.
	 */
	internal fun paddedFrameAffine(width: Int, height: Int): CAffine =
		CAffine().apply {
			m00 = width.toFloat() / ((width + 63) / 64 * 64)
			m11 = height.toFloat() / ((height + 63) / 64 * 64)
		}

	/**
	 * The cached-image manager the editor expects on every model image and texture atlas: the raw
	 * page cached as itself at SCALE_1 (nothing prerendered).
	 *
	 * @param CImageResource pageResource The page image resource.
	 * @param Int            width        The page width in pixels.
	 * @param Int            height       The page height in pixels.
	 * @return CCachedImageManager The fresh manager.
	 */
	internal fun paddedCacheManager(pageResource: CImageResource, width: Int, height: Int): CCachedImageManager =
		CCachedImageManager().apply {
			// CMO3: CCachedImageManager fields defaultCacheType / rawImage / cachedImages /
			// requiredMipmapLevel (corpus flat imports cache the raw resource itself).
			defaultCacheType = CachedImageType.SCALE_1
			rawImage = pageResource
			cachedImages =
				ArrayList<Any?>(
					mutableListOf(
						CCachedImage().apply {
							_cachedImageResource = pageResource
							isSharedImage = true
							rawImageSize =
								CSize().apply {
									this.width = width
									this.height = height
								}
							reductionRatio = 1
							mipmapLevel = 64
							hasMargin = false
							isCleaned = false
							// CMO3: CCachedImage field transformRawImageToCachedImage - the raw
							// dims over the cache raster's 64-aligned padding, per axis: every
							// corpus reductionRatio=1 cache writes exactly dim / ceil64(dim)
							// (1073 of 1073).  Identity here makes the editor's source-image
							// sampling stretch the art by the padding fraction.
							transformRawImageToCachedImage = paddedFrameAffine(width, height)
						},
					),
				)
			requiredMipmapLevel = 64
		}

	/**
	 * Writes a packing origin into [transform]: the position in page pixels, the packer's resampling
	 * scale, and its rotation in degrees.
	 *
	 * CMO3: ModelImageEntry field materialLocalToAtlasTransform, a GTransform2 whose eulerAngle is in
	 * DEGREES.  Shared by the fresh conversion, the export's pack-in mint, and the placement lowering,
	 * so the three write one convention.
	 *
	 * @param GTransform2 transform       The transform to write into; an entry's own, so it keeps its instance.
	 * @param Float       positionX       The packing origin's x on the page.
	 * @param Float       positionY       The packing origin's y on the page.
	 * @param Float       scaleX          The packer's horizontal scale.
	 * @param Float       scaleY          The packer's vertical scale.
	 * @param Float       rotationDegrees The packer's rotation, counter-clockwise degrees.
	 * @return GTransform2 The same [transform].
	 */
	internal fun writePacking(
		transform: GTransform2,
		positionX: Float,
		positionY: Float,
		scaleX: Float,
		scaleY: Float,
		rotationDegrees: Float,
	): GTransform2 {
		transform.position =
			GVector2().apply {
				x = positionX
				y = positionY
			}
		transform.scale =
			GVector2().apply {
				x = scaleX
				y = scaleY
			}
		transform.eulerAngle = rotationDegrees

		return transform
	}

	/**
	 * [writePacking] from a runtime placement.
	 *
	 * @param GTransform2    transform The transform to write into.
	 * @param AtlasPlacement placement Where the art sits on its page.
	 * @return GTransform2 The same [transform].
	 */
	internal fun writePacking(transform: GTransform2, placement: AtlasPlacement): GTransform2 =
		writePacking(
			transform,
			placement.positionX,
			placement.positionY,
			placement.scaleX,
			placement.scaleY,
			placement.rotationDegrees,
		)

	/**
	 * A page's packed entry for one model image: the membership back-reference, the shared guid, and
	 * the transform pair the editor's atlas and mesh-edit views derive mesh-over-texture placement
	 * from.
	 *
	 * CMO3: ModelImageEntry fields atlas / modelImageGuid / autoLayoutLock /
	 * atlasLocalToCanvasTransform / materialLocalToAtlasTransform.  The guid is the model image's OWN
	 * instance, so the writer shares it like the editor's files; autoLayoutLock is an AutoLayoutLock
	 * enum (v="NONE") - the editor's field is enum-typed and class-casts a boolean.  Shared by the
	 * fresh conversion and the export's pack-in mint, so an entry minted into a retained graph is
	 * field-for-field the shape the fresh path writes.
	 *
	 * @param CTextureAtlas atlas              The page the entry lives in.
	 * @param Any?          modelImageGuid     The model image's own guid instance.
	 * @param CAffine       atlasLocalToCanvas The fitted atlas-to-canvas placement, an independent instance.
	 * @param GTransform2   packing            The packing origin on the page.
	 * @return ModelImageEntry The entry.
	 */
	internal fun packedEntry(
		atlas: CTextureAtlas,
		modelImageGuid: Any?,
		atlasLocalToCanvas: CAffine,
		packing: GTransform2,
	): ModelImageEntry =
		ModelImageEntry().apply {
			this.atlas = atlas
			this.modelImageGuid = modelImageGuid
			autoLayoutLock = AutoLayoutLock.NONE
			atlasLocalToCanvasTransform = atlasLocalToCanvas
			materialLocalToAtlasTransform = packing
		}

	/**
	 * [packedEntry] at a runtime placement: the atlas-to-canvas half from its six components, and the
	 * packing origin the placement records.
	 *
	 * @param CTextureAtlas  atlas              The page the entry lives in.
	 * @param Any?           modelImageGuid     The model image's own guid instance.
	 * @param FloatArray     atlasLocalToCanvas The atlas-to-canvas half, all six components.
	 * @param AtlasPlacement placement          Where the art sits on the page.
	 * @return ModelImageEntry The entry.
	 */
	internal fun packedEntry(
		atlas: CTextureAtlas,
		modelImageGuid: Any?,
		atlasLocalToCanvas: FloatArray,
		placement: AtlasPlacement,
	): ModelImageEntry = packedEntry(atlas, modelImageGuid, affineOf(atlasLocalToCanvas), writePacking(GTransform2(), placement))
}

/**
 * The atlas-to-canvas half a packed entry carries at [placement]: the tile's full canvas placement
 * composed with the placement's inverse, so the entry pair keeps composing to
 * CModelImage._materialLocalToCanvasTransform.
 *
 * One helper shared by the entry lowering and the drawable region-input rewrite, so the two values
 * the corpus keeps equal stay bit-identical when both are written.
 *
 * @param FloatArray     canvasAffine The tile's canvas placement, all six components.
 * @param AtlasPlacement placement    Where the art sits on its page.
 * @return FloatArray? The composed 2x3 affine, or null when the placement is degenerate.
 */
internal fun atlasLocalToCanvasFor(canvasAffine: FloatArray, placement: AtlasPlacement): FloatArray? {
	val inverse = inversePlacementAffine(placement) ?: return null

	return composeAffine(canvasAffine, inverse)
}