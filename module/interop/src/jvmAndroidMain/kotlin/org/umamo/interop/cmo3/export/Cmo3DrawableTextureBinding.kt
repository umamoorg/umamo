package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.CAffine

/**
 * The texture web a synthesized drawable binds to when it has no existing source to clone - the
 * objects the image-chain builder created (docs/format/CMO3.md §4 How a Drawable References its
 * Texture).  A packed drawable binds its page: every drawable on the page references the SAME
 * GTexture2D and guid objects, so the writer hoists them exactly like the editor's own files (one atlas
 * GTexture2D for all packed drawables), and its region input places the page on the canvas.  A drawable
 * over art that was never packed binds its model image alone, sampling the image's raster through a
 * texture of its own - the shape the official editor writes for an unpacked drawable (every corpus
 * sample: a sole CTextureInput_ModelImage, current in both display modes).  The icons are the drawable's
 * own (one per drawable, like the editor's files), their PNG entries already collected or embedded by
 * whoever built the binding.
 */
public class Cmo3DrawableTextureBinding(
	/** The texture the drawable samples: its page's shared texture, or an unpacked drawable's raster texture. */
	val texture: GTexture2D,
	/** The page's CTextureAtlas guid - the atlas-region input's target - or null for an unpacked drawable. */
	val textureAtlasGuid: Guid?,
	/** The drawable's own CModelImage guid (its patch web), or null when it has no patch. */
	val modelImageGuid: Guid?,
	/** The drawable's fitted atlas-page-to-canvas placement (the region input's transform), or null for an unpacked drawable. */
	val inputImageLocalToCanvasTransform: CAffine?,
	/** The drawable's 32px thumbnail of its texture patch, or null when it has no art to show. */
	val icon32: CImageIcon? = null,
	/** The drawable's 16px thumbnail, or null likewise. */
	val icon16: CImageIcon? = null,
) {
	init {
		require((textureAtlasGuid == null) == (inputImageLocalToCanvasTransform == null)) { "an atlas region needs both its atlas and its transform" }
		require(textureAtlasGuid != null || modelImageGuid != null) { "a binding samples an atlas region, a model image, or both" }
	}

	/** Whether the drawable was never packed: it samples its model image's raster and has no atlas region. */
	val isUnpacked: Boolean
		get() = textureAtlasGuid == null
}