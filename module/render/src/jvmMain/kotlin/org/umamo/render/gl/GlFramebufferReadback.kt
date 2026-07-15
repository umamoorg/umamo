package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.umamo.format.raster.RasterImage
import org.umamo.render.puppet.flipRowsVertically

/**
 * Reads the currently bound framebuffer back to the host as a flat image.
 *
 * A free function, not a renderer method: it touches no renderer state at all - only the bound
 * framebuffer - so hanging it off [GlPuppetRenderer] only widened that class's surface.  It moves onto
 * the render device once that seam exists; this is its interim home.
 *
 * The returned rows run TOP first, matching [RasterImage]'s documented convention, so callers never
 * handle GL's bottom-up origin themselves - that fact is confined here.  Channel order needs no work:
 * `GL_RGBA` read-back already yields the byte order [RasterImage] wants.
 *
 * Synchronous, so it stalls the pipeline - fine for the verification dump it serves, wrong for a
 * per-frame path (which uses the PBO + fence read-back instead).
 *
 * @param Int viewportWidth  The bound framebuffer's width in pixels.
 * @param Int viewportHeight The bound framebuffer's height in pixels.
 * @return RasterImage The pixels, RGBA8888, top row first.
 * @pre A GL context is current and the framebuffer to read is bound.
 */
fun readFramebufferPixels(viewportWidth: Int, viewportHeight: Int): RasterImage {
	val pixels = BufferUtils.createByteBuffer(viewportWidth * viewportHeight * 4)
	GL11.glReadPixels(0, 0, viewportWidth, viewportHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
	val bottomUp = ByteArray(viewportWidth * viewportHeight * 4)
	pixels.get(bottomUp)
	return RasterImage(viewportWidth, viewportHeight, flipRowsVertically(bottomUp, viewportWidth, viewportHeight))
}
