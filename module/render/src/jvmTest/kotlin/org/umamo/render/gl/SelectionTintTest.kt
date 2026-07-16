package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
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
 * Proves the object-mode selection tint distinguishes the active (last-selected) drawable from a merely
 * selected one: both are pushed as selected, one is pushed as active, and the renderer must tint the
 * active one toward [PuppetRenderer.setActiveSelectionHighlightColor] (green here) while the other tints
 * toward [PuppetRenderer.setSelectionHighlightColor] (blue here).  Renders two separated flat-color
 * quads into an offscreen FBO and asserts the active quad reads greener and less blue than the selected
 * quad.  Because the two quads are identical apart from which one is active, any green/blue asymmetry is
 * caused solely by the active tint - so a control frame with no active drawable first asserts the two read
 * alike, isolating the effect of [PuppetRenderer.setActiveSelection].
 *
 * The relative comparison (active greener / less blue than selected) holds for any base drawable color:
 * the active mixes toward green and the selected toward blue at the same strength, so the active's green
 * channel is always the higher and its blue the lower of the pair.
 *
 * Self-skips in a display-less environment (no GL context), like [GeometryReuploadTest].
 */
class SelectionTintTest {
	private val viewportSize = 400
	private val paramA = ParameterId("A")
	private val leftId = DrawableId("Left")
	private val rightId = DrawableId("Right")

	// Two 80x80 quads: one centered at world x=-100 (screen col ~100), one at x=+100 (screen col ~300), both
	// on y=0 (screen row ~200).  At the 1:1 camera below they sit well apart with no pixel overlap.
	private val leftPositions = floatArrayOf(-140f, -40f, -60f, -40f, -140f, 40f, -60f, 40f)
	private val rightPositions = floatArrayOf(60f, -40f, 140f, -40f, 60f, 40f, 140f, 40f)
	private val quadUvs = FloatArray(8)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	private fun meshAxis() = listOf(KeyformAxis(paramA, floatArrayOf(0f)))

	private fun quad(id: DrawableId, positions: FloatArray): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions.copyOf(), quadUvs, quadIndices),
			// A single zero-delta keyform so the drawable is "keyed" (an unkeyed drawable is skipped by the
			// renderer); the base mesh alone drives its shape.
			keyforms = KeyformGrid(meshAxis(), listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size))))),
		)

	private fun twoQuadModel(): PuppetModel {
		val left = quad(leftId, leftPositions)
		val right = quad(rightId, rightPositions)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(left, right),
			rootChildren = listOf(OrgChild.Drawable(left.id), OrgChild.Drawable(right.id)),
			rootPartId = null,
		)
	}

	@Test
	fun activeDrawableTintsApartFromSelected() {
		val window = createHeadlessGl()
		assumeGlContext("[selection-tint]", window)
		try {
			val model = twoQuadModel()
			val device = GlRenderDevice()
			val renderer = PuppetRenderer(model, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
			renderer.initGl()
			val framebuffer = createColorFbo(viewportSize, viewportSize)
			val target = device.wrapExistingFramebuffer(framebuffer, viewportSize, viewportSize)
			// A fixed 1:1 camera centered on the origin, so one world unit == one screen pixel: the left quad
			// lands at col ~100, the right at ~300, both at row ~200.
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))
			renderer.setShownDrawables(setOf(leftId, rightId))
			renderer.setSelection(setOf(leftId, rightId))
			// Saturated pure colors so the active/selected channels are unambiguous in the read-back.
			renderer.setSelectionHighlightColor(0f, 0f, 1f)
			renderer.setActiveSelectionHighlightColor(0f, 1f, 0f)

			// Control: no active drawable, so both quads tint toward the same selection blue and read alike.
			renderer.setActiveSelection(null)
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val control = readPixels(viewportSize, viewportSize)
			val controlLeft = averageColor(control, leftScreenCol, quadScreenRow)
			val controlRight = averageColor(control, rightScreenCol, quadScreenRow)

			// The left quad is now the active one; it must tint toward green, the right stays selection blue.
			renderer.setActiveSelection(leftId)
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val active = readPixels(viewportSize, viewportSize)
			val activeLeft = averageColor(active, leftScreenCol, quadScreenRow)
			val selectedRight = averageColor(active, rightScreenCol, quadScreenRow)

			println(
				"[selection-tint] control L=$controlLeft R=$controlRight | active L=$activeLeft R=$selectedRight",
			)
			// Each quad is compared to ITSELF across the two frames (identical pixels, so the grid backdrop
			// bleeding through the quad's alpha cancels out) - the two quads sit at different screen columns
			// where the backdrop differs, so a cross-quad brightness comparison is not meaningful.

			// With no active drawable both quads tint toward the selection blue, so both read blue-dominant.
			assertTrue(
				controlLeft.third > controlLeft.second && controlRight.third > controlRight.second,
				"both selected quads should read blue-dominant with no active drawable (L=$controlLeft R=$controlRight)",
			)
			// The left quad becoming active flips it toward green: its green rises and its blue falls sharply,
			// and it ends green-dominant.  This within-quad transition is caused solely by setActiveSelection.
			assertTrue(
				activeLeft.second > controlLeft.second + 40 && activeLeft.third < controlLeft.third - 40,
				"the left quad should turn greener and less blue when it becomes active (control=$controlLeft active=$activeLeft)",
			)
			assertTrue(
				activeLeft.second > activeLeft.third,
				"the active quad should read green-dominant (active=$activeLeft)",
			)
			// The right quad stayed merely selected, so it is essentially unchanged between the two frames and
			// remains blue-dominant - the active tint touched only the active drawable.
			assertTrue(
				kotlin.math.abs(selectedRight.second - controlRight.second) < 8 &&
					kotlin.math.abs(selectedRight.third - controlRight.third) < 8 &&
					selectedRight.third > selectedRight.second,
				"the merely-selected quad should stay blue-dominant and unchanged (control=$controlRight active=$selectedRight)",
			)
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	private val leftScreenCol = 100
	private val rightScreenCol = 300
	private val quadScreenRow = 200

	/**
	 * The mean (red, green, blue) over an 11x11 window centered on the given screen column/row, each 0..255.
	 * Averaging smooths any anti-aliased edge and flat-color dithering at the sample point.
	 */
	private fun averageColor(frame: ByteBuffer, centerCol: Int, centerRow: Int): Triple<Int, Int, Int> {
		var sumRed = 0L
		var sumGreen = 0L
		var sumBlue = 0L
		var count = 0
		for (row in (centerRow - 5)..(centerRow + 5)) {
			for (col in (centerCol - 5)..(centerCol + 5)) {
				val pixel = (row * viewportSize + col) * 4
				sumRed += frame.get(pixel).toInt() and 0xFF
				sumGreen += frame.get(pixel + 1).toInt() and 0xFF
				sumBlue += frame.get(pixel + 2).toInt() and 0xFF
				count++
			}
		}
		return Triple((sumRed / count).toInt(), (sumGreen / count).toInt(), (sumBlue / count).toInt())
	}

	/** Reads the bound framebuffer's RGBA pixels into a fresh buffer (bottom-up rows; consistent across frames). */
	private fun readPixels(width: Int, height: Int): ByteBuffer {
		val buffer = BufferUtils.createByteBuffer(width * height * 4)
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
		return buffer
	}

	/** Creates and binds an RGBA8 offscreen framebuffer to render the puppet into for read-back. */
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-selection-tint", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
