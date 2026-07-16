package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.MemoryUtil
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the GPU renderer re-uploads an edited UV array in place so the textured art actually
 * resamples - the UV editor's live-preview and commit path.  Renders a quad sampling the LEFT (red)
 * half of a two-color atlas into an offscreen FBO, retargets its UVs at the RIGHT (green) half via
 * [GlPuppetRenderer.updateModel] with the positions, indices, and keyforms untouched by reference
 * (exactly the shape a withMeshUvs edit produces, so the uvs-only tier runs - not the structural
 * re-upload), re-renders, and asserts the drawn art flipped color without moving.
 *
 * Self-skips in a display-less environment (no GL context), like [GeometryReuploadTest].
 */
class UvReuploadTest {
	private val viewportSize = 200
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("UvProbe")

	// A 120x120 quad centered at the origin; at zoom 1 it covers pixels ~40..160 in the 200x200 viewport.
	private val quadPositions = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	// The atlas halves, sampled with a safe margin so the NEAREST lookup never straddles the color seam.
	private val leftHalfUvs = floatArrayOf(0.05f, 0.95f, 0.45f, 0.95f, 0.05f, 0.05f, 0.45f, 0.05f)
	private val rightHalfUvs = floatArrayOf(0.55f, 0.95f, 0.95f, 0.95f, 0.55f, 0.05f, 0.95f, 0.05f)

	/** An 8x8 RGBA atlas: columns 0..3 solid red, columns 4..7 solid green, fully opaque. */
	private fun twoColorAtlas(): DecodedImage {
		val size = 8
		val rgba = ByteArray(size * size * 4)
		for (row in 0 until size) {
			for (col in 0 until size) {
				val pixel = (row * size + col) * 4
				rgba[pixel] = if (col < size / 2) 0xFF.toByte() else 0x00
				rgba[pixel + 1] = if (col < size / 2) 0x00 else 0xFF.toByte()
				rgba[pixel + 2] = 0x00
				rgba[pixel + 3] = 0xFF.toByte()
			}
		}
		return DecodedImage(rgba, size, size)
	}

	private fun probeModel(): PuppetModel {
		val drawable =
			Drawable(
				id = probeId,
				name = "UvProbe",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(quadPositions.copyOf(), leftHalfUvs.copyOf(), quadIndices),
				// A single zero-delta keyform so the drawable is "keyed" (an unkeyed drawable is skipped
				// by the renderer); the base mesh alone drives its shape.
				keyforms =
					KeyformGrid(
						listOf(KeyformAxis(paramA, floatArrayOf(0f))),
						listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(quadPositions.size)))),
					),
			)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
		)
	}

	/**
	 * The same model with the probe's UVs retargeted at the green half - positions, indices, and
	 * keyforms shared by reference, the exact shape withMeshUvs produces, so only the uvs-only tier
	 * may run.
	 */
	private fun uvShiftedModel(source: PuppetModel): PuppetModel {
		val drawable = source.drawables.single()
		val mesh = drawable.mesh!!
		return source.copy(drawables = listOf(drawable.copy(mesh = DrawableMesh(mesh.positions, rightHalfUvs.copyOf(), mesh.indices))))
	}

	@Test
	fun artResamplesAfterUvEdit() {
		val window = createHeadlessGl()
		assumeGlContext("[uv-reupload]", window)
		try {
			val source = probeModel()
			val device = GlRenderDevice()
			val renderer = GlPuppetRenderer(source, PuppetTextures(listOf(twoColorAtlas()), mapOf(probeId.raw to 0), premultipliedAlpha = false), device)
			renderer.initGl()
			val framebuffer = createColorFbo(viewportSize, viewportSize)
			val target = device.wrapExistingFramebuffer(framebuffer, viewportSize, viewportSize)
			// A fixed 1:1 camera centered on the origin, so the quad never moves - only its texels do.
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))

			// Background only (the drawable hidden), so the art can be isolated by differencing against it.
			renderer.setShownDrawables(emptySet())
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val background = readPixels(viewportSize, viewportSize)

			// Frame A: the quad sampling the red half.
			renderer.setShownDrawables(setOf(probeId))
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val frameA = readPixels(viewportSize, viewportSize)
			val statsA = artColorStats(frameA, background, viewportSize, viewportSize)

			// Edit: retarget the UVs at the green half and push the new model (the render loop's
			// updateModel path, fed by the UV editor's preview and commit).
			renderer.updateModel(uvShiftedModel(source))
			renderer.setPose(emptyMap())
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
			renderer.render(target, viewportSize, viewportSize)
			val frameB = readPixels(viewportSize, viewportSize)
			val statsB = artColorStats(frameB, background, viewportSize, viewportSize)

			println(
				"[uv-reupload] massA=${statsA.mass} redA=${statsA.meanRed} greenA=${statsA.meanGreen} | " +
					"massB=${statsB.mass} redB=${statsB.meanRed} greenB=${statsB.meanGreen} | " +
					"centroidDx=${statsB.centroidX - statsA.centroidX} centroidDy=${statsB.centroidY - statsA.centroidY}",
			)
			assertTrue(statsA.mass > 1000, "frame A drew too little art (mass ${statsA.mass}) - the probe did not render")
			assertTrue(statsB.mass > 1000, "frame B drew too little art (mass ${statsB.mass})")
			assertTrue(statsA.meanRed > 200f && statsA.meanGreen < 60f, "frame A should sample the red half (r=${statsA.meanRed} g=${statsA.meanGreen})")
			assertTrue(statsB.meanGreen > 200f && statsB.meanRed < 60f, "frame B should sample the green half after the UV edit (r=${statsB.meanRed} g=${statsB.meanGreen})")
			// A pure UV edit moves texels, never geometry: the drawn quad must stay put.
			assertTrue(abs(statsB.centroidX - statsA.centroidX) < 2f, "the art moved in x under a UV-only edit")
			assertTrue(abs(statsB.centroidY - statsA.centroidY) < 2f, "the art moved in y under a UV-only edit")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/** The mean color and pixel centroid of the art (every pixel differing from [background]). */
	private class ArtColorStats(val mass: Int, val meanRed: Float, val meanGreen: Float, val centroidX: Float, val centroidY: Float)

	/**
	 * Isolates the art as every pixel that differs from [background] by more than a flat threshold on
	 * any channel, and returns its mean red / green plus its pixel centroid.
	 *
	 * @param ByteBuffer frame The rendered frame's RGBA pixels.
	 * @param ByteBuffer background The art-hidden frame's RGBA pixels.
	 * @param Int width The frame width in pixels.
	 * @param Int height The frame height in pixels.
	 * @return ArtColorStats The art's mass, mean red / green, and centroid.
	 */
	private fun artColorStats(frame: ByteBuffer, background: ByteBuffer, width: Int, height: Int): ArtColorStats {
		val threshold = 20
		var sumRed = 0.0
		var sumGreen = 0.0
		var sumX = 0.0
		var sumY = 0.0
		var count = 0
		for (row in 0 until height) {
			for (col in 0 until width) {
				val pixel = (row * width + col) * 4
				val red = frame.get(pixel).toInt() and 0xFF
				val green = frame.get(pixel + 1).toInt() and 0xFF
				val blue = frame.get(pixel + 2).toInt() and 0xFF
				val deltaRed = abs(red - (background.get(pixel).toInt() and 0xFF))
				val deltaGreen = abs(green - (background.get(pixel + 1).toInt() and 0xFF))
				val deltaBlue = abs(blue - (background.get(pixel + 2).toInt() and 0xFF))
				if (maxOf(deltaRed, deltaGreen, deltaBlue) > threshold) {
					sumRed += red
					sumGreen += green
					sumX += col
					sumY += row
					count++
				}
			}
		}
		if (count == 0) {
			return ArtColorStats(0, 0f, 0f, 0f, 0f)
		}
		return ArtColorStats(count, (sumRed / count).toFloat(), (sumGreen / count).toFloat(), (sumX / count).toFloat(), (sumY / count).toFloat())
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-uv-reupload", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
