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
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins that the mask-coverage pass forces Normal blend regardless of the mask source's own blend mode.
 *
 * The coverage target is cleared to alpha 0, then the mask sources are drawn into it and the masked
 * drawable multiplies its alpha by that coverage.  A mask source whose blend mode is Multiply or Additive
 * uses alpha factors (GL_ZERO, GL_ONE), so if the coverage draw honoured that mode the target's alpha
 * would stay 0 and the masked drawable would vanish entirely.  The renderer must therefore draw coverage
 * at Normal, not the source's mode - this test fails if that regresses (the masked quad goes black).
 *
 * マスク被覆パスがマスク元のブレンドモードに関わらず Normal で描くことを検証する。Multiply / Additive の
 * マスク元だと被覆アルファが 0 のままになり、被マスク描画が消えてしまう回帰を捕まえる。
 */
class MaskCoverageBlendTest {
	private val viewportSize = 64
	private val paramA = ParameterId("A")
	private val maskId = DrawableId("mask_source")
	private val maskedId = DrawableId("masked_quad")

	// A pure-black backdrop so "the masked quad is visible" is simply "some pixel is not black".
	private val blackGrid = GridColors(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

	// A full-viewport quad in the fixed 1:1 camera (world x,y in [-32, 32]).
	private val fullQuad = floatArrayOf(-32f, -32f, 32f, -32f, -32f, 32f, 32f, 32f)

	private fun restGrid(positions: FloatArray): KeyformGrid<MeshForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size)))),
		)

	private fun drawable(id: DrawableId, blendMode: BlendMode, maskedBy: List<DrawableId>): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = blendMode,
			maskedBy = maskedBy,
			mesh = DrawableMesh(fullQuad, FloatArray(fullQuad.size), intArrayOf(0, 1, 2, 1, 3, 2)),
			keyforms = restGrid(fullQuad),
		)

	/** A model whose mask SOURCE has the given blend mode, clipping a full-viewport masked quad. */
	private fun model(maskBlend: BlendMode): PuppetModel {
		val maskSource = drawable(maskId, maskBlend, emptyList())
		val masked = drawable(maskedId, BlendMode.Normal, listOf(maskId))
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(maskSource, masked),
			rootChildren = listOf(OrgChild.Drawable(maskSource.id), OrgChild.Drawable(masked.id)),
			rootPartId = null,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		)
	}

	@Test
	fun maskedDrawableSurvivesAMultiplyBlendMaskSource() {
		val window = createHeadlessGl()
		assumeGlContext("[mask-blend]", window)
		try {
			// The mask source is Multiply - the mode that most clearly breaks a coverage pass that honours it.
			val litColumns = renderAndCountArtColumns(model(BlendMode.Multiply))
			assertTrue(
				litColumns > viewportSize / 2,
				"a Multiply-blend mask source must still produce coverage: the masked quad should fill the row " +
					"(got $litColumns lit columns of $viewportSize)",
			)
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/** Renders [source] and returns how many columns of the centre row carry drawn (non-black) art. */
	private fun renderAndCountArtColumns(source: PuppetModel): Int {
		val device = GlRenderDevice()
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setGrid(blackGrid, 100f, 10)
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		val framebuffer = (target as GlRenderTarget).framebuffer
		renderer.render(target, viewportSize, viewportSize)
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer)
		val frame = BufferUtils.createByteBuffer(viewportSize * viewportSize * 4)
		GL11.glReadPixels(0, 0, viewportSize, viewportSize, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, frame)
		val row = viewportSize / 2
		var lit = 0
		for (column in 0 until viewportSize) {
			val pixel = (row * viewportSize + column) * 4
			val red = frame.get(pixel).toInt() and 0xFF
			val green = frame.get(pixel + 1).toInt() and 0xFF
			val blue = frame.get(pixel + 2).toInt() and 0xFF
			if (maxOf(red, green, blue) > 16) {
				lit++
			}
		}
		return lit
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-mask-blend", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
