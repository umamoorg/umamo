package org.umamo.editor.desktop.viewport

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer

/**
 * Supersample factor for the offscreen render: the puppet, its clip masks, and the grid are drawn at this
 * multiple of the display resolution and box-downscaled on resolve. This anti-aliases geometry edges AND the
 * in-shader clip masks together (MSAA cannot smooth a per-fragment mask lookup), with no atlas mipmaps (so no
 * cross-region atlas bleed). 2x costs 4x the fill of the draw buffer; the read-back stays at display size.
 *
 * Shared across [SupersampleFramebuffer] (which sizes the draw target at this multiple) and
 * [OffscreenRenderEngine] (which scales the render dimensions and camera zoom by it), so it lives at package
 * scope rather than as a private const in either.
 */
internal const val RENDER_SUPERSAMPLE = 2

/**
 * Owns the two framebuffers of the offscreen render: a supersampled draw target the renderer draws into, and
 * a display-size resolve target the draw buffer is box-downscaled into for read-back. All GL objects are
 * created, resized, used, and deleted on the render thread (the caller guarantees a current context); this
 * class never crosses threads.
 *
 * The draw target is a RENDER_SUPERSAMPLE x RGBA8 renderbuffer (the renderer draws puppet + masks + grid
 * here); the resolve target is a display-size RGBA8 texture (the draw buffer is downscaled into it, then
 * glReadPixels reads it - a renderbuffer draw target cannot be read back directly).
 */
internal class SupersampleFramebuffer {
	// Display-size resolve target + read-back source: the supersampled draw target is downscaled here, then
	// glReadPixels reads it (the draw renderbuffer cannot be read back directly).
	private var framebuffer: Int = 0
	private var colorTexture: Int = 0

	// Supersampled draw target (RENDER_SUPERSAMPLE x the display size): the renderer draws the puppet, masks,
	// and grid here, then we box-downscale into `framebuffer` - the supersample resolve.
	private var drawFramebuffer: Int = 0
	private var drawColorRenderbuffer: Int = 0

	// Last DISPLAY size allocated (the draw buffer is RENDER_SUPERSAMPLE× this).
	private var framebufferWidth: Int = -1
	private var framebufferHeight: Int = -1

	/** The display-size resolve FBO the read-back reads from (bound as GL_READ_FRAMEBUFFER by the caller). */
	val resolveFbo: Int
		get() = framebuffer

	/**
	 * Allocates the reusable GL objects (the two framebuffers, the draw renderbuffer, and the resolve
	 * texture) and sets the resolve texture's filtering.  Call once, on the render thread, after the context
	 * is current and before the first [ensure].
	 */
	fun allocate() {
		drawFramebuffer = GL30.glGenFramebuffers()
		drawColorRenderbuffer = GL30.glGenRenderbuffers()
		framebuffer = GL30.glGenFramebuffers()
		colorTexture = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
	}

	/**
	 * Ensures both framebuffers' attachments match [width] x [height] (the draw target at RENDER_SUPERSAMPLE
	 * x), reallocating on a size change, and binds the supersampled draw framebuffer as the current draw
	 * target so the renderer draws into it.
	 *
	 * @param Int width  The display width.
	 * @param Int height The display height.
	 */
	fun ensure(width: Int, height: Int) {
		if (width != framebufferWidth || height != framebufferHeight) {
			// Supersampled color for the draw target - the renderer draws here at RENDER_SUPERSAMPLE x.
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, drawFramebuffer)
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, drawColorRenderbuffer)
			GL30.glRenderbufferStorage(
				GL30.GL_RENDERBUFFER,
				GL11.GL_RGBA8,
				width * RENDER_SUPERSAMPLE,
				height * RENDER_SUPERSAMPLE,
			)
			GL30.glFramebufferRenderbuffer(
				GL30.GL_FRAMEBUFFER,
				GL30.GL_COLOR_ATTACHMENT0,
				GL30.GL_RENDERBUFFER,
				drawColorRenderbuffer,
			)
			// Display-size color for the resolve target - the draw buffer is downscaled here, then read back.
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture)
			GL11.glTexImage2D(
				GL11.GL_TEXTURE_2D,
				0,
				GL11.GL_RGBA8,
				width,
				height,
				0,
				GL11.GL_RGBA,
				GL11.GL_UNSIGNED_BYTE,
				null as ByteBuffer?,
			)
			GL30.glFramebufferTexture2D(
				GL30.GL_FRAMEBUFFER,
				GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D,
				colorTexture,
				0,
			)
			framebufferWidth = width
			framebufferHeight = height
		}
		// Draw into the supersampled FBO; downscaleResolve blits it into `framebuffer` before the read-back.
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, drawFramebuffer)
	}

	/**
	 * Downscales the supersampled draw target into the display-size [framebuffer] (the read-back source) with
	 * a linear box blit - the supersample resolve that anti-aliases geometry edges and clip masks alike.
	 * Leaves [framebuffer] bound for the read-back that follows.
	 *
	 * @param Int renderWidth  The supersampled draw width.
	 * @param Int renderHeight The supersampled draw height.
	 * @param Int width        The display width.
	 * @param Int height       The display height.
	 */
	fun downscaleResolve(renderWidth: Int, renderHeight: Int, width: Int, height: Int) {
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, drawFramebuffer)
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer)
		GL30.glBlitFramebuffer(
			0,
			0,
			renderWidth,
			renderHeight,
			0,
			0,
			width,
			height,
			GL11.GL_COLOR_BUFFER_BIT,
			GL11.GL_LINEAR,
		)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
	}

	/**
	 * Deletes the framebuffers, the draw renderbuffer, and the resolve texture.  Runs on the render thread
	 * during teardown, after the caller's single glFinish barrier.  A no-op if never allocated.
	 */
	fun dispose() {
		if (framebuffer != 0) {
			GL30.glDeleteFramebuffers(framebuffer)
			GL11.glDeleteTextures(colorTexture)
			GL30.glDeleteFramebuffers(drawFramebuffer)
			GL30.glDeleteRenderbuffers(drawColorRenderbuffer)
		}
	}
}
