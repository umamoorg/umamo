package org.umamo.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
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
import org.umamo.runtime.model.MeshDeltaForm
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
 * it should, at load and after every kind of structural edit.
 *
 * [GpuDeformValidationTest] excludes glue, and `GlueTest` / `GlueCorpusTest` exercise only the CPU
 * [applyGluesResolved]; this covers the parts unique to the GPU path and easiest to get wrong: the
 * per-vertex weld attributes `planGlueLayout` plans (partner GLOBAL index, glue index, weld weight), the
 * shared position buffer's per-mesh base offsets, the pass-1 transform-feedback deform, and the pass-2
 * weld shader's partner lookup.  A wrong base offset or a swapped partner index welds a vertex toward
 * garbage.  The edit stages re-plan that layout, or re-upload a mesh under it, in session: a remesh, a
 * key edit on the anchor, a vertex added, a deleted anchor, and a deleted earlier glue, each of which
 * must land where the oracle says.
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
	private fun restGrid(positions: FloatArray): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size)))),
		)

	private fun drawable(
		id: DrawableId,
		positions: FloatArray,
		indices: IntArray,
		grid: KeyformGrid<MeshDeltaForm> = restGrid(positions),
	): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), indices),
			geometryGrid = grid,
		)

	/**
	 * A model over [drawables] at the root, in that order, with [glues].
	 *
	 * @param List<Drawable> drawables The drawables.
	 * @param List<Glue> glues The glues.
	 * @return PuppetModel The model.
	 */
	private fun modelOf(drawables: List<Drawable>, glues: List<Glue>): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			glues = glues,
			canvasWidth = 0f,
			canvasHeight = 0f,
			worldOriginX = 0f,
			worldOriginZ = 0f,
		)

	/**
	 * The glue pulling the welded quad's left edge (vertices [leftLow], [leftHigh]) fully onto the anchor's
	 * right edge (vertices 1 and 3), at [intensity].
	 *
	 * @param DrawableId anchor The anchor.
	 * @param DrawableId welded The welded quad.
	 * @param Int leftLow The welded quad's lower left-edge vertex.
	 * @param Int leftHigh The welded quad's upper left-edge vertex.
	 * @param Float intensity The glue's static intensity.
	 * @return Glue A fresh glue with a fresh pair list.
	 */
	private fun seamGlue(anchor: DrawableId, welded: DrawableId, leftLow: Int = 0, leftHigh: Int = 2, intensity: Float = 1f): Glue =
		Glue(anchor, welded, listOf(GluePair(1, leftLow, 0f, 1f), GluePair(3, leftHigh, 0f, 1f)), intensity = intensity)

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
						// No intensity track, so the static default of 1 (full weld) applies.
						pairs = listOf(GluePair(1, 0, 0f, 1f), GluePair(3, 2, 0f, 1f)),
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
			worldOriginZ = 0f,
		)
	}

	@Test
	fun gpuGlueWeldMatchesTheCpuOracle() {
		requireHeadlessGl("[gpu-glue]")
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
	}

	/** A re-triangulated welded quad still welds: its re-upload carries the planned weld. */
	@Test
	fun aRemeshedWeldedQuadStillWelds() {
		requireHeadlessGl("[gpu-glue-remesh]")
		val source = model(welded = true)
		val edited =
			modelOf(
				listOf(source.drawables[0], drawable(weldedId, weldedPositions.copyOf(), intArrayOf(0, 1, 3, 0, 3, 2))),
				listOf(seamGlue(anchorId, weldedId)),
			)
		assertEditLandsOnTheOracle("remesh", source, edited)
	}

	/** A key edit moving the anchor's right edge re-uploads the anchor, which keeps welding. */
	@Test
	fun anAnchorKeyEditStillWelds() {
		requireHeadlessGl("[gpu-glue-anchor-key]")
		val source = model(welded = true)
		// Vertices 1 and 3 (the right edge) move 20 left.
		val deltas = FloatArray(anchorPositions.size)
		deltas[2] = -20f
		deltas[6] = -20f
		val keyed = KeyformGrid(listOf(KeyformAxis(paramA, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(deltas))))
		val edited = modelOf(listOf(source.drawables[0].copy(geometryGrid = keyed), source.drawables[1]), source.glues)
		assertEditLandsOnTheOracle("anchor key edit", source, edited)
	}

	/** A vertex added to the welded quad grows the store and the seam follows the remapped pairs. */
	@Test
	fun aVertexAddedToTheWeldedQuadStillWelds() {
		requireHeadlessGl("[gpu-glue-grown]")
		val source = model(welded = true)
		// A center vertex prepended: the old vertices move up by one, so the left edge is now 1 and 3.
		val grown = floatArrayOf(90f, 0f, 60f, -30f, 120f, -30f, 60f, 30f, 120f, 30f)
		val fan = intArrayOf(0, 1, 2, 0, 2, 4, 0, 4, 3, 0, 3, 1)
		val edited = modelOf(listOf(source.drawables[0], drawable(weldedId, grown, fan)), listOf(seamGlue(anchorId, weldedId, leftLow = 1, leftHigh = 3)))
		assertEditLandsOnTheOracle("vertex added", source, edited)
	}

	/** Deleting the anchor and its glue leaves the quad drawn on its own art, not welded toward a ghost. */
	@Test
	fun deletingTheAnchorUnweldsTheQuad() {
		requireHeadlessGl("[gpu-glue-anchor-deleted]")
		val source = model(welded = true)
		val edited = modelOf(listOf(source.drawables[1]), emptyList())
		assertEditLandsOnTheOracle("anchor deleted", source, edited)
	}

	/**
	 * Deleting an earlier glue's anchor moves a later glue down one index; the later glue's seam must
	 * read its own intensity (0 here, so it draws unwelded) rather than the full weld an unfilled slot
	 * holds.
	 */
	@Test
	fun deletingAnEarlierGlueKeepsALaterGluesIntensity() {
		requireHeadlessGl("[gpu-glue-retagged]")
		val earlyAnchorId = DrawableId("early_anchor")
		val earlyWeldedId = DrawableId("early_welded")
		// The earlier pair sits well below the scan row, so only the later pair's quad is measured.
		val earlyAnchor = drawable(earlyAnchorId, floatArrayOf(-120f, 70f, -60f, 70f, -120f, 130f, -60f, 130f), IntArray(0))
		val earlyWelded = drawable(earlyWeldedId, floatArrayOf(60f, 70f, 120f, 70f, 60f, 130f, 120f, 130f), intArrayOf(0, 1, 2, 1, 3, 2))
		val anchor = drawable(anchorId, anchorPositions, IntArray(0))
		val welded = drawable(weldedId, weldedPositions, intArrayOf(0, 1, 2, 1, 3, 2))
		val laterGlue = seamGlue(anchorId, weldedId, intensity = 0f)
		val source = modelOf(listOf(earlyAnchor, earlyWelded, anchor, welded), listOf(seamGlue(earlyAnchorId, earlyWeldedId), laterGlue))
		val edited = modelOf(listOf(earlyWelded, anchor, welded), listOf(laterGlue))
		assertEditLandsOnTheOracle("earlier glue deleted", source, edited)
	}

	/**
	 * Renders [source], pushes [edited] the way the engine does (a model update, then a pose), renders
	 * again, and asserts both frames put mesh B where the CPU oracle puts it for the model each shows.
	 *
	 * @param String label The stage, for the failure message.
	 * @param PuppetModel source The model at load.
	 * @param PuppetModel edited The edited model.
	 */
	private fun assertEditLandsOnTheOracle(label: String, source: PuppetModel, edited: PuppetModel) {
		val (before, after) = renderedExtents(source, edited)
		val expectedBefore = cpuColumnExtent(source)
		val expectedAfter = cpuColumnExtent(edited)
		println("[gpu-glue] $label: before gpu=$before cpu=$expectedBefore | after gpu=$after cpu=$expectedAfter")
		assertExtentMatches("$label, before the edit", before, expectedBefore)
		assertExtentMatches("$label, after the edit", after, expectedAfter)
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

	/** World x → framebuffer column, at the fixed 1:1 camera centerd on the world origin. */
	private fun worldXToColumn(worldX: Float): Int = ((worldX + viewportSize / 2f)).toInt()

	/**
	 * Renders [source] and returns the (leftmost, rightmost) column carrying drawn art at mid height.
	 *
	 * @param PuppetModel source The model.
	 * @return Pair<Int, Int> The extent.
	 */
	private fun artColumnExtent(source: PuppetModel): Pair<Int, Int> = renderedExtents(source, null).first

	/**
	 * Renders [source], then, when [edited] is given, pushes it through [PuppetRenderer.updateModel] and a
	 * pose on the SAME renderer and renders again, returning the art's column extent in each frame.
	 *
	 * @param PuppetModel source The model at load.
	 * @param PuppetModel? edited The edited model, or null for the load frame alone.
	 * @return Pair<Pair<Int, Int>, Pair<Int, Int>> The extent before and after the edit (the same when
	 *   there is no edit).
	 */
	private fun renderedExtents(source: PuppetModel, edited: PuppetModel?): Pair<Pair<Int, Int>, Pair<Int, Int>> {
		val device = GlRenderDevice()
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setGrid(blackGrid, 100f, 10)
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		// Device-owned target; the raw fbo id is read for this test's own bottom-up glReadPixels.
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		val framebuffer = (target as GlRenderTarget).framebuffer
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
		renderer.render(target, viewportSize, viewportSize)
		val before = scanExtent(readPixels(viewportSize, viewportSize))
		if (edited == null) {
			return before to before
		}
		renderer.updateModel(edited)
		renderer.setPose(emptyMap())
		renderer.render(target, viewportSize, viewportSize)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
		return before to scanExtent(readPixels(viewportSize, viewportSize))
	}

	/**
	 * The (leftmost, rightmost) column carrying drawn art on the row through world y = 0, where both
	 * quads are at full height.
	 *
	 * @param ByteBuffer frame The frame, bottom-up rows.
	 * @return Pair<Int, Int> The extent.
	 */
	private fun scanExtent(frame: ByteBuffer): Pair<Int, Int> {
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
}