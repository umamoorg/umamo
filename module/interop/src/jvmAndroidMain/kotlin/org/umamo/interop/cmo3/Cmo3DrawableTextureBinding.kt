package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.CAffine

/**
 * The texture web a synthesized drawable binds to when it has no existing source to clone - the
 * per-page objects the image-chain builder created (docs/format/CMO3.md §4 How a Drawable
 * References its Texture).  Instances are shared per atlas page: every drawable on the page
 * references the SAME GTexture2D and guid objects, so the writer hoists them exactly like the
 * editor's own files (one atlas GTexture2D for all packed drawables).
 */
public class Cmo3DrawableTextureBinding(
	/** The page's shared texture (srcImageResource = the page CImageResource). */
	val texture: GTexture2D,
	/** The page's CTextureAtlas guid - the atlas-region input's target. */
	val textureAtlasGuid: Guid,
	/** The page's CModelImage guid, or null when the chain carries no model image. */
	val modelImageGuid: Guid?,
	/** The atlas-region input's page-to-canvas placement transform. */
	val inputImageLocalToCanvasTransform: CAffine,
)
