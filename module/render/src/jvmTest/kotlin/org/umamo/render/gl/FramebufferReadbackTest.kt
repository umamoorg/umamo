package org.umamo.render.gl

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [readFramebufferPixels]'s orientation contract, and with it the `UMAMO_DUMP_PNG` path end to end.
 *
 * The read-back and the PNG dump had no coverage at all before this, while carrying the single most
 * invertible convention in the renderer: GL hands back the BOTTOM row first, and every consumer here
 * wants the TOP row first.  Get it backwards and nothing crashes - every dump is just silently mirrored,
 * which is exactly the sort of bug that survives review.
 *
 * The probe paints the framebuffer's top half red and bottom half green through the scissor box (in GL's
 * own bottom-left coordinates), so "which row is first" has a single unambiguous answer.  Then it round
 * trips through the real [PngCodec] the dump uses, so an encode-side flip would be caught too.
 */
class FramebufferReadbackTest {
	private val size = 16

	/** Reads a pixel out of a top-first [RasterImage]. Row 0 is the TOP row. */
	private fun pixelAt(image: RasterImage, column: Int, row: Int): Triple<Int, Int, Int> {
		val at = (row * image.width + column) * 4
		return Triple(
			image.rgba[at].toInt() and 0xFF,
			image.rgba[at + 1].toInt() and 0xFF,
			image.rgba[at + 2].toInt() and 0xFF,
		)
	}

	@Test
	fun readFramebufferPixelsReturnsTopRowFirstAndSurvivesThePngRoundTrip() {
		val window = createHeadlessGl()
		assumeGlContext("[framebuffer-readback]", window)
		try {
			val framebuffer = createColorFbo(size, size)
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			GL11.glViewport(0, 0, size, size)

			// Paint through the scissor box in GL's bottom-left space: y=0 is the BOTTOM half.
			GL11.glEnable(GL11.GL_SCISSOR_TEST)
			GL11.glScissor(0, 0, size, size / 2)
			GL11.glClearColor(0f, 1f, 0f, 1f)
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
			GL11.glScissor(0, size / 2, size, size / 2)
			GL11.glClearColor(1f, 0f, 0f, 1f)
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
			GL11.glDisable(GL11.GL_SCISSOR_TEST)

			val image = readFramebufferPixels(size, size)
			assertEquals(size, image.width)
			assertEquals(size, image.height)
			// GL's top half is red; a top-first image must therefore lead with red. Without the flip this
			// is green and the assertion fires - which is the whole point of the test.
			assertEquals(Triple(255, 0, 0), pixelAt(image, 0, 0), "row 0 is the framebuffer's TOP row (red)")
			assertEquals(Triple(0, 255, 0), pixelAt(image, 0, size - 1), "the last row is the BOTTOM row (green)")

			// The dump writes through PngCodec; re-decode it to prove the whole path keeps the orientation.
			val decoded = PngCodec.read(PngCodec.write(image))
			assertEquals(size, decoded.width)
			assertEquals(size, decoded.height)
			assertEquals(Triple(255, 0, 0), pixelAt(decoded, 0, 0), "the encoded PNG still leads with the top row")
			assertEquals(Triple(0, 255, 0), pixelAt(decoded, 0, size - 1))
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/** Creates and binds an RGBA8 offscreen framebuffer to render into for read-back. */
	private fun createColorFbo(width: Int, height: Int): Int {
		val framebuffer = GL30.glGenFramebuffers()
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
		val colorTexture = GL11.glGenTextures()
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTexture)
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTexture, 0)
		return framebuffer
	}

	/** Creates a hidden 1x1 GL 3.3 core window for headless rendering, or 0 if GLFW/GL is unavailable. */
	private fun createHeadlessGl(): Long {
		if (!GLFW.glfwInit()) {
			return 0L
		}
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
		GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
		GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-framebuffer-readback", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
