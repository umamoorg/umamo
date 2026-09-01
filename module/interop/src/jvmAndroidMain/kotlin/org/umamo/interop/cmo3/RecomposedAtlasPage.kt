package org.umamo.interop.cmo3

/**
 * One recomposed atlas page bound for a CMO3 export: the pixels the session's placements actually
 * denote, encoded and ready to replace the file's stored page image.
 *
 * A plain class, not a data class: it carries an encoded pixel buffer.
 *
 * @property Int       pageIndex The page in the model's own numbering (PuppetAtlas.pages order,
 *                               which is the texture manager's _textureAtlases order).
 * @property ByteArray pngBytes  The page encoded as PNG.
 * @property Int       width     The page width in pixels.
 * @property Int       height    The page height in pixels.
 */
class RecomposedAtlasPage(
	val pageIndex: Int,
	val pngBytes: ByteArray,
	val width: Int,
	val height: Int,
)