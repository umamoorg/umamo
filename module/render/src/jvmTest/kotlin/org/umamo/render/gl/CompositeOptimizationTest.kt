package org.umamo.render.gl

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryUtil
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.AlphaBlendMode
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
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The composite perf optimizations, verified for pixel correctness: the bounds SCISSOR that confines
 * a layer composite to the isolated subtree's screen rectangle (a vertical-flip or over-clip bug
 * would misplace or clip the effect), and the identity-composite FLATTEN fast path (an identity
 * Normal/Over isolated part draws its subtree inline; premultiplied Over is associative, so it must
 * match the same drawable drawn with no part at all).  Flat-color drawables over a black backdrop, so
 * every expectation is analytic.  Skips without a GL context.
 */
class CompositeOptimizationTest {
	private val viewportSize = 64
	private val paramA = ParameterId("A")
	private val blackGrid = GridColors(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
	private val frontIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	// A quad in one band of the viewport (mesh y in [8, 30]).  The vertex shader negates Y, so a
	// positive mesh Y renders in the LOWER band of the top-first read-back image (high row numbers) -
	// which is exactly what makes this quad a good scissor-placement probe: the composite must land
	// where the geometry actually rasterizes, Y-negation and all.
	private val bandQuad = floatArrayOf(-16f, 8f, 16f, 8f, -16f, 30f, 16f, 30f)

	// Rows (top-first) inside and outside the rendered quad band.  Mesh y [8, 30] -> rendered-world y
	// [-30, -8] -> top-first rows ~[40, 62]; row 50 is inside, row 12 is well above (outside).
	private val insideRow = 50
	private val outsideRow = 12

	private fun restGrid(size: Int): KeyformGrid<MeshForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(size)))),
		)

	private fun quad(id: String, positions: FloatArray = bandQuad): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			alphaBlendMode = AlphaBlendMode.Over,
			culling = false,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), frontIndices),
			keyforms = restGrid(positions.size),
			isVisible = true,
		)

	private fun model(drawables: List<Drawable>, parts: List<Part>, rootChildren: List<OrgChild>): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = parts,
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = rootChildren,
			rootPartId = null,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		).withDerivedRenderRoot()

	private fun isolated(partId: String, childId: String, composite: PartComposite): Part =
		Part(
			id = PartId(partId),
			name = partId,
			children = listOf(OrgChild.Drawable(DrawableId(childId))),
			groupMode = PartGroupMode.Isolated(composite),
		)

	private fun renderImage(source: PuppetModel): IntArray {
		val device = GlRenderDevice()
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setGrid(blackGrid, 100f, 10)
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		renderer.render(target, viewportSize, viewportSize)
		val image = device.readPixels(target)
		return IntArray(image.rgba.size) { image.rgba[it].toInt() and 0xFF }
	}

	private fun pixel(image: IntArray, x: Int, y: Int): IntArray {
		val offset = (y * viewportSize + x) * 4
		return IntArray(4) { image[offset + it] }
	}

	@Test
	fun scissoredCompositeLandsInTheQuadBandOnly() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-opt]", window)
		try {
			// An isolated part at opacity 0.5 (so the real composite path runs, not the flatten path)
			// wrapping the band quad.  Over black, the composite writes half the quad's premul color in
			// the quad's band and nothing elsewhere - and the scissor confines the work to the quad's
			// bounds.  A vertical scissor-flip bug would move the effect to the opposite band.
			val source =
				model(
					drawables = listOf(quad("top")),
					parts = listOf(isolated("fx", "top", PartComposite(opacity = 0.5f))),
					rootChildren = listOf(OrgChild.Part(PartId("fx"))),
				)
			val image = renderImage(source)
			val inside = pixel(image, viewportSize / 2, insideRow)
			val outside = pixel(image, viewportSize / 2, outsideRow)
			assertTrue(inside.take(3).max() > 8, "the composite is drawn in the quad's band, got ${inside.toList()}")
			assertTrue(outside.take(3).max() <= 2, "outside the quad stays the black backdrop, got ${outside.toList()}")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun identityCompositeFlattensToPlainDrawing() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-opt]", window)
		try {
			// The SAME quad, drawn two ways: plainly (no part), and wrapped in an identity Normal/Over
			// isolated part (opacity 1, identity colors, unmasked).  The identity part flattens to inline
			// drawing - Over is associative - so every pixel must agree within one 8-bit rounding step.
			val plain =
				model(
					drawables = listOf(quad("q")),
					parts = emptyList(),
					rootChildren = listOf(OrgChild.Drawable(DrawableId("q"))),
				)
			val wrapped =
				model(
					drawables = listOf(quad("q")),
					parts = listOf(isolated("fx", "q", PartComposite())),
					rootChildren = listOf(OrgChild.Part(PartId("fx"))),
				)
			val plainImage = renderImage(plain)
			val wrappedImage = renderImage(wrapped)
			var maxDiff = 0
			for (index in plainImage.indices) {
				maxDiff = maxOf(maxDiff, abs(plainImage[index] - wrappedImage[index]))
			}
			assertTrue(maxDiff <= 1, "flattened identity composite matches plain drawing (max channel diff $maxDiff)")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun fadedCompositeIsSkippedLeavingTheBackdrop() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-opt]", window)
		try {
			// A pose-blended opacity of 0 makes the layer empty, so the composite is skipped outright;
			// over the black backdrop the result is pure black everywhere the quad would have been.
			val source =
				model(
					drawables = listOf(quad("top")),
					parts = listOf(isolated("fx", "top", PartComposite(opacity = 0f))),
					rootChildren = listOf(OrgChild.Part(PartId("fx"))),
				)
			val image = renderImage(source)
			val inside = pixel(image, viewportSize / 2, insideRow)
			assertTrue(inside.take(3).max() <= 2, "a zero-opacity composite leaves the backdrop, got ${inside.toList()}")
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-composite-opt", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
