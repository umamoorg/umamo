package org.umamo.render.gl

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryUtil
import org.umamo.format.moc3.Moc3
import org.umamo.render.PuppetTextures
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.ingest.Moc3Import
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The whole-model correctness gate for the composite acceleration: renders a real corpus model
 * (Model A, 24 isolated groups + extended-blend drawables) through the OPTIMIZED path and again
 * with the acceleration disabled (plain full-viewport composites), and asserts the two images are
 * pixel-identical within one composite's rounding.  The optimizations are only ever meant to save
 * work, never change a pixel - so an undersized bounds scissor (which once clipped the head-top and
 * arm tips) shows up here as a large channel diff instead of a silent visual regression.  Corpus- +
 * GL-gated: skips without Model A or a context.
 */
class CompositeAccelerationParityTest {
	private val viewportWidth = 768
	private val viewportHeight = 1024

	// One composite boundary re-quantizes to 8 bits; the flatten fast path removes an intermediate
	// quantization, so identity groups can differ by a couple of LSB.  A clip would be ~255.
	private val channelTolerance = 3

	@Test
	fun acceleratedRenderMatchesTheFullViewportPath() {
		val mocFile =
			System.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { it.isDirectory }
				?.walkTopDown()
				?.firstOrNull { it.name == "modelA.moc3" }
		if (mocFile == null) {
			println("modelA.moc3 not present; skipping composite acceleration parity")
			return
		}
		val window = createHeadlessGl()
		assumeGlContext("[composite-accel-parity]", window)
		try {
			val puppet = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(Moc3.decode(mocFile.readBytes()), null))
			// The plain full-viewport composite path is the reference (the pre-optimization behavior the
			// oracle + composite suites already pin).  Each optimization is asserted against it alone and
			// combined, so a failure names which one diverged.
			val reference = renderImage(puppet, flatten = false, scissor = false)
			assertMatches(reference, renderImage(puppet, flatten = true, scissor = false), "identity flatten")
			assertMatches(reference, renderImage(puppet, flatten = false, scissor = true), "bounds scissor")
			assertMatches(reference, renderImage(puppet, flatten = true, scissor = true), "flatten + scissor")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	private fun assertMatches(reference: IntArray, candidate: IntArray, label: String) {
		var maxDiff = 0
		var differingSamples = 0
		var minX = Int.MAX_VALUE
		var minY = Int.MAX_VALUE
		var maxX = -1
		var maxY = -1
		val samples = ArrayList<String>()
		for (index in reference.indices) {
			val diff = abs(reference[index] - candidate[index])
			if (diff > maxDiff) {
				maxDiff = diff
			}
			// Report every over-tolerance channel INCLUDING alpha (channel 3): the pass/fail check below
			// is over all four channels, so excluding alpha here would let an alpha-only divergence fail
			// the test while printing an empty, misleading diagnostic.
			if (diff > channelTolerance) {
				differingSamples++
				val pixel = index / 4
				val px = pixel % viewportWidth
				val py = pixel / viewportWidth
				minX = minOf(minX, px)
				minY = minOf(minY, py)
				maxX = maxOf(maxX, px)
				maxY = maxOf(maxY, py)
				if (samples.size < 12) {
					samples.add("($px,$py) ch${index % 4} ref=${reference[index]} cand=${candidate[index]}")
				}
			}
		}
		if (differingSamples > 0) {
			println("[$label] diff bbox x[$minX,$maxX] y[$minY,$maxY], $differingSamples samples, maxDiff $maxDiff")
			samples.forEach { println("[$label]   $it") }
		}
		assertTrue(
			maxDiff <= channelTolerance,
			"$label must match the full-viewport composite path (max channel diff $maxDiff, $differingSamples channel " +
				"samples over tolerance) - a large diff means the optimization changed a pixel (e.g. a scissor clipped content)",
		)
	}

	/** Renders [source] at the default fit camera with the two optimizations toggled; returns RGBA bytes. */
	private fun renderImage(source: PuppetModel, flatten: Boolean, scissor: Boolean): IntArray {
		val device = GlRenderDevice()
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.compositeFlattenEnabled = flatten
		renderer.compositeBoundsScissorEnabled = scissor
		renderer.initGl()
		renderer.setPose(emptyMap()) // no camera set: render() frames the content by its fit
		val target = device.createRenderTarget(RenderTargetSpec(viewportWidth, viewportHeight, TextureFormat.Rgba8, sampled = true))
		renderer.render(target, viewportWidth, viewportHeight)
		val image = device.readPixels(target)
		return IntArray(image.rgba.size) { image.rgba[it].toInt() and 0xFF }
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-composite-accel-parity", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
