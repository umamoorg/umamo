package org.umamo.render.puppet

import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.scaledToLongerEdge
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.TextureWrap

/**
 * Uploads art as a linearly filtered RGBA texture the device can sample, scaled down when an edge passes
 * [RenderDevice.maxTextureSize].  Art is always sampled through normalized coordinates, so the smaller copy
 * shows the same art at a lower resolution where the full image would not upload at all.
 *
 * @param Int         width  The art's width in pixels.
 * @param Int         height The art's height in pixels.
 * @param ByteArray   rgba   The pixels, straight alpha, top row first.
 * @param TextureWrap wrap   How samples past the edge read.
 * @return GpuTexture The texture.
 */
internal fun RenderDevice.createArtTexture(width: Int, height: Int, rgba: ByteArray, wrap: TextureWrap = TextureWrap.ClampToEdge): GpuTexture {
	val maxEdge = maxTextureSize()

	if (width <= maxEdge && height <= maxEdge) {
		return createTexture(width, height, TextureFormat.Rgba8, TextureFilter.Linear, rgba, wrap)
	}

	val fitted = RasterImage(width, height, rgba).scaledToLongerEdge(maxEdge)

	return createTexture(fitted.width, fitted.height, TextureFormat.Rgba8, TextureFilter.Linear, fitted.rgba, wrap)
}