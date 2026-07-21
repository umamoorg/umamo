package org.umamo.render

/** A decoded RGBA image (top row first), ready for GL upload. */
class DecodedImage(val rgba: ByteArray, val width: Int, val height: Int)

/**
 * The texture data backing a puppet preview: the distinct atlas page(s) the art meshes sample, plus a
 * per-drawable index into them.  Art meshes typically share one atlas page in `TEXTURE_ATLAS` mode, but
 * this structure generalises to multiple pages.
 *
 * [premultipliedAlpha] is the OR-fold of the CMO3 `GTexture2D.isPremultiplied` bits and is currently
 * UNCONSUMED - and is deliberately kept that way.  It is an editor texture-upload-convention flag
 * (serialized true on nearly every model), NOT a claim that the decoded bytes are premultiplied: every
 * ingested page is straight-alpha (PngCodec yields straight alpha; the fragment shader premultiplies
 * in-shader with source factor GL_ONE), so wiring this to "unpremultiply the bytes" would over-darken
 * the whole corpus.  The version-dependent premultiplied-vs-straight COMPOSITING distinction (Cubism
 * 5.2 and earlier plus the "(Before 5.3)" modes composite premultiplied; 5.3+ Color blend composites
 * straight) is a separate axis, carried by `BlendMode.isLegacy` and handled in
 * `BlendMath.compositeReference`, not by this field.  See docs/format/CMO3.md, "Premultiplied vs
 * straight alpha".
 *
 * @property List<DecodedImage> atlases               The distinct decoded atlas pages.
 * @property Map<String,Int>    atlasIndexByDrawableId Drawable id (`ArtMesh…`) → index into [atlases].
 * @property Boolean            premultipliedAlpha     Aggregated `GTexture2D.isPremultiplied`; unconsumed
 *                                                     (see the note above) - do not drive rendering off it.
 */
class PuppetTextures(
	val atlases: List<DecodedImage>,
	val atlasIndexByDrawableId: Map<String, Int>,
	val premultipliedAlpha: Boolean,
)
