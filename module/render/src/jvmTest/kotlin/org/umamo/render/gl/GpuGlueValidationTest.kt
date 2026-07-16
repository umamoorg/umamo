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
import org.umamo.render.eval.applyCpuDeform
import org.umamo.render.eval.preparePose
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
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
 * Differential validation that the GPU's two-pass glue weld reaches the screen where the CPU oracle says
 * it should - the first coverage the GPU glue path has ever had.
 *
 * [GpuDeformValidationTest]'s docblock is explicit that it excludes glue ("the GPU path skips it"), and
 * `GlueTest` / `GlueCorpusTest` only exercise the CPU [applyGluesResolved]. So until this test, nothing
 * checked the parts that are unique to the GPU path and easiest to get wrong: the per-vertex glue
 * attribute layout ([PuppetRenderer]'s `buildGlueAttributes` - partner GLOBAL index, glue index, weld
 * weight), the shared position buffer's per-mesh base offsets, the pass-1 transform-feedback deform, and
 * the pass-2 weld shader's partner lookup. A wrong base offset or a swapped partner index welds a vertex
 * toward garbage, and no existing test would have noticed.
 *
 * The probe is built so the weld's effect is unmissable rather than a sub-pixel nudge. Two quads sit far
 * apart with a gap between them; the glue pulls mesh B's left edge all the way onto mesh A's right edge
 * (weightB = 1), so a working weld stretches B across the gap and a broken one leaves it where it was.
 * Mesh A is deliberately INDEX-LESS - a pure weld anchor that draws nothing - which both makes B's drawn
 * extent unambiguous (nothing else is on screen) and exercises the anchor path, since an index-less mesh
 * is skipped at upload unless it is glued.
 *
 * The assertion is against [applyCpuDeform], not a hardcoded pixel: the CPU oracle (which welds via
 * `applyGluesResolved`) says where B's leftmost vertex lands, and the render must agree within a pixel.
 * Bounded-pixel is the bar, per the geometry fidelity tier - never bit-identical.
 *
 * GPU グルー（2 パス）の溶接が CPU オラクルと一致することを検証する。GPU 側の初のグルー被覆。
 */
class GpuGlueValidationTest {
	private val viewportSize = 400
	private val paramA = ParameterId("A")
	private val anchorId = DrawableId("glue_anchor_a")
	private val weldedId = DrawableId("glue_welded_b")

	// A pure-black backdrop, so "art" is simply "not black" and no grid line can be mistaken for coverage.
	private val blackGrid = GridColors(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

	// Mesh A: the weld anchor, x in [-120, -60]. Vertices 1 and 3 are its RIGHT edge (x = -60).
	private val anchorPositions = floatArrayOf(-120f, -30f, -60f, -30f, -120f, 30f, -60f, 30f)

	// Mesh B: the welded quad, x in [60, 120]. Vertices 0 and 2 are its LEFT edge (x = 60).
	private val weldedPositions = floatArrayOf(60f, -30f, 120f, -30f, 60f, 30f, 120f, 30f)

	/** A single-cell keyform grid with zero deltas - the mesh sits at its rest positions. */
	private fun restGrid(positions: FloatArray): KeyformGrid<MeshForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(positions.size)))),
		)

	private fun drawable(id: DrawableId, positions: FloatArray, indices: IntArray): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), indices),
			keyforms = restGrid(positions),
		)

	/**
	 * The two-quad probe.  With [welded] the glue drags B's left edge onto A's right edge; without it the
	 * same two meshes sit apart, which is the control the weld is measured against.
	 */
	private fun model(welded: Boolean): PuppetModel {
		// The anchor carries NO indices: it draws nothing and exists only as a weld partner.
		val anchor = drawable(anchorId, anchorPositions, IntArray(0))
		val welded2 = drawable(weldedId, weldedPositions, intArrayOf(0, 1, 2, 1, 3, 2))
		// weightA = 0 pins the anchor in place; weightB = 1 moves B's seam vertex fully onto its partner.
		// Pair B's left-edge vertices (0, 2) with A's right-edge vertices (1, 3) at matching y.
		val glues =
			if (welded) {
				listOf(
					Glue(
						meshA = anchorId,
						meshB = weldedId,
						pairs = listOf(GluePair(1, 0, 0f, 1f), GluePair(3, 2, 0f, 1f)),
						intensity = null, // no grid -> a constant intensity of 1
					),
				)
			} else {
				emptyList()
			}
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(anchor, welded2),
			rootChildren = listOf(OrgChild.Drawable(anchor.id), OrgChild.Drawable(welded2.id)),
			rootPartId = null,
			glues = glues,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginY = 0f,
		)
	}

	@Test
	fun gpuGlueWeldMatchesTheCpuOracle() {
		val window = createHeadlessGl()
		assumeGlContext("[gpu-glue]", window)
		try {
			val welded = artColumnExtent(model(welded = true))
			val control = artColumnExtent(model(welded = false))

			// Where the CPU oracle - which welds through applyGluesResolved - puts B's edges.
			val expectedWelded = cpuColumnExtent(model(welded = true))
			val expectedControl = cpuColumnExtent(model(welded = false))
			println("[gpu-glue] welded: gpu=$welded cpu=$expectedWelded | control: gpu=$control cpu=$expectedControl")

			// The control first: if this drifts, the probe itself is wrong and the welded case proves nothing.
			assertExtentMatches("unwelded", control, expectedControl)
			// The weld must actually move B, not merely agree with a CPU oracle that also did nothing.
			assertTrue(
				expectedWelded.first < expectedControl.first - 50,
				"probe sanity: the CPU oracle's weld must move B's left edge a long way left",
			)
			assertExtentMatches("welded", welded, expectedWelded)
		} finally {
			GLFW.glfwDestroyWindow(window)
			GLFW.glfwTerminate()
		}
	}

	/**
	 * Asserts a rendered column extent matches the CPU oracle's within a pixel.
	 *
	 * BOTH edges are checked, not just the welded one.  The right edge is what catches an addressing bug
	 * that is self-consistent but out of bounds - a uniformly shifted base offset moves the writes, the
	 * own-reads, and the partner-reads together, so the welded edge still lands correctly and only the
	 * mesh's last vertex runs off the end of the shared buffer.  A left-edge-only probe is blind to it.
	 */
	private fun assertExtentMatches(label: String, actual: Pair<Int, Int>, expected: Pair<Int, Int>) {
		assertTrue(
			abs(actual.first - expected.first) <= 1,
			"$label: B's left edge must land where the CPU oracle says (gpu ${actual.first} vs cpu ${expected.first})",
		)
		assertTrue(
			abs(actual.second - expected.second) <= 1,
			"$label: B's right edge must land where the CPU oracle says (gpu ${actual.second} vs cpu ${expected.second})",
		)
	}

	/** The CPU oracle's (leftmost, rightmost) column across mesh B's welded vertices. */
	private fun cpuColumnExtent(source: PuppetModel): Pair<Int, Int> {
		val geometry = applyCpuDeform(source, preparePose(source, emptyMap()))
		val world = geometry.worldPositions[weldedId] ?: error("mesh B produced no geometry")
		var minX = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var coordIndex = 0
		while (coordIndex < world.size) {
			minX = minOf(minX, world[coordIndex])
			maxX = maxOf(maxX, world[coordIndex])
			coordIndex += 2
		}
		// The rightmost COVERED column is the last one inside the edge, hence the -1 against the exclusive edge.
		return worldXToColumn(minX) to worldXToColumn(maxX) - 1
	}

	/** World x → framebuffer column, at the fixed 1:1 camera centred on the world origin. */
	private fun worldXToColumn(worldX: Float): Int = ((worldX + viewportSize / 2f)).toInt()

	/** Renders [source] and returns the (leftmost, rightmost) column carrying drawn art at mid height. */
	private fun artColumnExtent(source: PuppetModel): Pair<Int, Int> {
		val device = GlRenderDevice()
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setGrid(blackGrid, 100f, 10)
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		val framebuffer = createColorFbo(viewportSize, viewportSize)
		val target = device.wrapExistingFramebuffer(framebuffer, viewportSize, viewportSize)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
		renderer.render(target, viewportSize, viewportSize)
		val frame = readPixels(viewportSize, viewportSize)
		// Scan the row through world y = 0, where both quads are at full height.
		val row = viewportSize / 2
		val artColumns = (0 until viewportSize).filter { isArt(frame, it, row) }
		check(artColumns.isNotEmpty()) { "the probe drew nothing at all - mesh B never reached the framebuffer" }
		return artColumns.first() to artColumns.last()
	}

	/** True when the pixel carries drawn art rather than the (pure black) backdrop. */
	private fun isArt(frame: ByteBuffer, column: Int, row: Int): Boolean {
		val pixel = (row * viewportSize + column) * 4
		val red = frame.get(pixel).toInt() and 0xFF
		val green = frame.get(pixel + 1).toInt() and 0xFF
		val blue = frame.get(pixel + 2).toInt() and 0xFF
		return maxOf(red, green, blue) > 24
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
		val window = GLFW.glfwCreateWindow(1, 1, "umamo-gpu-glue", MemoryUtil.NULL, MemoryUtil.NULL)
		if (window == MemoryUtil.NULL) {
			GLFW.glfwTerminate()
			return 0L
		}
		GLFW.glfwMakeContextCurrent(window)
		GL.createCapabilities()
		return window
	}
}
