package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
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
 * Proves [PuppetRenderer.renderAtlasPage] draws the atlas page UPRIGHT with the correct UV orientation
 * (the V-flip is easy to get backwards): a page whose four quadrants are distinctly colored must land
 * atlas-top-left at the DISPLAYED top-left, atlas-top-right at the displayed top-right,
 * and atlas-bottom-left at the displayed bottom-left.  A second case proves a null page paints the grid
 * only (no crash, no colored art).  Renders into an offscreen FBO at a page-fit 1:1 camera so the page
 * fills the frame.  Self-skips in a display-less environment, like [WorldAxisLinesTest].
 */
class AtlasPageRenderTest {
	private val viewportSize = 64
	private val paramA = ParameterId("A")

	// A minimal keyed quad so the renderer's CPU content-bounds eval (used only for the camera fit) is
	// finite; renderAtlasPage itself ignores the model entirely.
	private fun model(): PuppetModel {
		val positions = floatArrayOf(0f, 0f, 64f, 0f, 0f, 64f, 64f, 64f)
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
			worldOriginX = 0f,
			worldOriginY = 0f,
		)
	}

	/**
	 * A page (RGBA, top row first) with four distinctly-colored quadrants, so the rendered frame pins both
	 * orientation axes: atlas top-left RED, top-right GREEN, bottom-left BLUE, bottom-right WHITE.
	 */
	private fun quadrantPage(size: Int): DecodedImage {
		val rgba = ByteArray(size * size * 4)
		for (rowIndex in 0 until size) {
			for (columnIndex in 0 until size) {
				val top = rowIndex < size / 2 // row 0 is the atlas TOP row (top-row-first)
				val left = columnIndex < size / 2
				val red: Int
				val green: Int
				val blue: Int
				when {
					top && left -> {
						red = 255
						green = 0
						blue = 0
					}
					top && !left -> {
						red = 0
						green = 255
						blue = 0
					}
					!top && left -> {
						red = 0
						green = 0
						blue = 255
					}
					else -> {
						red = 255
						green = 255
						blue = 255
					}
				}
				val pixel = (rowIndex * size + columnIndex) * 4
				rgba[pixel] = red.toByte()
				rgba[pixel + 1] = green.toByte()
				rgba[pixel + 2] = blue.toByte()
				rgba[pixel + 3] = 255.toByte()
			}
		}
		return DecodedImage(rgba, size, size)
	}

	@Test
	fun atlasPageRendersUprightWithCorrectUvOrientation() {
		val window = createHeadlessGl()
		assumeGlContext("[atlas-page]", window)
		try {
			val page = quadrantPage(viewportSize)
			val device = GlRenderDevice()
			val renderer = PuppetRenderer(model(), PuppetTextures(listOf(page), emptyMap(), premultipliedAlpha = false), device)
			renderer.initGl()
			val framebuffer = createColorFbo(viewportSize, viewportSize)
			val target = device.wrapExistingFramebuffer(framebuffer, viewportSize, viewportSize)
			// Fit the page rectangle 1:1 so the whole page fills the frame.
			renderer.setCamera(ViewportCamera.fit(ContentBounds(0f, 0f, viewportSize.toFloat(), viewportSize.toFloat()), viewportSize, viewportSize))
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.renderAtlasPage(target, 0, viewportSize, viewportSize)
			val frame = readPixels(viewportSize, viewportSize) // bottom-up rows: displayed TOP = high row index

			val near = viewportSize / 8
			val far = viewportSize - near - 1
			// The V-flip acceptance check: atlas top-left (red) must land at the displayed top-left.
			assertChannel(frame, near, far, "red", "displayed top-left is the atlas top-left (red)")
			// U orientation: atlas top-right (green) at the displayed top-right.
			assertChannel(frame, far, far, "green", "displayed top-right is the atlas top-right (green)")
			// atlas bottom-left (blue) at the displayed bottom-left.
			assertChannel(frame, near, near, "blue", "displayed bottom-left is the atlas bottom-left (blue)")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun nullPageRendersGridOnly() {
		val window = createHeadlessGl()
		assumeGlContext("[atlas-page]", window)
		try {
			val device = GlRenderDevice()
			val renderer = PuppetRenderer(model(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
			renderer.initGl()
			val framebuffer = createColorFbo(viewportSize, viewportSize)
			val target = device.wrapExistingFramebuffer(framebuffer, viewportSize, viewportSize)
			renderer.setCamera(ViewportCamera.fit(ContentBounds(0f, 0f, viewportSize.toFloat(), viewportSize.toFloat()), viewportSize, viewportSize))
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.renderAtlasPage(target, null, viewportSize, viewportSize)
			val frame = readPixels(viewportSize, viewportSize)

			// No page: the neutral grey grid (grey background + grey lines) fills the frame, so a sampled pixel
			// reads near-neutral - not a saturated red / green / blue art channel.  Assert grayish (all
			// channels within a tight band).
			val pixel = (viewportSize / 2 * viewportSize + viewportSize / 2) * 4
			val red = frame.get(pixel).toInt() and 0xFF
			val green = frame.get(pixel + 1).toInt() and 0xFF
			val blue = frame.get(pixel + 2).toInt() and 0xFF
			val spread = maxOf(red, green, blue) - minOf(red, green, blue)
			println("[atlas-page] null-page center rgb=($red,$green,$blue) spread=$spread")
			assertTrue(spread < 40, "null page paints the neutral grid, not colored art (rgb spread $spread)")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/** Asserts the given channel dominates the pixel at (col, row), i.e. the expected quadrant color lands there. */
	private fun assertChannel(frame: ByteBuffer, column: Int, row: Int, channel: String, message: String) {
		val pixel = (row * viewportSize + column) * 4
		val red = frame.get(pixel).toInt() and 0xFF
		val green = frame.get(pixel + 1).toInt() and 0xFF
		val blue = frame.get(pixel + 2).toInt() and 0xFF
		val dominant =
			when (channel) {
				"red" -> red > green + 60 && red > blue + 60
				"green" -> green > red + 60 && green > blue + 60
				"blue" -> blue > red + 60 && blue > green + 60
				else -> false
			}
		assertTrue(dominant, "$message - got rgb=($red,$green,$blue)")
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-atlas-page", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
