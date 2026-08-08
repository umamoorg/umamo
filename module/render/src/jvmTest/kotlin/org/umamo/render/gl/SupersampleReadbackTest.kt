package org.umamo.render.gl

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.umamo.format.raster.RasterImage
import org.umamo.render.SupersampledSurface
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
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
		requireHeadlessGl("[supersample-readback]")
		val device = GlRenderDevice()
		val surface = SupersampledSurface(device, scale)
		val drawTarget = surface.ensure(displaySize, displaySize) as GlRenderTarget
		assertEquals(displaySize * scale, drawTarget.width, "the draw target is supersampled")

		paintHalves(drawTarget, displaySize * scale, displaySize * scale)
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

		// A completed poll consumes the ticket EXACTLY ONCE, at the fence-signal: re-polling a finished
		// ticket must trip the spent check, not silently re-map a recycled PBO. This pins the ordering
		// fix - the ticket is marked spent the moment the fence signals, before the map, so there is no
		// window in which a consumed ticket reads as still-in-flight and gets re-polled.
		assertFailsWith<IllegalStateException>("re-polling a consumed ticket is a spent-check trip") {
			device.pollReadback(firstTicket)
		}

		// Grow-only: a smaller ensure keeps the high-water allocation (the SAME target instances)
		// and just narrows the used region; the render, resolve, and read-back all confine
		// themselves to it.
		val halfSize = displaySize / 2
		val shrunk = surface.ensure(halfSize, halfSize) as GlRenderTarget
		assertSame(drawTarget, shrunk, "a smaller ensure never reallocates the draw target")
		assertEquals(displaySize * scale, shrunk.width, "the draw target keeps its high-water allocation")
		assertEquals(displaySize, (surface.resolveTarget as GlRenderTarget).width, "the resolve target keeps its allocation too")
		assertEquals(halfSize, surface.usedWidth, "the used region follows the request")
		assertEquals(halfSize, surface.usedHeight)

		paintHalves(shrunk, halfSize * scale, halfSize * scale)
		surface.resolve()
		val small = device.readPixels(surface.resolveTarget, halfSize, halfSize)
		assertEquals(halfSize, small.width)
		assertEquals(halfSize, small.height)
		assertPixel(small, row = 0, expectedRed = 255, expectedGreen = 0, note = "used-region top row is red after the shrink")
		assertPixel(small, row = halfSize - 1, expectedRed = 0, expectedGreen = 255, note = "used-region bottom row is green after the shrink")
		val smallTicket = device.beginReadback(surface.resolveTarget, halfSize, halfSize)
		assertContentEquals(small.rgba, awaitReadback(device, smallTicket).rgba, "the used-region async read-back matches the sync one")
		surface.dispose()
	}

	/** Polls [ticket] to completion, bounded so a wedged fence fails the test instead of hanging it. */
	private fun awaitReadback(device: GlRenderDevice, ticket: org.umamo.render.device.ReadbackTicket): RasterImage {
		repeat(2000) {
			device.pollReadback(ticket)?.let { return it }
			Thread.sleep(1)
		}
		error("read-back never completed - the fence did not signal within 2s")
	}

	/**
	 * Paints the used region's TOP half red and BOTTOM half green, in GL's bottom-left scissor
	 * coordinates.  The used region is the target's bottom-left rows (the pass viewport anchors at
	 * the origin), so a capacity-larger target is painted only where the render would draw.
	 *
	 * @param GlRenderTarget target The target to paint.
	 * @param Int usedWidth The used extent along x, in pixels.
	 * @param Int usedHeight The used extent along y, in pixels.
	 */
	private fun paintHalves(target: GlRenderTarget, usedWidth: Int, usedHeight: Int) {
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer)
		GL11.glViewport(0, 0, usedWidth, usedHeight)
		GL11.glEnable(GL11.GL_SCISSOR_TEST)
		GL11.glScissor(0, 0, usedWidth, usedHeight / 2)
		GL11.glClearColor(0f, 1f, 0f, 1f) // GL's bottom half = the image's bottom = green
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
		GL11.glScissor(0, usedHeight / 2, usedWidth, usedHeight / 2)
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
}