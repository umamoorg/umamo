package org.umamo.render.puppet

import org.umamo.render.device.OverlayBuffersCreated
import org.umamo.render.device.OverlayBuffersDestroyed
import org.umamo.render.device.OverlayFlagsUpdated
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.device.ResourceEvent
import org.umamo.render.device.StoreCreated
import org.umamo.render.device.StoreDestroyed
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what the overlay residency asks the device for on each kind of change: a first value uploads
 * everything, a flags-only value uploads flags and nothing else (by array identity), a repeated value
 * nothing, a new edge list or a replaced resident re-creates that mesh alone, a reorder re-uploads
 * nothing, the store grows but never shrinks, and null frees the buffers but keeps the store.
 */
class MeshOverlayResidencyTest {
	private val paramA = ParameterId("A")
	private val first = DrawableId("first")
	private val second = DrawableId("second")
	private val third = DrawableId("third")
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val quadEdges = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3)
	private val sizes = MeshOverlaySizes(3.5f, 1f, 2.5f)

	@Test
	fun theFirstValueUploadsTheStoreAndOneBufferSetPerPairedMesh() {
		val model = model()
		val (device, residents) = residentsOver(model)
		val residency = MeshOverlayResidency(device)
		val value = overlay(mesh(first), mesh(second))

		residency.apply(value, residents, model)

		val stores = device.eventsOf<StoreCreated>()
		assertEquals(1, stores.size, "one store for the overlay")
		assertEquals(8, stores.single().store.vertexCapacity, "sized to the two quads")
		val created = device.eventsOf<OverlayBuffersCreated>()
		assertEquals(listOf(first, second), residency.entries.map { entry -> entry.mesh.drawableId })
		assertEquals(2, created.size, "one buffer set per paired mesh")
		assertSame(value.meshes[0].edgeEndpoints, created[0].buffers.spec.edgeEndpoints, "the edge list is uploaded as shipped")
		assertSame(model.drawables.first { drawable -> drawable.id == first }.mesh!!.indices, created[0].buffers.spec.faceCorners, "the triangles are the model's own indices")
		assertEquals(listOf(0, 4), residency.entries.map { entry -> entry.baseOffset })
		assertTrue(residency.storeStale, "a fresh placement needs a capture")
		assertSame(value, residency.applied)
	}

	@Test
	fun aFlagsOnlyValueUploadsFlagsAndNothingElse() {
		val model = model()
		val (device, residents) = residentsOver(model)
		val residency = MeshOverlayResidency(device)
		val value = overlay(mesh(first), mesh(second))
		residency.apply(value, residents, model)
		val firstBuffers = device.eventsOf<OverlayBuffersCreated>()[0].buffers
		device.clearLog()
		residency.storeStale = false

		val reflagged = overlay(mesh(first, vertexFlags = byteArrayOf(1, 0, 0, 0)), value.meshes[1])
		residency.apply(reflagged, residents, model)

		val update = assertNotNull(device.resourceEvents.singleOrNull() as? OverlayFlagsUpdated, "exactly one flag upload, got ${device.resourceEvents.map { event -> event::class.simpleName }}")
		assertSame(firstBuffers, update.buffers, "the flags land on the mesh that changed")
		assertFalse(residency.storeStale, "a selection change moves no position")

		// Identity, not content: a fresh array with the same bytes still uploads, so a producer that keeps
		// its arrays is what avoids the upload.
		device.clearLog()
		residency.apply(overlay(mesh(first, vertexFlags = byteArrayOf(1, 0, 0, 0)), value.meshes[1]), residents, model)
		assertEquals(1, device.eventsOf<OverlayFlagsUpdated>().size, "a new array instance is an upload")
	}

	@Test
	fun theSameValueTwiceAsksForNothing() {
		val model = model()
		val (device, residents) = residentsOver(model)
		val residency = MeshOverlayResidency(device)
		val value = overlay(mesh(first), mesh(second))
		residency.apply(value, residents, model)
		device.clearLog()

		residency.apply(value, residents, model)

		assertTrue(device.resourceEvents.isEmpty(), "an applied value is applied, got ${device.resourceEvents.map { event -> event::class.simpleName }}")
	}

	@Test
	fun aNewEdgeListRecreatesThatMeshAlone() {
		val model = model()
		val (device, residents) = residentsOver(model)
		val residency = MeshOverlayResidency(device)
		val value = overlay(mesh(first), mesh(second))
		residency.apply(value, residents, model)
		val secondBuffers = device.eventsOf<OverlayBuffersCreated>()[1].buffers
		device.clearLog()
		residency.storeStale = false

		residency.apply(overlay(value.meshes[0], mesh(second, edges = quadEdges.copyOf())), residents, model)

		assertSame(secondBuffers, device.eventsOf<OverlayBuffersDestroyed>().single().buffers, "the old buffers of the changed mesh are freed")
		assertEquals(1, device.eventsOf<OverlayBuffersCreated>().size, "and replaced; the other mesh is untouched")
		assertTrue(device.eventsOf<OverlayFlagsUpdated>().isEmpty())
		assertFalse(residency.storeStale, "the positions did not move")
	}

	@Test
	fun aReorderedMeshSetUploadsNothingButRecaptures() {
		val model = model()
		val (device, residents) = residentsOver(model)
		val residency = MeshOverlayResidency(device)
		val value = overlay(mesh(first), mesh(second))
		residency.apply(value, residents, model)
		device.clearLog()
		residency.storeStale = false

		residency.apply(overlay(value.meshes[1], value.meshes[0]), residents, model)

		assertTrue(device.resourceEvents.isEmpty(), "local indices make a reorder free, got ${device.resourceEvents.map { event -> event::class.simpleName }}")
		assertEquals(listOf(second to 0, first to 4), residency.entries.map { entry -> entry.mesh.drawableId to entry.baseOffset })
		assertTrue(residency.storeStale, "the store layout moved, so the capture must run again")
	}

	@Test
	fun theStoreGrowsAndNeverShrinks() {
		val model = model()
		val (device, residents) = residentsOver(model)
		val residency = MeshOverlayResidency(device)
		val value = overlay(mesh(first), mesh(second))
		residency.apply(value, residents, model)
		device.clearLog()

		residency.apply(overlay(value.meshes[0], value.meshes[1], mesh(third)), residents, model)
		assertEquals(1, device.eventsOf<StoreDestroyed>().size, "the outgrown store is freed")
		assertEquals(12, device.eventsOf<StoreCreated>().single().store.vertexCapacity, "and replaced by one that fits")
		assertEquals(1, device.eventsOf<OverlayBuffersCreated>().size, "only the new mesh uploads")
		device.clearLog()

		residency.apply(overlay(value.meshes[0]), residents, model)
		assertTrue(device.eventsOf<StoreDestroyed>().isEmpty() && device.eventsOf<StoreCreated>().isEmpty(), "a smaller overlay keeps the store")
		assertEquals(2, device.eventsOf<OverlayBuffersDestroyed>().size, "the meshes that left are freed")
	}

	@Test
	fun aMeshWhoseResidentDisagreesGetsNoBuffers() {
		val model = model()
		val (device, residents) = residentsOver(model)
		val residency = MeshOverlayResidency(device)

		residency.apply(overlay(mesh(first, vertexCount = 5, edges = IntArray(0), vertexFlags = ByteArray(5), edgeFlags = ByteArray(0)), mesh(second)), residents, model)

		assertEquals(listOf(second), residency.entries.map { entry -> entry.mesh.drawableId }, "the mismatched mesh is left out")
		assertEquals(1, device.eventsOf<OverlayBuffersCreated>().size)
		assertEquals(4, device.eventsOf<StoreCreated>().single().store.vertexCapacity, "the store is sized to what paired")
	}

	@Test
	fun nullFreesTheBuffersAndKeepsTheStore() {
		val model = model()
		val (device, residents) = residentsOver(model)
		val residency = MeshOverlayResidency(device)
		val value = overlay(mesh(first), mesh(second))
		residency.apply(value, residents, model)
		device.clearLog()

		residency.apply(null, residents, model)
		assertEquals(2, device.eventsOf<OverlayBuffersDestroyed>().size)
		assertTrue(device.eventsOf<StoreDestroyed>().isEmpty(), "the store stays for the next overlay")
		assertNotNull(residency.store)
		assertNull(residency.applied)
		device.clearLog()

		residency.apply(value, residents, model)
		assertEquals(2, device.eventsOf<OverlayBuffersCreated>().size, "the buffers come back")
		assertTrue(device.eventsOf<StoreCreated>().isEmpty(), "over the kept store")
	}

	@Test
	fun aReplacedResidentRecreatesItsBuffers() {
		val model = model()
		val device = RecordingRenderDevice()
		val drawableResidency = DrawableResidency(device)
		drawableResidency.uploadAll(model, model.parameters) { null }
		val residency = MeshOverlayResidency(device)
		val value = overlay(mesh(first), mesh(second))
		residency.apply(value, drawableResidency.residents, model)
		// A topology edit of the first quad: the reconcile frees its resident and uploads a new one.
		val remeshed =
			model.copy(
				drawables =
					model.drawables.map { drawable ->
						if (drawable.id == first) {
							drawable.copy(mesh = DrawableMesh(drawable.mesh!!.positions, drawable.mesh!!.uvs, quadIndices.copyOf()))
						} else {
							drawable
						}
					},
			)
		drawableResidency.reconcile(model, remeshed) { null }
		device.clearLog()
		residency.residencyChanged = true

		residency.apply(value, drawableResidency.residents, remeshed)

		assertEquals(1, device.eventsOf<OverlayBuffersDestroyed>().size, "the replaced resident's buffers are freed")
		assertEquals(1, device.eventsOf<OverlayBuffersCreated>().size, "and rebuilt over the new resident")
		assertTrue(residency.storeStale, "a new resident needs a capture")
		assertFalse(residency.residencyChanged, "the re-pairing consumed the flag")
	}

	@Test
	fun disposeFreesTheBuffersAndTheStore() {
		val model = model()
		val (device, residents) = residentsOver(model)
		val residency = MeshOverlayResidency(device)
		residency.apply(overlay(mesh(first), mesh(second)), residents, model)
		device.clearLog()

		residency.dispose()

		assertEquals(2, device.eventsOf<OverlayBuffersDestroyed>().size)
		assertEquals(1, device.eventsOf<StoreDestroyed>().size)
		assertNull(residency.store)
		assertTrue(residency.entries.isEmpty())
	}

	/**
	 * The recorded events of one kind, in order.
	 *
	 * @return List<TEvent> The events.
	 */
	private inline fun <reified TEvent : ResourceEvent> RecordingRenderDevice.eventsOf(): List<TEvent> = resourceEvents.filterIsInstance<TEvent>()

	/**
	 * A keyed quad at the given origin.
	 *
	 * @param DrawableId id The drawable's id.
	 * @param Float originX Its left edge.
	 * @return Drawable The drawable.
	 */
	private fun quad(id: DrawableId, originX: Float): Drawable {
		val positions = floatArrayOf(originX, 0f, originX + 10f, 0f, originX, 10f, originX + 10f, 10f)
		return Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), quadIndices),
			geometryGrid = KeyformGrid(listOf(KeyformAxis(paramA, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size))))),
		)
	}

	/**
	 * Three quads side by side.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(quad(first, 0f), quad(second, 20f), quad(third, 40f)),
			rootChildren = listOf(OrgChild.Drawable(first), OrgChild.Drawable(second), OrgChild.Drawable(third)),
			rootPartId = null,
		)

	/**
	 * The model's residents over a fresh recording device, with the upload's events cleared away.
	 *
	 * @param PuppetModel model The model to upload.
	 * @return Pair The device and the residents.
	 */
	private fun residentsOver(model: PuppetModel): Pair<RecordingRenderDevice, Map<DrawableId, GpuDrawable>> {
		val device = RecordingRenderDevice()
		val drawableResidency = DrawableResidency(device)
		drawableResidency.uploadAll(model, model.parameters) { null }
		device.clearLog()
		return device to drawableResidency.residents
	}

	/**
	 * One quad's overlay entry.
	 *
	 * @param DrawableId id The drawable.
	 * @param IntArray edges Its edge list.
	 * @param ByteArray vertexFlags Its vertex flags.
	 * @param ByteArray edgeFlags Its edge flags.
	 * @param Int vertexCount The vertex count it claims.
	 * @return MeshOverlayMesh The entry.
	 */
	private fun mesh(
		id: DrawableId,
		edges: IntArray = quadEdges,
		vertexFlags: ByteArray = ByteArray(4),
		edgeFlags: ByteArray = ByteArray(edges.size / 2),
		vertexCount: Int = 4,
	): MeshOverlayMesh = MeshOverlayMesh(id, vertexCount, edges, vertexFlags, edgeFlags, ByteArray(2), null, null, null)

	/**
	 * An Edit overlay in vertex mode over the given meshes.
	 *
	 * @param MeshOverlayMesh meshes The meshes, in layout order.
	 * @return MeshOverlay The overlay.
	 */
	private fun overlay(vararg meshes: MeshOverlayMesh): MeshOverlay = MeshOverlay(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, meshes.toList(), sizes)
}