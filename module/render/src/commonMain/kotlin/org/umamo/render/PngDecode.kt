package org.umamo.render

/**
 * Decodes PNG bytes into a top-first RGBA [DecodedImage], or null if undecodable/absent.
 *
 * Platform seam: desktop decodes via javax.imageio, Android via android.graphics.BitmapFactory —
 * both produce the same top-first, straight-alpha RGBA byte stream, ready for GL/GLES upload.
 *
 * @param ByteArray? png The PNG bytes.
 * @return DecodedImage? The decoded RGBA image.
 */
expect fun decodePngToRgba(png: ByteArray?): DecodedImage?
