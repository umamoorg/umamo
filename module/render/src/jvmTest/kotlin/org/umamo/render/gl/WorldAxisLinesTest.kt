package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the world-origin axis lines actually rasterize: with [PuppetRenderer.setWorldAxesVisible]
 * on, the frame gains a horizontal red X axis row and a vertical blue Z axis column crossing at the
 * model's world origin, and with the flag off (the default, which keeps render-diff tests line-free)
 * the frame contains neither.  Renders into an offscreen FBO at a fixed 1:1 camera so the origin's
 * pixel position is predictable.  Skips in a display-less environment, like [GeometryReuploadTest] - via a
 * JUnit assumption, so the run reports SKIPPED rather than a green pass that asserted nothing.
 */
class WorldAxisLinesTest {
	private val viewportSize = 400
	private val paramA = ParameterId("A")

	// A small keyed quad well away from the origin so the axis pixels are never confused with art.
	private fun model(): PuppetModel {
		val positions = floatArrayOf(120f, 120f, 160f, 120f, 120f, 160f, 160f, 160f)
		val drawable =
			Drawable(
				id = DrawableId("probe"),
				name = "probe",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2, 1, 3, 2)),
				keyforms = KeyformGrid(listOf(KeyformAxis(paramA, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size))))),
			)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
			// The origin under test sits at world (0, 0) - the viewport center at the fixed 1:1 camera.
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		)
	}

	@Test
	fun axisLinesDrawOnlyWhenEnabled() {
		val window = createHeadlessGl()
		assumeGlContext("[world-axis-lines]", window)
		try {
			val device = GlRenderDevice()
			val renderer = PuppetRenderer(model(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
			renderer.initGl()
			// Device-owned target; the raw fbo id is read for this test's own bottom-up glReadPixels.
			val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
			val framebuffer = (target as GlRenderTarget).framebuffer
			// A fixed 1:1 camera centered on the world origin: the axes must cross at the viewport center.
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))
			renderer.setPose(emptyMap())

			// Default: axes off - the frame must contain no axis-colored pixels (render-diff tests rely on this).
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val framePlain = readPixels(viewportSize, viewportSize)
			assertEquals(0, maxRedRunInCenterRows(framePlain), "no X axis pixels while the flag is off")
			assertEquals(0, maxBlueRunInCenterColumns(framePlain), "no Z axis pixels while the flag is off")

			// Enabled: a red row and a blue column cross at the center.
			renderer.setWorldAxesVisible(true)
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val frameAxes = readPixels(viewportSize, viewportSize)
			val redRun = maxRedRunInCenterRows(frameAxes)
			val blueRun = maxBlueRunInCenterColumns(frameAxes)
			println("[world-axis-lines] redRun=$redRun blueRun=$blueRun (viewport $viewportSize)")
			assertTrue(redRun > viewportSize / 2, "the X axis row spans the viewport (got $redRun red pixels)")
			assertTrue(blueRun > viewportSize / 2, "the Z axis column spans the viewport (got $blueRun blue pixels)")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/** Counts red-dominant pixels per row across the center band and returns the largest row count. */
	private fun maxRedRunInCenterRows(frame: ByteBuffer): Int {
		var best = 0
		for (row in viewportSize / 2 - 3..viewportSize / 2 + 3) {
			var count = 0
			for (col in 0 until viewportSize) {
				if (isRedDominant(frame, col, row)) {
					count++
				}
			}
			best = maxOf(best, count)
		}
		return best
	}

	/** Counts blue-dominant pixels per column across the center band and returns the largest column count. */
	private fun maxBlueRunInCenterColumns(frame: ByteBuffer): Int {
		var best = 0
		for (col in viewportSize / 2 - 3..viewportSize / 2 + 3) {
			var count = 0
			for (row in 0 until viewportSize) {
				if (isBlueDominant(frame, col, row)) {
					count++
				}
			}
			best = maxOf(best, count)
		}
		return best
	}

	/** True when the pixel reads clearly red over green and blue (the X axis color over any backdrop). */
	private fun isRedDominant(frame: ByteBuffer, col: Int, row: Int): Boolean {
		val pixel = (row * viewportSize + col) * 4
		val red = frame.get(pixel).toInt() and 0xFF
		val green = frame.get(pixel + 1).toInt() and 0xFF
		val blue = frame.get(pixel + 2).toInt() and 0xFF
		return red > green + 40 && red > blue + 40
	}

	/** True when the pixel reads clearly blue over red and green (the Z axis color over any backdrop). */
	private fun isBlueDominant(frame: ByteBuffer, col: Int, row: Int): Boolean {
		val pixel = (row * viewportSize + col) * 4
		val red = frame.get(pixel).toInt() and 0xFF
		val green = frame.get(pixel + 1).toInt() and 0xFF
		val blue = frame.get(pixel + 2).toInt() and 0xFF
		return blue > red + 40 && blue > green + 40
	}

	/** Reads the bound framebuffer's RGBA pixels into a fresh buffer (bottom-up rows). */
	private fun readPixels(width: Int, height: Int): ByteBuffer {
		val buffer = BufferUtils.createByteBuffer(width * height * 4)
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
		return buffer
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-world-axis-lines", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
