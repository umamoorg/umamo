package org.umamo.render.gl

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryUtil
import org.umamo.render.device.CompositeUniforms
import org.umamo.render.device.DrawTextures
import org.umamo.render.device.LoadAction
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.RenderPassSpec
import org.umamo.render.device.RenderPipelineSpec
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.WorldToNdc
import org.umamo.render.puppet.compositeReference
import org.umamo.render.puppet.packedAlphaModeOf
import org.umamo.render.puppet.packedColorModeOf
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

// 8-bit target quantization + the shader's float math vs the reference's - a couple of ulps at
// 1/255 granularity.  The Rgba8 inputs are pre-quantized below, so this only absorbs arithmetic
// divergence, not input rounding.
private const val CHANNEL_TOLERANCE = 3f / 255f

/**
 * The composite shader vs the pure reference, across every color x alpha mode combination: fills a
 * layer and a destination target with known premultiplied colors, runs the Composite
 * pipeline, and compares the read-back pixel against `compositeReference` channel by channel.
 * This is what makes BlendMath.kt the spec rather than documentation - the GLSL cannot drift from
 * it without this failing.  Skips without a GL context.
 */
class CompositeSweepTest {
	private val size = 8

	private fun quantize(premul: FloatArray): FloatArray =
		FloatArray(4) { channelIndex -> (premul[channelIndex] * 255f).roundToInt() / 255f }

	private fun target(device: GlRenderDevice): RenderTarget =
		device.createRenderTarget(RenderTargetSpec(size, size, TextureFormat.Rgba8, sampled = true))

	private fun fill(device: GlRenderDevice, target: RenderTarget, premul: FloatArray) {
		val frame = device.beginFrame()
		val pass =
			frame.beginRenderPass(
				RenderPassSpec(
					colorTarget = target,
					loadAction = LoadAction.Clear,
					viewportWidth = size,
					viewportHeight = size,
					clearRed = premul[0],
					clearGreen = premul[1],
					clearBlue = premul[2],
					clearAlpha = premul[3],
				),
			)
		pass.end()
		frame.endFrame()
	}

	private fun centerPixel(device: GlRenderDevice, target: RenderTarget): FloatArray {
		val image = device.readPixels(target)
		val pixelOffset = ((size / 2) * size + size / 2) * 4
		return FloatArray(4) { channelIndex -> (image.rgba[pixelOffset + channelIndex].toInt() and 0xFF) / 255f }
	}

	private fun composite(
		device: GlRenderDevice,
		out: RenderTarget,
		layer: RenderTarget,
		dest: RenderTarget,
		uniforms: CompositeUniforms,
	): FloatArray {
		val pipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.Composite, PipelineBlend.Opaque))
		val textures = DrawTextures()
		val frame = device.beginFrame()
		val pass = frame.beginRenderPass(RenderPassSpec(out, LoadAction.Clear, size, size))
		pass.setPipeline(pipeline)
		pass.setCamera(WorldToNdc(1f, 1f, 0f, 0f), size, size)
		textures.compositeLayer = layer.sampledTexture
		textures.destinationSnapshot = dest.sampledTexture
		pass.drawComposite(uniforms, textures)
		pass.end()
		frame.endFrame()
		return centerPixel(device, out)
	}

	@Test
	fun compositeShaderMatchesTheReferenceAcrossAllModes() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-sweep]", window)
		try {
			val device = GlRenderDevice()
			val layer = target(device)
			val dest = target(device)
			val out = target(device)
			// Valid premultiplied pixels (rgb <= a), partially transparent on both sides so every
			// alpha mode's factors bite.
			val sourcePremul = quantize(floatArrayOf(0.3f, 0.2f, 0.1f, 0.5f))
			val destinationPremul = quantize(floatArrayOf(0.4f, 0.5f, 0.6f, 0.75f))
			fill(device, layer, sourcePremul)
			fill(device, dest, destinationPremul)
			val uniforms = CompositeUniforms()
			val failures = ArrayList<String>()
			var worstDelta = 0f
			for (blendMode in BlendMode.entries) {
				for (alphaBlendMode in AlphaBlendMode.entries) {
					uniforms.colorMode = packedColorModeOf(blendMode)
					uniforms.alphaMode = packedAlphaModeOf(alphaBlendMode)
					val pixel = composite(device, out, layer, dest, uniforms)
					val expected = compositeReference(sourcePremul, destinationPremul, blendMode, alphaBlendMode)
					for (channelIndex in 0 until 4) {
						val delta = abs(pixel[channelIndex] - expected[channelIndex])
						if (delta > worstDelta) {
							worstDelta = delta
						}
						if (delta > CHANNEL_TOLERANCE) {
							failures.add(
								"$blendMode/$alphaBlendMode ch$channelIndex: shader=${pixel[channelIndex]} reference=${expected[channelIndex]}",
							)
						}
					}
				}
			}
			println("[composite-sweep] ${BlendMode.entries.size * AlphaBlendMode.entries.size} combos, worst channel delta=$worstDelta")
			assertTrue(failures.isEmpty(), "shader vs reference mismatches (${failures.size}):\n" + failures.joinToString("\n"))
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun compositeChannelsApplyToTheLayerBeforeBlending() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-sweep]", window)
		try {
			val device = GlRenderDevice()
			val layer = target(device)
			val dest = target(device)
			val out = target(device)
			val sourcePremul = quantize(floatArrayOf(0.3f, 0.2f, 0.1f, 0.5f))
			val destinationPremul = quantize(floatArrayOf(0.4f, 0.5f, 0.6f, 0.75f))
			fill(device, layer, sourcePremul)
			fill(device, dest, destinationPremul)
			val uniforms = CompositeUniforms()
			uniforms.opacity = 0.5f
			uniforms.multiplyRed = 0f
			uniforms.multiplyGreen = 1f
			uniforms.multiplyBlue = 1f
			uniforms.screenRed = 0.2f
			uniforms.screenGreen = 0f
			uniforms.screenBlue = 0f
			val pixel = composite(device, out, layer, dest, uniforms)
			// The reference application order: opacity scales the premultiplied layer, then multiply
			// then screen apply to the straight color - mirroring the shader line for line.
			val scaled = FloatArray(4) { channelIndex -> sourcePremul[channelIndex] * 0.5f }
			val alpha = scaled[3]
			val straight = floatArrayOf(scaled[0] / alpha, scaled[1] / alpha, scaled[2] / alpha)
			straight[0] *= 0f
			straight[1] *= 1f
			straight[2] *= 1f
			straight[0] = straight[0] + 0.2f - straight[0] * 0.2f
			val layerAfter = floatArrayOf(straight[0] * alpha, straight[1] * alpha, straight[2] * alpha, alpha)
			val expected = compositeReference(layerAfter, destinationPremul, BlendMode.Normal, AlphaBlendMode.Over)
			for (channelIndex in 0 until 4) {
				assertTrue(
					abs(pixel[channelIndex] - expected[channelIndex]) <= CHANNEL_TOLERANCE,
					"channel $channelIndex: shader=${pixel[channelIndex]} reference=${expected[channelIndex]}",
				)
			}
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-composite-sweep", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
