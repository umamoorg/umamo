package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
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
import kotlin.test.assertTrue

/**
 * Proves the world-aligned grid backdrop rasterizes its major lines at world coordinates that are
 * multiples of the grid scale (anchored at world origin 0, where the grid snap also rounds) and that the
 * lines scale with the camera zoom.  Drives the grid with a high-contrast palette (black background, red
 * major lines, black minor lines with a single subdivision) so a red column pinpoints a major line.  At
 * a 1:1 camera centered on the origin the line at world x = 0 lands at the viewport center and the lines
 * at x = +-scale land scale pixels either side, while a non-multiple column stays background.  Doubling
 * the zoom halves the on-screen gap between lines.  Self-skips in a display-less environment, like
 * [WorldAxisLinesTest].
 */
class GridBackdropRenderTest {
	private val viewportSize = 400
	private val gridScale = 100f
	private val paramA = ParameterId("A")

	// A small keyed quad tucked into a corner so its pixels never overlap the sampled center rows/columns.
	private fun model(originX: Float = 0f, originY: Float = 0f): PuppetModel {
		val positions = floatArrayOf(150f, 150f, 180f, 150f, 150f, 180f, 180f, 180f)
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
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = originX,
			worldOriginY = originY,
		)
	}

	// Black background, red major lines, black minor lines; one subdivision so only the major lines show.
	private fun highContrastGrid(renderer: PuppetRenderer) {
		renderer.setGrid(GridColors(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f), gridScale, subdivisions = 1)
	}

	@Test
	fun majorLinesLandOnWorldMultiplesAndScaleWithZoom() {
		val window = createHeadlessGl()
		assumeGlContext("[grid-backdrop]", window)
		try {
			val device = GlRenderDevice()
			val renderer = PuppetRenderer(model(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
			renderer.initGl()
			highContrastGrid(renderer)
			renderer.setPose(emptyMap())
			val framebuffer = createColorFbo(viewportSize, viewportSize)
			val target = device.wrapExistingFramebuffer(framebuffer, viewportSize, viewportSize)

			// 1:1 camera centered on the world origin: world x = 0 is the center column, x = +-scale sit scale
			// pixels either side.  Sample a row OFF the horizontal y = 0 line (else every column reads that
			// line's red) and clear of the corner probe quad.
			val center = viewportSize / 2
			val row = center + gridScale.toInt() / 2 // world y = ~half a cell: no horizontal line here
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val frame = readPixels(viewportSize, viewportSize)
			assertTrue(hasRedNear(frame, center, row), "a major line at world x = 0 lands at the viewport center")
			assertTrue(hasRedNear(frame, center - gridScale.toInt(), row), "a major line at world x = -scale")
			assertTrue(hasRedNear(frame, center + gridScale.toInt(), row), "a major line at world x = +scale")
			assertTrue(!hasRedNear(frame, center + gridScale.toInt() / 2, row, radius = 2), "no line at a non-multiple (x = scale/2)")

			// Double the zoom: the lines spread apart on screen.  The world-0 line stays centered, while the
			// column one scale out (a line at 1x) becomes a mid-cell gap at 2x - the on-screen spacing doubled.
			renderer.setCamera(ViewportCamera(0f, 0f, 2f))
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val zoomed = readPixels(viewportSize, viewportSize)
			assertTrue(hasRedNear(zoomed, center, row), "the world-0 line stays centered under zoom")
			assertTrue(!hasRedNear(zoomed, center + gridScale.toInt(), row, radius = 3), "the 1x line position is now a gap at 2x zoom")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun majorLineFollowsWorldOrigin() {
		val window = createHeadlessGl()
		assumeGlContext("[grid-backdrop]", window)
		try {
			// Offset the world origin by half a major cell (a mid-division case): the grid must anchor on the
			// origin, so the major line lands there, NOT at world 0.
			val halfCell = gridScale / 2f
			val device = GlRenderDevice()
			val renderer = PuppetRenderer(model(originX = halfCell, originY = 0f), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
			renderer.initGl()
			highContrastGrid(renderer)
			renderer.setPose(emptyMap())
			val framebuffer = createColorFbo(viewportSize, viewportSize)
			val target = device.wrapExistingFramebuffer(framebuffer, viewportSize, viewportSize)
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val frame = readPixels(viewportSize, viewportSize)
			val center = viewportSize / 2
			val row = center + gridScale.toInt() / 4 // off the horizontal origin line
			assertTrue(hasRedNear(frame, center + halfCell.toInt(), row), "the major line lands on the offset world origin")
			assertTrue(!hasRedNear(frame, center, row, radius = 3), "world x = 0 is a mid-cell gap when the origin is offset")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/** True when any pixel within [radius] columns of [col] on [row] reads clearly red (a major grid line). */
	private fun hasRedNear(frame: ByteBuffer, col: Int, row: Int, radius: Int = 2): Boolean {
		for (probe in col - radius..col + radius) {
			if (probe < 0 || probe >= viewportSize) {
				continue
			}
			val pixel = (row * viewportSize + probe) * 4
			val red = frame.get(pixel).toInt() and 0xFF
			val green = frame.get(pixel + 1).toInt() and 0xFF
			val blue = frame.get(pixel + 2).toInt() and 0xFF
			if (red > green + 60 && red > blue + 60) {
				return true
			}
		}
		return false
	}

	/** Reads the bound framebuffer's RGBA pixels into a fresh buffer (bottom-up rows). */
	private fun readPixels(width: Int, height: Int): ByteBuffer {
		val buffer = BufferUtils.createByteBuffer(width * height * 4)
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
		return buffer
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-grid-backdrop", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
