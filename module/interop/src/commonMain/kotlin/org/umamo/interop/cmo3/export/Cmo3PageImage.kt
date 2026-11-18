package org.umamo.interop.cmo3.export

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage

/**
 * One atlas page: the original PNG bytes (model3 texture order) plus its pixel dimensions, and the page's
 * pixels when the caller already holds them decoded.  Both export paths take their pages in this form:
 * the fresh conversion builds its image chain over them, and a retained export writes a repack's
 * recomposed pages from them.
 *
 * The decoded pixels are what the conversion cuts patches and crops out of; without them it decodes
 * [pngBytes] itself, one page at a time.  An open document holds every page decoded for the viewport, so
 * handing those over spares the export a second copy of each page - hundreds of megabytes on a large
 * atlas.  They must be exactly the pixels [pngBytes] decodes to, and the conversion only ever reads them:
 * they are the caller's live buffer, the one its viewport draws from.
 *
 * @property ByteArray    pngBytes The page's PNG bytes, written into the file as they are.
 * @property Int          width    The page's width in pixels.
 * @property Int          height   The page's height in pixels.
 * @property RasterImage? decoded  The pixels [pngBytes] decodes to, straight alpha, or null to decode them.
 */
public class Cmo3PageImage(
	public val pngBytes: ByteArray,
	public val width: Int,
	public val height: Int,
	public val decoded: RasterImage? = null,
) {
	init {
		if (decoded != null) {
			require(decoded.width == width && decoded.height == height && decoded.rgba.size == width * height * 4) {
				"decoded pixels ${decoded.width}x${decoded.height} (${decoded.rgba.size} bytes) do not match the ${width}x$height page"
			}
		}
	}

	/**
	 * The page's pixels: the caller's decoded ones when it handed them over, else a fresh decode the
	 * caller owns and drops when done with the page.
	 *
	 * @return RasterImage The page's straight-alpha pixels.
	 */
	internal fun decodedPixels(): RasterImage = decoded ?: PngCodec.read(pngBytes)
}