package org.umamo.render.gl

import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.system.MemoryUtil
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTarget
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
 * The "an edit reaches the viewport" gate: pushes an EDITED model through the same
 * [PuppetRenderer.updateModel] + [PuppetRenderer.setPose] path the render loop drives, on ONE persistent
 * renderer, and asserts the pixels change.  This is what a fresh-renderer-per-model test (like
 * [CompositeRendererTest]) cannot catch - a composite-only edit is a ModelDiff Keep with no buffer work,
 * so the resident is reused, and the renderer must still pick up the new blend/mask (drawable) or the
 * re-derived render tree (part) rather than the state captured at upload.  Flat-color drawables over a
 * black backdrop keep the expectations analytic.  Skips without a GL context.
 */
class CompositeEditReconcileTest {
	private val viewportSize = 64
	private val paramA = ParameterId("A")
	private val blackGrid = GridColors(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

	private val fullQuad = floatArrayOf(-32f, -32f, 32f, -32f, -32f, 32f, 32f, 32f)
	private val frontIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	// The left half of the viewport, as a clip-mask source.
	private val leftHalfQuad = floatArrayOf(-32f, -32f, 0f, -32f, -32f, 32f, 0f, 32f)

	private fun restGrid(positions: FloatArray): KeyformGrid<MeshForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size)))),
		)

	private fun drawable(
		id: String,
		positions: FloatArray = fullQuad,
		blendMode: BlendMode = BlendMode.Normal,
		maskedBy: List<DrawableId> = emptyList(),
		isVisible: Boolean = true,
	): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = blendMode,
			maskedBy = maskedBy,
			mesh = DrawableMesh(positions, FloatArray(positions.size), frontIndices),
			keyforms = restGrid(positions),
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

	private fun isolatedPart(partId: String, childId: String, composite: PartComposite): Part =
		Part(
			id = PartId(partId),
			name = partId,
			children = listOf(OrgChild.Drawable(DrawableId(childId))),
			groupMode = PartGroupMode.Isolated,
			composite = composite,
		)

	/**
	 * One persistent renderer that shows successive model snapshots through the edit path
	 * (updateModel → setPose → render), exactly as the render loop does.
	 */
	private inner class LiveRenderer(initial: PuppetModel) {
		private val device = GlRenderDevice()
		private val renderer =
			PuppetRenderer(initial, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		private val target: RenderTarget

		init {
			renderer.initGl()
			renderer.setGrid(blackGrid, 100f, 10)
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))
			target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		}

		fun show(model: PuppetModel) {
			renderer.updateModel(model)
			renderer.setPose(emptyMap())
			renderer.render(target, viewportSize, viewportSize)
		}

		fun pixel(x: Int, y: Int): IntArray {
			val image = device.readPixels(target)
			val pixelOffset = (y * viewportSize + x) * 4
			return IntArray(4) { channelIndex -> image.rgba[pixelOffset + channelIndex].toInt() and 0xFF }
		}

		fun centerPixel(): IntArray = pixel(viewportSize / 2, viewportSize / 2)
	}

	@Test
	fun drawableBlendEditReflectsAfterUpdateModel() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-edit-reconcile]", window)
		try {
			// A single flat quad over black.  Normal draws its color; a Multiply over black collapses to 0
			// (the src factor is the destination color, which is black).  The blend edit does no buffer
			// work (a ModelDiff Keep), so this passes only if updateModel re-stamps the resident's blend.
			val normal = model(listOf(drawable("top")), rootChildren = listOf(OrgChild.Drawable(DrawableId("top"))))
			val live = LiveRenderer(normal)
			live.show(normal)
			assertTrue(live.centerPixel().take(3).max() > 32, "Normal over black shows the layer color")

			val multiplied =
				model(
					listOf(drawable("top", blendMode = BlendMode.MultiplyPremultiplied)),
					rootChildren = listOf(OrgChild.Drawable(DrawableId("top"))),
				)
			live.show(multiplied)
			assertTrue(live.centerPixel().take(3).max() <= 3, "after the blend edit, Multiply over black is dark")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun drawableMaskEditReflectsAfterUpdateModel() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-edit-reconcile]", window)
		try {
			// A full quad, then masked by a hidden left-half source: adding the mask hides the right half.
			// Editing maskedBy is a Keep (no buffer work), so the right half only disappears if updateModel
			// re-stamps the resident's mask ids.  The mask source is already resident in both snapshots.
			val maskSource = drawable("clip", positions = leftHalfQuad, isVisible = false)
			val rootChildren = listOf(OrgChild.Drawable(DrawableId("clip")), OrgChild.Drawable(DrawableId("top")))
			val unmasked = model(listOf(maskSource, drawable("top")), rootChildren = rootChildren)
			val rightX = viewportSize * 3 / 4
			val centerY = viewportSize / 2

			val live = LiveRenderer(unmasked)
			live.show(unmasked)
			assertTrue(live.pixel(rightX, centerY).take(3).max() > 32, "unmasked quad covers the right half")

			val masked =
				model(
					listOf(maskSource, drawable("top", maskedBy = listOf(DrawableId("clip")))),
					rootChildren = rootChildren,
				)
			live.show(masked)
			assertTrue(live.pixel(rightX, centerY).take(3).max() <= 3, "after the mask edit, the right half is clipped away")
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	@Test
	fun partCompositeOpacityEditReflectsAfterUpdateModel() {
		val window = createHeadlessGl()
		assumeGlContext("[composite-edit-reconcile]", window)
		try {
			// An isolated part composited over black at opacity 1, then edited to 0.5.  The edit re-derives
			// renderRoot (withPartComposite does now), and updateModel + setPose rebuild the plan from it, so
			// the composite must halve - the end-to-end proof that a part composite edit reaches the screen.
			fun withOpacity(opacity: Float): PuppetModel =
				model(
					drawables = listOf(drawable("top")),
					parts = listOf(isolatedPart("fx", "top", PartComposite(opacity = opacity))),
					rootChildren = listOf(OrgChild.Part(PartId("fx"))),
				)

			val live = LiveRenderer(withOpacity(1f))
			live.show(withOpacity(1f))
			val opaque = live.centerPixel()
			assertTrue(opaque.take(3).max() > 32, "the composite shows at full opacity")

			live.show(withOpacity(0.5f))
			val halved = live.centerPixel()
			for (channelIndex in 0 until 3) {
				assertTrue(
					abs(halved[channelIndex] - opaque[channelIndex] / 2) <= 3,
					"opacity 0.5 halves ch$channelIndex over black: ${opaque.toList()} -> ${halved.toList()}",
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-composite-edit-reconcile", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
