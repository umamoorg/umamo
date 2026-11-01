package org.umamo.render.puppet

import org.umamo.render.FrameOverlays
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.RecordedTarget
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.runtime.model.ElementOwner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what the renderer records for the 2D viewport's direct Edit cage, on a [RecordingRenderDevice]: a lattice's
 * cell fills as its two triangles and shows ONE face dot at its center - both triangles point their dot at the
 * appended center vertex - the center draws no vertex dot, the cage draws under the Show Overlays master, and it
 * draws under the lattice layer.
 */
class EditDirectCagePassTest {
	private val viewportSize = 64
	private val sizes = MeshOverlaySizes(3f, 1f, 2f)
	private val owner: ElementOwner = ElementOwner.Deformer(LATTICE_RIG_OUTER)

	// A 1 x 1 lattice's four points, row by row, then its cell's center.
	private val positions = floatArrayOf(-10f, 10f, 10f, 10f, -10f, -10f, 10f, -10f, 0f, 0f)
	private val triangles = intArrayOf(0, 1, 3, 0, 3, 2)
	private val edges = intArrayOf(0, 1, 2, 3, 0, 2, 1, 3)
	private val dotCorners = intArrayOf(4, 4, 4, 4, 4, 4)

	/**
	 * The cage of one 1 x 1 lattice whose cell is active, in [selectMode].
	 *
	 * @param MeshOverlaySelectMode selectMode The select mode.
	 * @return DirectMeshOverlay The cage.
	 */
	private fun cage(selectMode: MeshOverlaySelectMode): DirectMeshOverlay {
		val mesh =
			MeshOverlayMesh(
				owner = owner,
				vertexCount = 5,
				edgeEndpoints = edges,
				vertexFlags = byteArrayOf(1, 1, 1, 1, 0),
				edgeFlags = byteArrayOf(1, 1, 1, 1),
				faceFlags = byteArrayOf(OVERLAY_FLAG_ACTIVE, OVERLAY_FLAG_ACTIVE),
				activeVertex = null,
				activeEdge = null,
				activeFace = 0,
				faceDotCorners = dotCorners,
				vertexDotCount = 4,
			)

		return DirectMeshOverlay(MeshOverlay(MeshOverlayKind.Edit, selectMode, listOf(mesh), sizes), mapOf(owner to positions), mapOf(owner to triangles))
	}

	/**
	 * A renderer over the lattice rig, posed at rest behind a 1:1 camera.
	 *
	 * @param RecordingRenderDevice device The device to record on.
	 * @return PuppetRenderer The renderer.
	 */
	private fun renderer(device: RecordingRenderDevice): PuppetRenderer {
		val renderer = PuppetRenderer(latticeRigModel(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())

		return renderer
	}

	/**
	 * The surface a test renders into.
	 *
	 * @param RecordingRenderDevice device The device to allocate on.
	 * @return RecordedTarget The target.
	 */
	private fun mainTarget(device: RecordingRenderDevice): RecordedTarget =
		device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true)) as RecordedTarget

	@Test
	fun aCellDotsOnceAtItsCenterAndTheCenterDrawsNoVertexDot() {
		val device = RecordingRenderDevice()
		val renderer = renderer(device)
		val target = mainTarget(device)
		renderer.setEditDirectOverlay(cage(MeshOverlaySelectMode.Face))
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize)

		val draws = device.overlayDraws()
		val spec = draws.first().buffers.spec
		assertContentEquals(dotCorners, spec.faceDotCorners, "both triangles point their dot at the center")
		assertEquals(4, spec.vertexDotCount, "the center is no vertex of the lattice")
		assertContentEquals(triangles, spec.faceCorners, "the cell fills as its two triangles")
		assertEquals(PipelinePurpose.OverlayFaceFill, draws.first().purpose, "the cell fills")

		val activeDots = draws.filter { draw -> draw.purpose == PipelinePurpose.OverlayFaceDot && draw.activeDraw }
		assertEquals(listOf(listOf(4, 4, 4)), activeDots.map { draw -> draw.activeIndices }, "the active cell's one dot sits on its center")
	}

	@Test
	fun theCageDrawsUnderTheShowOverlaysMaster() {
		val device = RecordingRenderDevice()
		val renderer = renderer(device)
		val target = mainTarget(device)
		renderer.setEditDirectOverlay(cage(MeshOverlaySelectMode.Vertex))
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(meshOverlay = false))
		assertTrue(device.overlayDraws().isEmpty(), "no cage without the overlays")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertTrue(device.overlayDraws().any { draw -> draw.purpose == PipelinePurpose.OverlayVertexDot }, "the points dot in Vertex mode")

		renderer.setEditDirectOverlay(null)
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertTrue(device.overlayDraws().isEmpty(), "and a cleared cage draws nothing")
	}

	@Test
	fun theCageDrawsUnderTheLatticeLayer() {
		val device = RecordingRenderDevice()
		val renderer = renderer(device)
		val target = mainTarget(device)
		renderer.setEditDirectOverlay(cage(MeshOverlaySelectMode.Vertex))
		renderer.setLatticeOverlay(LatticeOverlay(mapOf(LATTICE_RIG_INNER to LatticeRole.Active), sizes))
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize)

		val draws = device.overlayDraws()
		val cageDraws = draws.indices.filter { drawIndex -> draws[drawIndex].buffers.spec.vertexDotCount == 4 && draws[drawIndex].buffers.spec.faceDotCorners != null }
		val latticeDraws = draws.indices.filter { drawIndex -> draws[drawIndex].buffers.spec.faceDotCorners == null }
		assertTrue(cageDraws.isNotEmpty() && latticeDraws.isNotEmpty())
		assertTrue(cageDraws.max() < latticeDraws.min(), "the cage first, the lattice layer over it")
	}
}