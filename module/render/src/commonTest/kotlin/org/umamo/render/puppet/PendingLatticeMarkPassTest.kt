package org.umamo.render.puppet

import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.RecordedTarget
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.runtime.model.ElementOwner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the mark of a lattice shaped in Pose mode and not yet keyed, on a [RecordingRenderDevice]: in the lattice layer
 * it draws its lines and dots in the palette's pending color whatever its role, and in the Edit cage its unselected
 * grid lines take that color while the selected ones keep theirs.
 */
class PendingLatticeMarkPassTest {
	private val viewportSize = 64
	private val sizes = MeshOverlaySizes(2.5f, 1f, 2.5f)
	private val palette = MeshOverlayPalette.Classic

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

	/**
	 * A palette color as the recorded uniform holds it.
	 *
	 * @param OverlayColor color The color.
	 * @return List<Float> Its four components.
	 */
	private fun components(color: OverlayColor): List<Float> = listOf(color.red, color.green, color.blue, color.alpha)

	@Test
	fun aPendingLatticeInTheLayerDrawsInThePendingColorWhateverItsRole() {
		val device = RecordingRenderDevice()
		val renderer = renderer(device)
		renderer.setLatticeOverlay(LatticeOverlay(mapOf(LATTICE_RIG_OUTER to LatticeRole.Active, LATTICE_RIG_INNER to LatticeRole.Idle), sizes, pendingIds = setOf(LATTICE_RIG_OUTER)))
		val target = mainTarget(device)
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize)

		assertEquals(
			listOf(
				PipelinePurpose.OverlayEdge to components(palette.latticeIdle),
				PipelinePurpose.OverlayVertexDot to components(palette.latticeIdle),
				PipelinePurpose.OverlayEdge to components(palette.latticePending),
				PipelinePurpose.OverlayVertexDot to components(palette.latticePending),
			),
			device.overlayDraws().map { draw -> draw.purpose to draw.idleColor },
		)
	}

	@Test
	fun aPendingCagesUnselectedGridLinesTakeThePendingColor() {
		val owner = ElementOwner.Deformer(LATTICE_RIG_OUTER)
		val positions = floatArrayOf(-10f, 10f, 10f, 10f, -10f, -10f, 10f, -10f, 0f, 0f)
		val device = RecordingRenderDevice()
		val renderer = renderer(device)
		val target = mainTarget(device)

		for (pending in listOf(false, true)) {
			val mesh =
				MeshOverlayMesh(
					owner = owner,
					vertexCount = 5,
					edgeEndpoints = intArrayOf(0, 1, 2, 3, 0, 2, 1, 3),
					vertexFlags = byteArrayOf(1, 0, 0, 0, 0),
					edgeFlags = byteArrayOf(0, 0, 0, 0),
					faceFlags = byteArrayOf(0, 0),
					activeVertex = null,
					activeEdge = null,
					activeFace = null,
					faceDotCorners = intArrayOf(4, 4, 4, 4, 4, 4),
					vertexDotCount = 4,
					style = if (pending) MeshStyle.PendingCage else MeshStyle.Plain,
				)
			renderer.setEditDirectOverlay(DirectMeshOverlay(MeshOverlay(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, listOf(mesh), sizes), mapOf(owner to positions), mapOf(owner to intArrayOf(0, 1, 3, 0, 3, 2))))
			device.clearLog()

			renderer.render(target, viewportSize, viewportSize)

			val edges = device.overlayDraws().filter { draw -> draw.purpose == PipelinePurpose.OverlayEdge && !draw.activeDraw }
			val expectedIdle = if (pending) palette.latticePending else palette.edgeIdle
			assertEquals(listOf(components(expectedIdle)), edges.map { draw -> draw.idleColor }, "pending $pending: the unselected grid lines")
			assertEquals(listOf(components(palette.edgeSelected)), edges.map { draw -> draw.selectedColor }, "pending $pending: the selected ones keep the selection color")
		}
	}
}