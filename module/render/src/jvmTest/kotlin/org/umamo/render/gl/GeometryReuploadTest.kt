package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
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
import org.umamo.runtime.model.RotationForm
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the GPU renderer re-uploads an edited base mesh so the textured art actually moves: an
 * Object/Edit-mode G/S/R commits new positions, and those positions must reach the VBO on the next
 * render or the art visibly lags behind the mesh. Renders a flat-color quad into an offscreen FBO, shifts
 * its base positions via [GlPuppetRenderer.updateModel], re-renders, and asserts the drawn art's pixel
 * centroid moved by the expected screen delta - once for a direct (undeformed) drawable and once for a
 * rotation-deformer-parented one, so a parented drawable's art fully tracks the edit rather than lagging
 * partway. Also asserts [GlPuppetRenderer.pickGeometry] follows the edit, so picking stays in sync with
 * what's drawn.
 *
 * Self-skips in a display-less environment (no GL context), like [GpuDeformValidationTest].
 */
class GeometryReuploadTest {
	private val viewportSize = 400
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("MotionProbe")

	// A 120x120 quad centered at the origin; at zoom 1 it covers pixels ~140..260, so a +60 shift stays fully
	// on-screen in the 400x400 viewport (no edge clipping to bias the centroid).
	private val quadPositions = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)
	private val quadUvs = FloatArray(8)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val shiftWorld = 60f

	private fun meshAxis() = listOf(KeyformAxis(paramA, floatArrayOf(0f)))

	private fun quadDrawable(parentDeformerId: DeformerId?): Drawable =
		Drawable(
			id = probeId,
			name = "MotionProbe",
			parentDeformerId = parentDeformerId,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(quadPositions.copyOf(), quadUvs, quadIndices),
			// A single zero-delta keyform so the drawable is "keyed" (an unkeyed drawable is skipped by the
			// renderer); the base mesh alone drives its shape.
			keyforms = KeyformGrid(meshAxis(), listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(quadPositions.size))))),
		)

	private fun model(deformers: List<Deformer>, drawable: Drawable): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = deformers,
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
		)

	/** The same model with the probe's base positions translated +[shiftWorld] in x (a fresh positions array). */
	private fun shiftedModel(source: PuppetModel): PuppetModel {
		val drawable = source.drawables.single()
		val shifted =
			FloatArray(quadPositions.size) { coordIndex ->
				drawable.mesh!!.positions[coordIndex] + if (coordIndex % 2 == 0) shiftWorld else 0f
			}
		return source.copy(drawables = listOf(drawable.copy(mesh = DrawableMesh(shifted, quadUvs, quadIndices))))
	}

	@Test
	fun directDrawableArtFollowsBaseEdit() {
		runMotionCase(model(emptyList(), quadDrawable(null)), "direct")
	}

	@Test
	fun rotationParentedDrawableArtFollowsBaseEdit() {
		// An identity rotation (angle 0, scale 1, origin 0,0) so the parent-transform path (parentType 1) runs
		// yet the expected screen delta stays the direct +shiftWorld - proving the re-uploaded base flows
		// through the deformer cascade and lands at the new place, rather than the parent absorbing the move.
		val deformerId = DeformerId("R")
		val rotation =
			Deformer.Rotation(
				id = deformerId,
				name = "R",
				parent = null,
				partId = null,
				baseAngle = 0f,
				keyforms = KeyformGrid(meshAxis(), listOf(KeyformCell(intArrayOf(0), RotationForm(0f, 0f, 0f, 1f, flipX = false, flipY = false)))),
			)
		runMotionCase(model(listOf(rotation), quadDrawable(deformerId)), "rotation")
	}

	/**
	 * Renders [source] and its base-shifted variant into an offscreen FBO and asserts the drawn art moved by
	 * the expected screen delta and that pick geometry followed. Self-skips (returns) with no GL context.
	 */
	private fun runMotionCase(source: PuppetModel, label: String) {
		val window = createHeadlessGl()
		assumeGlContext("[geometry-reupload]", window)
		try {
			val device = GlRenderDevice()
			val renderer = GlPuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
			renderer.initGl()
			val framebuffer = createColorFbo(viewportSize, viewportSize)
			val target = device.wrapExistingFramebuffer(framebuffer, viewportSize, viewportSize)
			// A fixed 1:1 camera centered on the origin, so one world unit == one screen pixel and the frame
			// never moves - only the art does. Held across both renders.
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))

			// Background only (the drawable hidden), so the art can be isolated by differencing against it.
			renderer.setShownDrawables(emptySet())
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val background = readPixels(viewportSize, viewportSize)

			// Frame A: the art at its original base positions.
			renderer.setShownDrawables(setOf(probeId))
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val frameA = readPixels(viewportSize, viewportSize)
			val (centroidAx, centroidAy, massA) = maskCentroid(frameA, background, viewportSize, viewportSize)
			val pickAx = pickCentroidX(renderer)

			// Edit: shift the base +shiftWorld in x and push the new model (the render loop's updateModel path).
			renderer.updateModel(shiftedModel(source))
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val frameB = readPixels(viewportSize, viewportSize)
			val (centroidBx, centroidBy, massB) = maskCentroid(frameB, background, viewportSize, viewportSize)
			val pickBx = pickCentroidX(renderer)

			println(
				"[geometry-reupload] $label: massA=$massA massB=$massB dx=${centroidBx - centroidAx} " +
					"(expected $shiftWorld) dy=${centroidBy - centroidAy} pickDx=${pickBx - pickAx}",
			)
			assertTrue(massA > 1000, "$label: frame A drew too little art (mass $massA) - the probe did not render")
			assertTrue(massB > 1000, "$label: frame B drew too little art (mass $massB)")
			// The whole quad translated rigidly, so its pixel centroid shifts by exactly the world delta at
			// zoom 1. A generous tolerance covers anti-aliased edge pixels near the flat-color threshold.
			assertEquals(shiftWorld, centroidBx - centroidAx, 4f, "$label: art did not move by the edited base delta")
			assertTrue(abs(centroidBy - centroidAy) < 4f, "$label: art drifted in y (${centroidBy - centroidAy})")
			// Pick geometry must follow the edit too (world x unchanged by the global Y-flip).
			assertEquals(shiftWorld, pickBx - pickAx, 0.5f, "$label: pick geometry did not follow the base edit")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/** The mean world-space x of the probe's current pick geometry (the CPU deform runs against currentModel). */
	private fun pickCentroidX(renderer: GlPuppetRenderer): Float {
		val positions = renderer.pickGeometry()?.worldPositions?.getValue(probeId) ?: return Float.NaN
		var sum = 0f
		var vertexCount = 0
		var coordIndex = 0
		while (coordIndex + 1 < positions.size) {
			sum += positions[coordIndex]
			vertexCount++
			coordIndex += 2
		}
		return sum / vertexCount
	}

	/**
	 * The pixel centroid (col, row) of the art, isolated as every pixel that differs from [background] by
	 * more than a flat threshold on any channel, plus the count of such pixels (the art's covered area).
	 */
	private fun maskCentroid(frame: ByteBuffer, background: ByteBuffer, width: Int, height: Int): Triple<Float, Float, Int> {
		val threshold = 20
		var sumX = 0.0
		var sumY = 0.0
		var count = 0
		for (row in 0 until height) {
			for (col in 0 until width) {
				val pixel = (row * width + col) * 4
				val deltaRed = abs((frame.get(pixel).toInt() and 0xFF) - (background.get(pixel).toInt() and 0xFF))
				val deltaGreen = abs((frame.get(pixel + 1).toInt() and 0xFF) - (background.get(pixel + 1).toInt() and 0xFF))
				val deltaBlue = abs((frame.get(pixel + 2).toInt() and 0xFF) - (background.get(pixel + 2).toInt() and 0xFF))
				if (maxOf(deltaRed, deltaGreen, deltaBlue) > threshold) {
					sumX += col
					sumY += row
					count++
				}
			}
		}
		if (count == 0) {
			return Triple(0f, 0f, 0)
		}
		return Triple((sumX / count).toFloat(), (sumY / count).toFloat(), count)
	}

	/** Reads the bound framebuffer's RGBA pixels into a fresh buffer (bottom-up rows; consistent A vs B). */
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-geometry-reupload", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
