package org.umamo.render.gl

import org.umamo.format.raster.RasterImage
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.ModelUpdateKind
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
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
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the renderer's positions-only fast path on the pixels: after a push that moved every mesh, a
 * renderer that kept its pose renders byte for byte what a fresh renderer posed over the moved model
 * renders, and its pick geometry follows the move.  The rig puts all three position-dependent paths
 * on screen - a warp child, a glue weld, and an isolated composite whose scissor must follow its
 * drawable - so an omission in the fast path changes bytes.  Skips without a GL context.
 */
class PositionsOnlyUpdateRenderTest {
	private val viewportSize = 64
	private val paramA = ParameterId("A")
	private val warpId = DeformerId("W")
	private val directId = DrawableId("direct")
	private val warpChildId = DrawableId("warpChild")
	private val anchorId = DrawableId("anchor")
	private val weldedId = DrawableId("welded")
	private val layeredId = DrawableId("layered")
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val worldShift = 3f
	private val latticeShift = 0.1f

	@Test
	fun aPositionsOnlyPushRendersAsAFreshPoseWould() {
		requireHeadlessGl("[positions-only-update]")
		val source = model()
		val moved = movedModel(source)
		val device = GlRenderDevice()
		val persistent = posedRenderer(source, device)
		val before = renderAt(device, persistent, viewportSize)
		val directCentroidBefore = pickCentroidX(persistent, directId)

		assertEquals(ModelUpdateKind.PositionsOnly, persistent.updateModel(moved), "a whole-selection move keeps the pose")
		val keptPose = renderAt(device, persistent, viewportSize)
		val freshPose = renderAt(device, posedRenderer(moved, device), viewportSize)

		assertContentEquals(freshPose.rgba, keptPose.rgba, "the kept pose renders byte for byte what a fresh pose over the moved model renders")
		assertFalse(before.rgba.contentEquals(keptPose.rgba), "the move reached the pixels, so the identity above is not vacuous")
		assertEquals(directCentroidBefore + worldShift, pickCentroidX(persistent, directId), absoluteTolerance = 1e-3f, "picking follows the kept pose")
	}

	/**
	 * A renderer over [source], uploaded, framed 1:1 at the origin, and posed at rest.
	 *
	 * @param PuppetModel source The model.
	 * @param GlRenderDevice device The device to render through.
	 * @return PuppetRenderer The renderer.
	 */
	private fun posedRenderer(source: PuppetModel, device: GlRenderDevice): PuppetRenderer {
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		return renderer
	}

	/**
	 * Renders one frame at the given square size and reads it back.
	 *
	 * @param GlRenderDevice device The device.
	 * @param PuppetRenderer renderer The renderer.
	 * @param Int size The square viewport extent in pixels.
	 * @return RasterImage The frame, top row first.
	 */
	private fun renderAt(device: GlRenderDevice, renderer: PuppetRenderer, size: Int): RasterImage {
		val target = device.createRenderTarget(RenderTargetSpec(size, size, TextureFormat.Rgba8, sampled = true))
		renderer.render(target, size, size)
		val image = device.readPixels(target)
		device.destroyRenderTarget(target)
		return image
	}

	/**
	 * The mean world x of one drawable's current pick geometry.
	 *
	 * @param PuppetRenderer renderer The renderer.
	 * @param DrawableId drawableId The drawable.
	 * @return Float The mean x, or NaN before any pose.
	 */
	private fun pickCentroidX(renderer: PuppetRenderer, drawableId: DrawableId): Float {
		val positions = renderer.pickGeometry()?.worldPositions?.getValue(drawableId) ?: return Float.NaN
		var sum = 0f
		var vertexCount = 0
		var coordinateIndex = 0
		while (coordinateIndex + 1 < positions.size) {
			sum += positions[coordinateIndex]
			vertexCount++
			coordinateIndex += 2
		}
		return sum / vertexCount
	}

	/**
	 * A zero-delta single-cell grid, so a drawable is keyed and deforms through the normal path.
	 *
	 * @param Int coordinateCount The mesh's coordinate count.
	 * @return KeyformGrid The grid.
	 */
	private fun restGrid(coordinateCount: Int): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(coordinateCount)))),
		)

	/**
	 * A quad drawable; an empty index array makes a triangle-less weld anchor.
	 *
	 * @param DrawableId id The drawable's id.
	 * @param FloatArray positions Its rest positions.
	 * @param IntArray indices Its triangle indices.
	 * @param DeformerId? parentDeformerId Its parent deformer, or null for a direct drawable.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: DrawableId, positions: FloatArray, indices: IntArray = quadIndices, parentDeformerId: DeformerId? = null): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = parentDeformerId,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), indices),
			geometryGrid = restGrid(positions.size),
		)

	/**
	 * The rig: a direct quad, a quad under a warp, a weld anchor and its welded quad, and a quad inside
	 * an isolated part, each in its own quadrant of the 64-pixel frame.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		val warp =
			Deformer.Warp(
				warpId,
				"W",
				null,
				null,
				1,
				1,
				true,
				KeyformGrid(
					listOf(KeyformAxis(paramA, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(floatArrayOf(-28f, -28f, -4f, -28f, -28f, -4f, -4f, -4f)))),
				),
			)
		val fxPart =
			Part(
				id = PartId("fx"),
				name = "fx",
				children = listOf(OrgChild.Drawable(layeredId)),
				groupMode = PartGroupMode.Isolated,
				composite = PartComposite(opacity = 0.5f),
			)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = listOf(fxPart),
			deformers = listOf(warp),
			drawables =
				listOf(
					drawable(directId, floatArrayOf(4f, -28f, 28f, -28f, 4f, -4f, 28f, -4f)),
					drawable(warpChildId, floatArrayOf(0.2f, 0.2f, 0.8f, 0.2f, 0.2f, 0.8f, 0.8f, 0.8f), parentDeformerId = warpId),
					drawable(anchorId, floatArrayOf(-28f, 4f, -16f, 4f, -28f, 28f, -16f, 28f), indices = IntArray(0)),
					drawable(weldedId, floatArrayOf(-8f, 4f, 28f, 4f, -8f, 28f, 28f, 28f)),
					drawable(layeredId, floatArrayOf(-12f, -12f, 12f, -12f, -12f, 12f, 12f, 12f)),
				),
			rootChildren =
				listOf(
					OrgChild.Part(PartId("fx")),
					OrgChild.Drawable(weldedId),
					OrgChild.Drawable(anchorId),
					OrgChild.Drawable(warpChildId),
					OrgChild.Drawable(directId),
				),
			rootPartId = null,
			glues = listOf(Glue(anchorId, weldedId, listOf(GluePair(1, 0, 0f, 1f), GluePair(3, 2, 0f, 1f)))),
		).withDerivedRenderRoot()
	}

	/**
	 * The rig with every mesh moved: world meshes by [worldShift] in x, the warp child by [latticeShift]
	 * in u, each in a new array sharing its uvs and indices, as a whole-selection Grab preview does.
	 *
	 * @param PuppetModel source The rig.
	 * @return PuppetModel The moved rig.
	 */
	private fun movedModel(source: PuppetModel): PuppetModel =
		source.copy(
			drawables =
				source.drawables.map { drawable ->
					val mesh = drawable.mesh ?: error("the rig carries meshes")
					val shift = if (drawable.id == warpChildId) latticeShift else worldShift
					val moved = FloatArray(mesh.positions.size) { coordinateIndex -> mesh.positions[coordinateIndex] + if (coordinateIndex % 2 == 0) shift else 0f }
					drawable.copy(mesh = DrawableMesh(moved, mesh.uvs, mesh.indices))
				},
		)
}