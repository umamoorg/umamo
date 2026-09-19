package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.umamo.render.ContentBounds
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.PuppetModel
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves a model with nothing in it still paints a work surface: the renderer initializes over zero
 * drawables and zero atlas pages, frames the canvas with no camera handed to it, and rasterizes the grid
 * there.  This is the first frame of every new document, so a blank one is the first thing a rigger sees.
 *
 * What it guards is the framing, not the grid: with nothing to measure, a camera fitted to a rectangle
 * left at the float extremes draws nothing at all, which no unit test of the grid - each of which sets its
 * own camera - could notice.  Drives a high-contrast palette (black background, red major lines) so "the
 * grid is there" is a count of red pixels.  Self-skips in a display-less environment, like
 * [GridBackdropRenderTest].
 */
class EmptyModelRenderTest {
	private val viewportSize = 400
	private val canvasSize = 1200f

	private fun emptyModel(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = canvasSize,
			canvasHeight = canvasSize,
			worldOriginX = canvasSize / 2f,
			worldOriginY = -(canvasSize / 2f),
		)

	@Test
	fun anEmptyModelFramesItsCanvasAndPaintsTheGrid() {
		requireHeadlessGl("[empty-model]")
		val device = GlRenderDevice()
		val renderer = PuppetRenderer(emptyModel(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		// Black background, red major lines every 100 world units, black minor lines: twelve major cells
		// across the canvas, so the fitted view crosses a dozen red lines each way.
		renderer.setGrid(GridColors(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f), 100f, subdivisions = 1)
		renderer.setPose(emptyMap())

		assertEquals(ContentBounds(0f, -canvasSize, canvasSize, canvasSize), renderer.contentBounds(), "with nothing to measure, the view frames the canvas")

		// No camera is set: the renderer fits its own content bounds, the path a viewport's first frame takes.
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, (target as GlRenderTarget).framebuffer)
		renderer.render(target, viewportSize, viewportSize)
		val frame = readPixels(viewportSize, viewportSize)

		var redPixels = 0
		var backgroundPixels = 0
		for (pixelIndex in 0 until viewportSize * viewportSize) {
			val red = frame.get(pixelIndex * 4).toInt() and 0xFF
			val green = frame.get(pixelIndex * 4 + 1).toInt() and 0xFF
			val blue = frame.get(pixelIndex * 4 + 2).toInt() and 0xFF
			if (red > green + 60 && red > blue + 60) {
				redPixels++
			} else if (red < 40 && green < 40 && blue < 40) {
				backgroundPixels++
			}
		}
		assertTrue(redPixels > viewportSize, "the grid's major lines are on screen ($redPixels red pixels)")
		assertTrue(backgroundPixels > redPixels, "as lines over a backdrop, not a flooded frame ($backgroundPixels background pixels)")
	}

	private fun readPixels(width: Int, height: Int): ByteBuffer {
		val buffer = BufferUtils.createByteBuffer(width * height * 4)
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
		return buffer
	}
}