package org.umamo.render.puppet

import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.MeshCreated
import org.umamo.render.device.MeshDestroyed
import org.umamo.render.device.RecordedCapture
import org.umamo.render.device.RecordedMesh
import org.umamo.render.device.RecordedTarget
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.StoreCreated
import org.umamo.render.device.StoreDestroyed
import org.umamo.render.device.TextureFormat
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins that the renderer's glue layout follows the model through every structural edit: after any
 * [PuppetRenderer.updateModel], each glue mesh is uploaded with the weld attributes and store region
 * [planGlueLayout] gives the edited model, the store fits that layout, and nothing else is re-uploaded.
 * The oracle is the plan itself: what a fresh upload of the edited model would produce.
 *
 * The fixture is an index-less anchor (a weld partner that draws nothing) and a welded quad whose left
 * edge is pulled fully onto the anchor's right edge, the pair the structure tests use.  Every position
 * array is fresh per model, so a mesh is identified by the array it was uploaded from.
 */
class GlueResidencyTest {
	private val paramA = ParameterId("A")
	private val viewportSize = 64
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val anchorId = DrawableId("anchor")
	private val weldedId = DrawableId("welded")

	/** A remeshed welded quad keeps its weld, and its partner and the store are left alone. */
	@Test
	fun aRemeshedGlueMeshKeepsItsWeldAndItsPartner() {
		val source = pairModel()
		val (device, renderer, target) = rendered(source)
		val welded = drawable(weldedId, weldedPositions(), intArrayOf(0, 1, 3, 0, 3, 2))
		val edited = source.copy(drawables = listOf(source.drawables[0], welded), glues = listOf(pairGlue()))

		renderer.updateModel(edited)
		renderAt(device, renderer, target)

		val created = createdMeshes(device)
		assertEquals(listOf(welded.mesh!!.positions), created.map { mesh -> mesh.spec.restPositions }, "only the remeshed quad is re-created")
		assertWeldedPerPlan(edited, weldedId, created.single())
		assertTrue(destroyedMeshes(device).none { mesh -> mesh.spec.restPositions === source.drawables[0].mesh!!.positions }, "the anchor is untouched")
		assertNoStoreEvents(device)
		assertCaptures(edited, listOf(anchorId to 0, weldedId to 4), device)
	}

	/** A key edit re-uploads an index-less anchor, which comes back welded rather than dropped. */
	@Test
	fun aKeyEditOnAnIndexLessAnchorKeepsItWelding() {
		val source = pairModel()
		val (device, renderer, target) = rendered(source)
		val anchor = source.drawables[0].copy(geometryGrid = restGrid(8))
		val edited = source.copy(drawables = listOf(anchor, source.drawables[1]))

		renderer.updateModel(edited)
		renderAt(device, renderer, target)

		val created = createdMeshes(device)
		assertEquals(1, created.size, "only the anchor is re-created")
		assertWeldedPerPlan(edited, anchorId, created.single())
		assertCaptures(edited, listOf(anchorId to 0, weldedId to 4), device)
	}

	/** A vertex added to the last glue mesh grows the store and re-uploads that mesh alone. */
	@Test
	fun aVertexAddedToTheLastGlueMeshGrowsTheStoreAndKeepsItsPartner() {
		val source = pairModel()
		val (device, renderer, target) = rendered(source)
		val welded =
			drawable(
				weldedId,
				floatArrayOf(10f, -10f, 30f, -10f, 10f, 10f, 30f, 10f, 20f, 0f),
				intArrayOf(0, 1, 4, 1, 3, 4, 3, 2, 4, 2, 0, 4),
			)
		val edited = source.copy(drawables = listOf(source.drawables[0], welded), glues = listOf(pairGlue()))

		renderer.updateModel(edited)
		renderAt(device, renderer, target)

		assertEquals(listOf(8), device.resourceEvents.filterIsInstance<StoreDestroyed>().map { event -> event.store.vertexCapacity }, "the outgrown store is freed")
		assertEquals(listOf(9), device.resourceEvents.filterIsInstance<StoreCreated>().map { event -> event.store.vertexCapacity }, "and one that fits is made")
		val created = createdMeshes(device)
		assertEquals(1, created.size, "only the grown quad is re-created")
		assertWeldedPerPlan(edited, weldedId, created.single())
		assertCaptures(edited, listOf(anchorId to 0, weldedId to 4), device)
		assertEquals(5, captureOf(device, welded).vertexCount, "the grown quad captures all its vertices")
	}

	/** A vertex added to the earlier glue mesh moves the later one's region, so both are re-uploaded. */
	@Test
	fun aVertexAddedToAnEarlierGlueMeshMovesTheLaterOne() {
		val source = pairModel()
		val (device, renderer, target) = rendered(source)
		val anchor = drawable(anchorId, floatArrayOf(-30f, -10f, -10f, -10f, -30f, 10f, -10f, 10f, -20f, 0f), IntArray(0))
		val edited = source.copy(drawables = listOf(anchor, source.drawables[1]), glues = listOf(pairGlue()))

		renderer.updateModel(edited)
		renderAt(device, renderer, target)

		val created = createdMeshes(device)
		assertEquals(2, created.size, "both glue meshes are re-created")
		assertWeldedPerPlan(edited, anchorId, created.single { mesh -> mesh.spec.restPositions === anchor.mesh!!.positions })
		assertWeldedPerPlan(edited, weldedId, created.single { mesh -> mesh.spec.restPositions === source.drawables[1].mesh!!.positions })
		assertCaptures(edited, listOf(anchorId to 0, weldedId to 5), device)
	}

	/** An intensity edit moves no weld, so the device sees nothing. */
	@Test
	fun anIntensityEditIssuesNoDeviceCall() {
		val source = pairModel()
		val (device, renderer, _) = rendered(source)
		val edited = source.copy(glues = listOf(source.glues.single().copy(intensity = 0.25f)))

		assertEquals(ModelUpdateKind.Structural, renderer.updateModel(edited))

		assertTrue(device.resourceEvents.isEmpty(), "no device call, but ${device.resourceEvents}")
	}

	/** Deleting the anchor and its glue re-uploads the quad unwelded and frees the store. */
	@Test
	fun deletingAGluedDrawableRecreatesItsPartnerUnwelded() {
		val source = pairModel()
		val (device, renderer, target) = rendered(source)
		val edited = source.copy(drawables = listOf(source.drawables[1]), glues = emptyList(), rootChildren = listOf(OrgChild.Drawable(weldedId)))

		renderer.updateModel(edited)
		renderAt(device, renderer, target)

		val created = createdMeshes(device)
		assertEquals(1, created.size, "the quad is re-created")
		assertNull(created.single().spec.glueAttributes, "unwelded")
		assertEquals(1, device.resourceEvents.filterIsInstance<StoreDestroyed>().size, "and the store is freed")
		assertTrue(device.capturePasses().isEmpty(), "nothing is left to capture")
		assertTrue(device.meshDraws().none { draw -> draw.isGlue }, "the quad draws as a plain mesh")
	}

	/**
	 * Deleting the first glue's anchor moves the second glue to index 0: its meshes are re-uploaded with
	 * the new tag, so the seam reads its own glue's intensity rather than a slot nothing fills.
	 */
	@Test
	fun deletingTheFirstGlueRetagsTheSecondGluesSeam() {
		val frontAnchor = drawable(DrawableId("frontAnchor"), anchorPositions(), IntArray(0))
		val frontWelded = drawable(DrawableId("frontWelded"), weldedPositions(), quadIndices)
		val backAnchor = drawable(DrawableId("backAnchor"), anchorPositions(), IntArray(0))
		val backWelded = drawable(DrawableId("backWelded"), weldedPositions(), quadIndices)
		val backGlue = pairGlue(backAnchor.id, backWelded.id)
		val frontGlue = pairGlue(frontAnchor.id, frontWelded.id).copy(intensity = 0.25f)
		val source = model(listOf(frontAnchor, frontWelded, backAnchor, backWelded), listOf(backGlue, frontGlue))
		val (device, renderer, target) = rendered(source)
		val edited =
			source.copy(
				drawables = listOf(frontAnchor, frontWelded, backWelded),
				glues = listOf(frontGlue),
				rootChildren = listOf(frontAnchor, frontWelded, backWelded).map { drawable -> OrgChild.Drawable(drawable.id) },
			)

		renderer.updateModel(edited)
		renderAt(device, renderer, target)

		val frontDraw = device.meshDrawsOf(frontWelded.mesh!!.positions).single()
		val seamTag = assertNotNull(frontDraw.mesh.spec.glueAttributes).glueIndex[0]
		assertEquals(0, seamTag, "the surviving glue is now glue 0")
		assertEquals(0.25f, frontDraw.glueIntensities[seamTag], "and its seam reads that glue's intensity")
		assertTrue(device.meshDrawsOf(backWelded.mesh!!.positions).single().isGlue.not(), "the deleted glue's partner draws unwelded")
		assertCaptures(edited, listOf(frontAnchor.id to 0, frontWelded.id to 4), device)
		assertNoStoreEvents(device)
	}

	/** Undoing the delete brings the anchor back and welds the quad to it again. */
	@Test
	fun undoingADeleteRestoresTheWeld() {
		val source = pairModel()
		val (device, renderer, target) = rendered(source)
		renderer.updateModel(source.copy(drawables = listOf(source.drawables[1]), glues = emptyList(), rootChildren = listOf(OrgChild.Drawable(weldedId))))
		renderAt(device, renderer, target)
		device.clearLog()

		renderer.updateModel(source)
		renderAt(device, renderer, target)

		val created = createdMeshes(device)
		assertWeldedPerPlan(source, anchorId, created.single { mesh -> mesh.spec.restPositions === source.drawables[0].mesh!!.positions })
		assertWeldedPerPlan(source, weldedId, created.single { mesh -> mesh.spec.restPositions === source.drawables[1].mesh!!.positions })
		assertCaptures(source, listOf(anchorId to 0, weldedId to 4), device)
		assertTrue(device.meshDrawsOf(source.drawables[1].mesh!!.positions).single().isGlue, "the quad welds again")
	}

	/**
	 * A renderer over [source] that has posed and drawn one frame, with the log cleared after it, so the
	 * device records only what an edit does.
	 *
	 * @param PuppetModel source The model.
	 * @return Triple<RecordingRenderDevice, PuppetRenderer, RecordedTarget> The device, the renderer, and
	 *   its target.
	 */
	private fun rendered(source: PuppetModel): Triple<RecordingRenderDevice, PuppetRenderer, RecordedTarget> {
		val device = RecordingRenderDevice()
		val renderer = PuppetRenderer(source, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true)) as RecordedTarget
		renderAt(device, renderer, target)
		device.clearLog()
		return Triple(device, renderer, target)
	}

	/**
	 * Poses at rest and renders one frame, as the engine does after a structural push.
	 *
	 * @param RecordingRenderDevice device The device.
	 * @param PuppetRenderer renderer The renderer.
	 * @param RecordedTarget target The frame target.
	 */
	private fun renderAt(device: RecordingRenderDevice, renderer: PuppetRenderer, target: RecordedTarget) {
		renderer.setPose(emptyMap())
		renderer.render(target, viewportSize, viewportSize)
	}

	/**
	 * Asserts [mesh] was uploaded with exactly the weld attributes [planGlueLayout] gives [edited] for [id].
	 *
	 * @param PuppetModel edited The edited model.
	 * @param DrawableId id The glue mesh.
	 * @param RecordedMesh mesh The mesh the device created for it.
	 */
	private fun assertWeldedPerPlan(edited: PuppetModel, id: DrawableId, mesh: RecordedMesh) {
		val planned = assertNotNull(planGlueLayout(edited).attributesById[id], "$id is a glue mesh of the edited model")
		val uploaded = assertNotNull(mesh.spec.glueAttributes, "$id is uploaded welded")
		assertContentEquals(planned.partnerIndex, uploaded.partnerIndex, "$id partner indices")
		assertContentEquals(planned.glueIndex, uploaded.glueIndex, "$id glue indices")
		assertContentEquals(planned.weldWeight, uploaded.weldWeight, "$id weld weights")
	}

	/**
	 * Asserts the frame's one capture pass deformed exactly [expected] glue meshes, at those store offsets,
	 * in order.
	 *
	 * @param PuppetModel edited The model the frame shows.
	 * @param List<Pair<DrawableId, Int>> expected Each captured mesh and its store offset.
	 * @param RecordingRenderDevice device The device.
	 */
	private fun assertCaptures(edited: PuppetModel, expected: List<Pair<DrawableId, Int>>, device: RecordingRenderDevice) {
		val positionsById = edited.drawables.associate { drawable -> drawable.id to drawable.mesh!!.positions }
		val captures = device.capturePasses().single().captures
		val actual =
			captures.map { capture ->
				val id = positionsById.entries.single { (_, positions) -> positions === capture.mesh.restPositions }.key
				id to capture.destinationVertexOffset
			}
		assertEquals(expected, actual, "the glue meshes captured and their store offsets")
	}

	/**
	 * The frame's capture of the mesh uploaded from [drawable]'s positions.
	 *
	 * @param RecordingRenderDevice device The device.
	 * @param Drawable drawable The drawable.
	 * @return RecordedCapture The capture.
	 */
	private fun captureOf(device: RecordingRenderDevice, drawable: Drawable): RecordedCapture =
		device.capturePasses().single().captures.single { capture -> capture.mesh.restPositions === drawable.mesh!!.positions }

	/**
	 * Asserts the device neither freed nor made a store.
	 *
	 * @param RecordingRenderDevice device The device.
	 */
	private fun assertNoStoreEvents(device: RecordingRenderDevice) {
		assertTrue(device.resourceEvents.none { event -> event is StoreCreated || event is StoreDestroyed }, "no store event")
	}

	/**
	 * The meshes the device created since the log was cleared.
	 *
	 * @param RecordingRenderDevice device The device.
	 * @return List<RecordedMesh> The meshes, in creation order.
	 */
	private fun createdMeshes(device: RecordingRenderDevice): List<RecordedMesh> = device.resourceEvents.filterIsInstance<MeshCreated>().map { event -> event.mesh }

	/**
	 * The meshes the device freed since the log was cleared.
	 *
	 * @param RecordingRenderDevice device The device.
	 * @return List<RecordedMesh> The meshes, in order.
	 */
	private fun destroyedMeshes(device: RecordingRenderDevice): List<RecordedMesh> = device.resourceEvents.filterIsInstance<MeshDestroyed>().map { event -> event.mesh }

	/**
	 * The anchor's positions: x from -30 to -10; vertices 1 and 3 are its right edge.
	 *
	 * @return FloatArray A fresh array.
	 */
	private fun anchorPositions(): FloatArray = floatArrayOf(-30f, -10f, -10f, -10f, -30f, 10f, -10f, 10f)

	/**
	 * The welded quad's positions: x from 10 to 30; vertices 0 and 2 are its left edge.
	 *
	 * @return FloatArray A fresh array.
	 */
	private fun weldedPositions(): FloatArray = floatArrayOf(10f, -10f, 30f, -10f, 10f, 10f, 30f, 10f)

	/**
	 * A single-cell, zero-delta grid, so a mesh sits at its rest positions.
	 *
	 * @param Int coordinateCount The mesh's position array length.
	 * @return KeyformGrid<MeshDeltaForm> A fresh grid.
	 */
	private fun restGrid(coordinateCount: Int): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(
			listOf(KeyformAxis(paramA, floatArrayOf(0f))),
			listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(coordinateCount)))),
		)

	/**
	 * A flat drawable over [positions].
	 *
	 * @param DrawableId id The drawable.
	 * @param FloatArray positions The rest positions.
	 * @param IntArray indices The triangle indices; empty for a weld anchor.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: DrawableId, positions: FloatArray, indices: IntArray): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(positions, FloatArray(positions.size), indices),
			geometryGrid = restGrid(positions.size),
		)

	/**
	 * The glue pulling the welded quad's left edge fully onto the anchor's right edge.
	 *
	 * @param DrawableId anchor The anchor.
	 * @param DrawableId welded The welded quad.
	 * @return Glue A fresh glue with a fresh pair list.
	 */
	private fun pairGlue(anchor: DrawableId = anchorId, welded: DrawableId = weldedId): Glue =
		Glue(anchor, welded, listOf(GluePair(1, 0, 0f, 1f), GluePair(3, 2, 0f, 1f)))

	/**
	 * The anchor and the welded quad, glued.
	 *
	 * @return PuppetModel The model.
	 */
	private fun pairModel(): PuppetModel =
		model(
			listOf(drawable(anchorId, anchorPositions(), IntArray(0)), drawable(weldedId, weldedPositions(), quadIndices)),
			listOf(pairGlue()),
		)

	/**
	 * A model over [drawables] at the root, in that order, with [glues].
	 *
	 * @param List<Drawable> drawables The drawables.
	 * @param List<Glue> glues The glues.
	 * @return PuppetModel The model.
	 */
	private fun model(drawables: List<Drawable>, glues: List<Glue>): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			glues = glues,
		)
}