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
import org.umamo.runtime.model.ColorRgb
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

private const val CHANNEL_TOLERANCE = 3

/**
 * End-to-end layer compositing through [PuppetRenderer]: the always-shader legacy parity (an
 * isolated part with a legacy blend renders identically to the same drawable blended
 * fixed-function), the composite channels (opacity, multiply color), the composite clip mask with
 * invert, two-level nesting, and back-face culling.  Flat-color drawables (no atlas) keep every
 * expectation analytic; the backdrop is pure black so "over black" collapses to the layer's own
 * premultiplied color.  Skips without a GL context.
 */
class CompositeRendererTest {
	private val viewportSize = 64
	private val paramA = ParameterId("A")
	private val blackGrid = GridColors(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

	// A full-viewport quad in the fixed 1:1 camera (world x,y in [-32, 32]).  With the standard
	// indices (0,1,2)(1,3,2) the triangles land CLOCKWISE in the renderer's Y-negated world - the
	// corpus front-face convention - so they survive back-face culling.
	private val fullQuad = floatArrayOf(-32f, -32f, 32f, -32f, -32f, 32f, 32f, 32f)
	private val frontIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val backIndices = intArrayOf(0, 2, 1, 1, 2, 3)

	// The left half of the viewport, as a clip-mask source.
	private val leftHalfQuad = floatArrayOf(-32f, -32f, 0f, -32f, -32f, 32f, 0f, 32f)

	private fun restGrid(positions: FloatArray, opacity: Float = 1f): KeyformGrid<MeshForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size), opacity = opacity))),
		)

	private fun drawable(
		id: String,
		positions: FloatArray = fullQuad,
		indices: IntArray = frontIndices,
		blendMode: BlendMode = BlendMode.Normal,
		alphaBlendMode: AlphaBlendMode = AlphaBlendMode.Over,
		culling: Boolean = false,
		opacity: Float = 1f,
		isVisible: Boolean = true,
	): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = blendMode,
			alphaBlendMode = alphaBlendMode,
			culling = culling,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), indices),
			keyforms = restGrid(positions, opacity),
			isVisible = isVisible,
		)

	private fun model(drawables: List<Drawable>, parts: List<Part> = emptyList(), rootChildren: List<OrgChild>): PuppetModel =
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

	/** Renders [source] and returns the RGBA of the pixel at ([x], [y]) in top-first image space. */
	private fun renderPixel(source: PuppetModel, x: Int, y: Int): IntArray {
		val device = GlRenderDevice()
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setGrid(blackGrid, 100f, 10)
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		renderer.render(target, viewportSize, viewportSize)
		val image = device.readPixels(target)
		val pixelOffset = (y * viewportSize + x) * 4
		return IntArray(4) { channelIndex -> image.rgba[pixelOffset + channelIndex].toInt() and 0xFF }
	}

	private fun renderCenterPixel(source: PuppetModel): IntArray = renderPixel(source, viewportSize / 2, viewportSize / 2)

	private fun isolatedPart(
		partId: String,
		childId: String,
		composite: PartComposite,
		children: List<OrgChild> = listOf(OrgChild.Drawable(DrawableId(childId))),
	): Part =
		Part(
			id = PartId(partId),
			name = partId,
			children = children,
			groupMode = PartGroupMode.Isolated,
			composite = composite,
		)

	private fun assertPixelClose(expected: IntArray, actual: IntArray, label: String) {
		for (channelIndex in 0 until 3) {
			assertTrue(
				abs(expected[channelIndex] - actual[channelIndex]) <= CHANNEL_TOLERANCE,
				"$label ch$channelIndex: expected ${expected.toList()}, got ${actual.toList()}",
			)
		}
	}

	@Test
	fun legacyCompositeMatchesFixedFunctionBlending() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-renderer]", window)
		try {
			// The same two quads: base Normal, top legacy Multiply at 0.6 opacity.  Fixed-function
			// path (drawable-level blend) vs the always-shader composite path (the top quad wrapped
			// in a legacy Multiply/Over isolated part) must land on the same pixels.
			val base = drawable("base")
			val fixedFunction =
				model(
					drawables = listOf(base, drawable("top", blendMode = BlendMode.MultiplyPremultiplied, opacity = 0.6f)),
					rootChildren = listOf(OrgChild.Drawable(DrawableId("base")), OrgChild.Drawable(DrawableId("top"))),
				)
			val composited =
				model(
					drawables = listOf(base, drawable("top", opacity = 0.6f)),
					parts = listOf(isolatedPart("fx", "top", PartComposite(blendMode = BlendMode.MultiplyPremultiplied))),
					rootChildren = listOf(OrgChild.Drawable(DrawableId("base")), OrgChild.Part(PartId("fx"))),
				)
			assertPixelClose(renderCenterPixel(fixedFunction), renderCenterPixel(composited), "legacy multiply parity")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun premultipliedModesIgnoreTheAlphaBlendSetting() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-renderer]", window)
		try {
			// The "(Before 5.3)" Add/Multiply composite in premultiplied format and ignore the Alpha
			// blend setting, so an isolated composite using one renders identically whether its alpha
			// mode is Over or Disjoint.  A modern mode would diverge (it honors the alpha mode).
			for (mode in listOf(BlendMode.AdditivePremultiplied, BlendMode.MultiplyPremultiplied)) {
				fun composited(alpha: AlphaBlendMode): PuppetModel =
					model(
						drawables = listOf(drawable("base"), drawable("top", opacity = 0.6f)),
						parts = listOf(isolatedPart("fx", "top", PartComposite(blendMode = mode, alphaBlendMode = alpha))),
						rootChildren = listOf(OrgChild.Drawable(DrawableId("base")), OrgChild.Part(PartId("fx"))),
					)
				assertPixelClose(
					renderCenterPixel(composited(AlphaBlendMode.Over)),
					renderCenterPixel(composited(AlphaBlendMode.Disjoint)),
					"$mode ignores alpha blend (Over == Disjoint)",
				)
			}
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun compositeOpacityAndMultiplyColorApply() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-renderer]", window)
		try {
			// Over the black backdrop, source-over collapses to the layer's premultiplied color: a
			// composite at opacity 0.5 must read exactly half the plain render, and a (1,0,0)
			// multiply color must keep red while zeroing green/blue.
			val plain =
				model(
					drawables = listOf(drawable("top")),
					parts = listOf(isolatedPart("fx", "top", PartComposite())),
					rootChildren = listOf(OrgChild.Part(PartId("fx"))),
				)
			val plainPixel = renderCenterPixel(plain)
			val halved =
				model(
					drawables = listOf(drawable("top")),
					parts = listOf(isolatedPart("fx", "top", PartComposite(opacity = 0.5f))),
					rootChildren = listOf(OrgChild.Part(PartId("fx"))),
				)
			val halvedPixel = renderCenterPixel(halved)
			assertPixelClose(
				IntArray(4) { channelIndex -> if (channelIndex < 3) plainPixel[channelIndex] / 2 else halvedPixel[channelIndex] },
				halvedPixel,
				"opacity 0.5 halves the composite over black",
			)
			val redFiltered =
				model(
					drawables = listOf(drawable("top")),
					parts = listOf(isolatedPart("fx", "top", PartComposite(multiplyColor = ColorRgb(1f, 0f, 0f)))),
					rootChildren = listOf(OrgChild.Part(PartId("fx"))),
				)
			val redPixel = renderCenterPixel(redFiltered)
			assertPixelClose(
				intArrayOf(plainPixel[0], 0, 0, redPixel[3]),
				redPixel,
				"multiply (1,0,0) keeps red and zeroes green/blue",
			)
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun compositeClipMaskGatesTheCompositeAndInverts() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-renderer]", window)
		try {
			// The composite is clipped by a hidden left-half mask drawable.  Coverage is the mask's
			// ALPHA - the fallback color's 0.85, not 1.0 - so the clipped side shows 0.85x the plain
			// composite and the inverted inside keeps the 0.15 remainder (fractional coverage is the
			// Cubism semantic; a soft-edged mask clips softly).
			fun clipped(invert: Boolean): PuppetModel {
				val maskSource = drawable("clip", positions = leftHalfQuad, isVisible = false)
				val composite = PartComposite(maskedBy = listOf(DrawableId("clip")), invertMask = invert)
				return model(
					drawables = listOf(maskSource, drawable("top")),
					parts = listOf(isolatedPart("fx", "top", composite)),
					rootChildren = listOf(OrgChild.Drawable(DrawableId("clip")), OrgChild.Part(PartId("fx"))),
				)
			}

			val plain =
				model(
					drawables = listOf(drawable("top")),
					parts = listOf(isolatedPart("fx", "top", PartComposite())),
					rootChildren = listOf(OrgChild.Part(PartId("fx"))),
				)
			val leftX = viewportSize / 4
			val rightX = viewportSize * 3 / 4
			val centerY = viewportSize / 2
			val plainPixel = renderPixel(plain, leftX, centerY)
			val maskAlpha = 0.85f // fallbackColorFor's fixed alpha - the mask source's coverage
			val straightLeft = renderPixel(clipped(invert = false), leftX, centerY)
			val straightRight = renderPixel(clipped(invert = false), rightX, centerY)
			assertPixelClose(
				IntArray(4) { channelIndex -> if (channelIndex < 3) (plainPixel[channelIndex] * maskAlpha).toInt() else straightLeft[3] },
				straightLeft,
				"clipped composite shows coverage x layer inside the mask",
			)
			assertTrue(straightRight.take(3).max() <= CHANNEL_TOLERANCE, "clipped composite hides outside")
			val invertedLeft = renderPixel(clipped(invert = true), leftX, centerY)
			val invertedRight = renderPixel(clipped(invert = true), rightX, centerY)
			assertPixelClose(
				IntArray(4) { channelIndex -> if (channelIndex < 3) (plainPixel[channelIndex] * (1f - maskAlpha)).toInt() else invertedLeft[3] },
				invertedLeft,
				"inverted clip keeps the coverage remainder inside",
			)
			assertPixelClose(
				IntArray(4) { channelIndex -> if (channelIndex < 3) plainPixel[channelIndex] else invertedRight[3] },
				invertedRight,
				"inverted clip shows fully outside",
			)
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun nestedCompositesComposeInnerThenOuter() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-renderer]", window)
		try {
			val plain =
				model(
					drawables = listOf(drawable("top")),
					parts = listOf(isolatedPart("fx", "top", PartComposite())),
					rootChildren = listOf(OrgChild.Part(PartId("fx"))),
				)
			val plainPixel = renderCenterPixel(plain)
			// Inner keeps only blue (multiply 0,0,1), outer halves the whole thing (opacity 0.5) -
			// over black, the pixel must be exactly (0, 0, blue/2).
			val inner = isolatedPart("inner", "top", PartComposite(multiplyColor = ColorRgb(0f, 0f, 1f)))
			val outer =
				Part(
					id = PartId("outer"),
					name = "outer",
					children = listOf(OrgChild.Part(PartId("inner"))),
					groupMode = PartGroupMode.Isolated,
					composite = PartComposite(opacity = 0.5f),
				)
			val nested =
				model(
					drawables = listOf(drawable("top")),
					parts = listOf(outer, inner),
					rootChildren = listOf(OrgChild.Part(PartId("outer"))),
				)
			val nestedPixel = renderCenterPixel(nested)
			assertPixelClose(
				intArrayOf(0, 0, plainPixel[2] / 2, nestedPixel[3]),
				nestedPixel,
				"inner multiply then outer opacity",
			)
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun drawableMultiplyColorTintsThePlainDraw() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-renderer]", window)
		try {
			// A flat drawable over black with NO composite - just its own 5.3 per-art-mesh multiply color
			// (1,0,0) on the plain draw path.  Over black the pixel is the layer's premultiplied color, so
			// the tint must keep red and zero green/blue.
			val positions = fullQuad
			val tinted =
				Drawable(
					id = DrawableId("top"),
					name = "top",
					parentDeformerId = null,
					blendMode = BlendMode.Normal,
					maskedBy = emptyList(),
					mesh = DrawableMesh(positions, FloatArray(positions.size), frontIndices),
					keyforms =
						KeyformGrid(
							listOf(KeyformAxis(paramA, floatArrayOf(0f))),
							listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size), opacity = 1f, multiplyColor = ColorRgb(1f, 0f, 0f)))),
						),
				)
			val plainPixel = renderCenterPixel(model(listOf(drawable("top")), rootChildren = listOf(OrgChild.Drawable(DrawableId("top")))))
			val tintedPixel = renderCenterPixel(model(listOf(tinted), rootChildren = listOf(OrgChild.Drawable(DrawableId("top")))))
			assertPixelClose(intArrayOf(plainPixel[0], 0, 0, tintedPixel[3]), tintedPixel, "drawable multiply (1,0,0) keeps red, zeroes green/blue")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun backFaceCullingHidesOnlyFlippedTriangles() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-renderer]", window)
		try {
			// Corpus front-face winding survives culling; the reversed winding is a flipped mesh and
			// disappears exactly when culling is on.
			val frontCulled = model(listOf(drawable("quad", culling = true)), rootChildren = listOf(OrgChild.Drawable(DrawableId("quad"))))
			assertTrue(renderCenterPixel(frontCulled).take(3).max() > 32, "front-facing survives culling")
			val backCulled =
				model(
					listOf(drawable("quad", indices = backIndices, culling = true)),
					rootChildren = listOf(OrgChild.Drawable(DrawableId("quad"))),
				)
			assertTrue(renderCenterPixel(backCulled).take(3).max() <= CHANNEL_TOLERANCE, "flipped winding is culled")
			val backDoubleSided =
				model(
					listOf(drawable("quad", indices = backIndices)),
					rootChildren = listOf(OrgChild.Drawable(DrawableId("quad"))),
				)
			assertTrue(renderCenterPixel(backDoubleSided).take(3).max() > 32, "double-sided shows both windings")
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-composite-renderer", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
