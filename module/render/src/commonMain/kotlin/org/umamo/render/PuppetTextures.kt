package org.umamo.render

/** A decoded RGBA image (top row first), ready for GL upload. */
class DecodedImage(val rgba: ByteArray, val width: Int, val height: Int)

/**
 * The texture data backing a puppet preview: the distinct atlas page(s) the art meshes sample, plus a
 * per-drawable index into them. Art meshes typically share one atlas page in `TEXTURE_ATLAS` mode, but
 * this structure generalises to multiple pages.
 *
 * @property List<DecodedImage> atlases               The distinct decoded atlas pages.
 * @property Map<String,Int>    atlasIndexByDrawableId Drawable id (`ArtMesh…`) → index into [atlases].
 * @property Boolean            premultipliedAlpha     Whether the atlas pixels are premultiplied.
 */
class PuppetTextures(
	val atlases: List<DecodedImage>,
	val atlasIndexByDrawableId: Map<String, Int>,
	val premultipliedAlpha: Boolean,
)
