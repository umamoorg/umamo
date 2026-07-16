package org.umamo.render.gl

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.format.raster.RasterImage
import org.umamo.render.SupersampledSurface
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the viewport read-back tail end to end: [SupersampledSurface] (draw at Nx, resolve to display
 * size), [GlRenderDevice.resolve] (the box-downscale blit), and the asynchronous
 * [GlRenderDevice.beginReadback] / [GlRenderDevice.pollReadback] ticket path.
 *
 * This stack had NO coverage at all in its previous life as the app-side SupersampleFramebuffer +
 * PixelReadbackPool - the per-frame path every viewport pixel travels was defended by nothing.  The
 * probe paints the supersampled draw target's top half red and bottom half green (in GL's bottom-up
 * scissor coordinates), resolves, and then requires: the sync read-back sees red FIRST (top-first
 * contract, correct through the downscale), and the async ticket delivers byte-identical pixels in
 * submission order.
 *
 * ビューポート読み戻しの末尾（スーパーサンプル解決＋非同期読み戻し）を初めて検証する。
 */
class SupersampleReadbackTest {
	private val displaySize = 64
	private val scale = 2

	@Test
	fun resolveDownscalesAndAsyncReadbackMatchesSync() {
		val window = createHeadlessGl()
		assumeGlContext("[supersample-readback]", window)
		try {
			val device = GlRenderDevice()
			val surface = SupersampledSurface(device, scale)
			val drawTarget = surface.ensure(displaySize, displaySize) as GlRenderTarget
			assertEquals(displaySize * scale, drawTarget.width, "the draw target is supersampled")

			paintHalves(drawTarget)
			surface.resolve()

			// Sync read of the resolve target: display-sized, top row red - the downscale kept regions in
			// place and the read-back honoured the top-first contract.
			val sync = device.readPixels(surface.resolveTarget)
			assertEquals(displaySize, sync.width)
			assertEquals(displaySize, sync.height)
			assertPixel(sync, row = 0, expectedRed = 255, expectedGreen = 0, note = "top row is the red half")
			assertPixel(sync, row = displaySize - 1, expectedRed = 0, expectedGreen = 255, note = "bottom row is the green half")

			// Async: two tickets in flight at once, collected in submission order, byte-identical to sync.
			val firstTicket = device.beginReadback(surface.resolveTarget)
			val secondTicket = device.beginReadback(surface.resolveTarget)
			val first = awaitReadback(device, firstTicket)
			val second = awaitReadback(device, secondTicket)
			assertContentEquals(sync.rgba, first.rgba, "the async read-back returns the same pixels as the sync one")
			assertContentEquals(sync.rgba, second.rgba, "a second in-flight ticket delivers too")

			// A size change recreates both targets at the new extents.
			val resized = surface.ensure(displaySize / 2, displaySize / 2) as GlRenderTarget
			assertEquals(displaySize / 2 * scale, resized.width, "ensure() recreates the draw target on resize")
			assertEquals(displaySize / 2, (surface.resolveTarget as GlRenderTarget).width)
			surface.dispose()
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/** Polls [ticket] to completion, bounded so a wedged fence fails the test instead of hanging it. */
	private fun awaitReadback(device: GlRenderDevice, ticket: org.umamo.render.device.ReadbackTicket): RasterImage {
		repeat(2000) {
			device.pollReadback(ticket)?.let { return it }
			Thread.sleep(1)
		}
		error("read-back never completed - the fence did not signal within 2s")
	}

	/** Paints the target's TOP half red and BOTTOM half green, in GL's bottom-left scissor coordinates. */
	private fun paintHalves(target: GlRenderTarget) {
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer)
		GL11.glViewport(0, 0, target.width, target.height)
		GL11.glEnable(GL11.GL_SCISSOR_TEST)
		GL11.glScissor(0, 0, target.width, target.height / 2)
		GL11.glClearColor(0f, 1f, 0f, 1f) // GL's bottom half = the image's bottom = green
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
		GL11.glScissor(0, target.height / 2, target.width, target.height / 2)
		GL11.glClearColor(1f, 0f, 0f, 1f) // GL's top half = the image's top = red
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
		GL11.glDisable(GL11.GL_SCISSOR_TEST)
	}

	/** Asserts the pixel at column 0 of [row] (top-first) is the expected solid red/green. */
	private fun assertPixel(image: RasterImage, row: Int, expectedRed: Int, expectedGreen: Int, note: String) {
		val at = row * image.width * 4
		val red = image.rgba[at].toInt() and 0xFF
		val green = image.rgba[at + 1].toInt() and 0xFF
		assertNotNull(image.rgba, note)
		assertTrue(red == expectedRed && green == expectedGreen, "$note: got rgb($red, $green, _) at row $row")
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-supersample-readback", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
