package org.umamo.render.puppet

import org.umamo.render.FrameBackdrop
import org.umamo.render.FrameOverlays
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.OverlayBuffersCreated
import org.umamo.render.device.OverlayBuffersDestroyed
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.RecordedOverlayDraw
import org.umamo.render.device.RecordedTarget
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.StorePositionsUpdated
import org.umamo.render.device.TextureFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what the renderer records for its lattice layer, on a [RecordingRenderDevice]: each role's lines then its
 * dots in the role's color at the frame's lattice opacity, idle first and active last, the hovered lattice in the
 * hover color after the rest of its role; idle lattices only in a frame that shows every deformer; nothing under the
 * Show Overlays master or in a capture; and an upload only for a lattice the pose moved.
 */
class LatticeLayerPassTest {
	private val viewportSize = 64
	private val sizes = MeshOverlaySizes(2.5f, 1f, 2.5f)
	private val roles = mapOf(LATTICE_RIG_OUTER to LatticeRole.Active, LATTICE_RIG_INNER to LatticeRole.Idle, LATTICE_RIG_RANGED to LatticeRole.Selected)
	private val palette = MeshOverlayPalette.Classic

	/**
	 * A renderer over the lattice rig, posed at rest behind a 1:1 camera, holding [roles].
	 *
	 * @param RecordingRenderDevice device The device to record on.
	 * @return PuppetRenderer The renderer.
	 */
	private fun latticeRenderer(device: RecordingRenderDevice): PuppetRenderer {
		val renderer = PuppetRenderer(latticeRigModel(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		renderer.setPose(emptyMap())
		renderer.setLatticeOverlay(LatticeOverlay(roles, sizes))

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

	/**
	 * Each recorded overlay draw as its pipeline and color, in recording order.
	 *
	 * @param List<RecordedOverlayDraw> draws The draws.
	 * @return List<Pair<PipelinePurpose, List<Float>>> The pipeline and idle-slot color of each.
	 */
	private fun describe(draws: List<RecordedOverlayDraw>): List<Pair<PipelinePurpose, List<Float>>> = draws.map { draw -> draw.purpose to draw.idleColor }

	@Test
	fun eachRoleDrawsItsLinesThenItsDotsIdleFirstAndActiveLast() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		val target = mainTarget(device)
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize)

		val draws = device.overlayDraws()
		assertEquals(
			listOf(
				PipelinePurpose.OverlayEdge to components(palette.latticeIdle),
				PipelinePurpose.OverlayVertexDot to components(palette.latticeIdle),
				PipelinePurpose.OverlayEdge to components(palette.latticeSelected),
				PipelinePurpose.OverlayVertexDot to components(palette.latticeSelected),
				PipelinePurpose.OverlayEdge to components(palette.latticeActive),
				PipelinePurpose.OverlayVertexDot to components(palette.latticeActive),
			),
			describe(draws),
		)
		assertTrue(draws.none { draw -> draw.purpose == PipelinePurpose.OverlayFaceFill || draw.purpose == PipelinePurpose.OverlayFaceDot }, "a lattice never fills")
		assertTrue(draws.all { draw -> draw.selectedColor == draw.idleColor && draw.activeColor == draw.idleColor }, "a lattice draws whole in its role's color")
		assertEquals(sizes.edgeWidthPx / 2f, draws[0].sizePx, "the grid lines at the line width")
		assertEquals(sizes.vertexDotRadiusPx, draws[1].sizePx, "the dots at the dot radius")
		assertEquals(3, device.resourceEvents.filterIsInstance<OverlayBuffersCreated>().size, "one buffer set per lattice")
		assertTrue(
			device.resourceEvents.filterIsInstance<OverlayBuffersCreated>().all { created -> created.buffers.spec.faceCorners.isEmpty() },
			"and no triangles in any of them",
		)
	}

	@Test
	fun theHoveredLatticeDrawsInTheHoverColorAfterTheRestOfItsRole() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		val translucent = OverlayColor(0.25f, 0.25f, 0.25f, 0.5f)
		renderer.setMeshOverlayPalette(palette.copy(latticeIdle = translucent))
		renderer.setLatticeOverlay(LatticeOverlay(roles.mapValues { LatticeRole.Idle }, sizes))
		val target = mainTarget(device)
		renderer.render(target, viewportSize, viewportSize)
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(hoveredLattice = LATTICE_RIG_OUTER))

		val draws = device.overlayDraws()
		val hover = components(palette.latticeHover)
		assertEquals(
			listOf(
				PipelinePurpose.OverlayEdge to components(translucent),
				PipelinePurpose.OverlayEdge to components(translucent),
				PipelinePurpose.OverlayVertexDot to components(translucent),
				PipelinePurpose.OverlayVertexDot to components(translucent),
				PipelinePurpose.OverlayEdge to hover,
				PipelinePurpose.OverlayVertexDot to hover,
			),
			describe(draws),
			"the other idle lattices at the palette's alpha, then the hovered one in the hover color",
		)
		// The outer warp is the 2x2 lattice, whose outline rings eight border segments; the others are 1x1.
		assertEquals(listOf(4, 4, 4, 4, 8, 8), draws.map { draw -> draw.buffers.spec.edgeEndpoints.size / 2 }, "the hovered one is the outer warp")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)

		assertEquals(
			listOf(PipelinePurpose.OverlayEdge, PipelinePurpose.OverlayEdge, PipelinePurpose.OverlayEdge, PipelinePurpose.OverlayVertexDot, PipelinePurpose.OverlayVertexDot, PipelinePurpose.OverlayVertexDot),
			device.overlayDraws().map { draw -> draw.purpose },
			"with nothing hovered, one batch",
		)
		assertTrue(device.overlayDraws().all { draw -> draw.idleColor == components(translucent) }, "all at the palette's alpha")
		assertTrue(device.resourceEvents.none { event -> event is OverlayBuffersCreated }, "and the hover uploads nothing")
	}

	@Test
	fun theHoverColorWinsOverEveryRoleAndThePendingMark() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		renderer.setLatticeOverlay(LatticeOverlay(roles, sizes, pendingIds = setOf(LATTICE_RIG_RANGED)))
		val target = mainTarget(device)
		val hover = components(palette.latticeHover)

		for (hovered in listOf(LATTICE_RIG_INNER, LATTICE_RIG_RANGED, LATTICE_RIG_OUTER)) {
			device.clearLog()
			renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(hoveredLattice = hovered))

			val colors = device.overlayDraws().map { draw -> draw.idleColor }
			assertEquals(2, colors.count { color -> color == hover }, "$hovered draws its lines and dots in the hover color, whatever its role")
		}

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(hoveredLattice = LATTICE_RIG_INNER))
		assertTrue(device.overlayDraws().any { draw -> draw.idleColor == components(palette.latticePending) }, "the unhovered pending lattice keeps its mark")
	}

	@Test
	fun theLatticeOpacityFadesEveryRoleButNotTheHover() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		val target = mainTarget(device)
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(hoveredLattice = LATTICE_RIG_INNER, latticeOpacity = 0.5f))

		val halved = { color: OverlayColor -> components(color.copy(alpha = color.alpha * 0.5f)) }
		assertEquals(
			listOf(
				PipelinePurpose.OverlayEdge to components(palette.latticeHover),
				PipelinePurpose.OverlayVertexDot to components(palette.latticeHover),
				PipelinePurpose.OverlayEdge to halved(palette.latticeSelected),
				PipelinePurpose.OverlayVertexDot to halved(palette.latticeSelected),
				PipelinePurpose.OverlayEdge to halved(palette.latticeActive),
				PipelinePurpose.OverlayVertexDot to halved(palette.latticeActive),
			),
			describe(device.overlayDraws()),
			"the idle lattice is the hovered one, at the hover color whole; the selected and active fade by half",
		)
	}

	@Test
	fun noLatticeOpacityDrawsNoLattice() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		val target = mainTarget(device)
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(hoveredLattice = LATTICE_RIG_INNER, latticeOpacity = 0f))

		assertTrue(device.overlayDraws().isEmpty(), "at no opacity the layer draws nothing, the hovered lattice included")
	}

	@Test
	fun idleLatticesWaitForAFrameThatShowsEveryDeformer() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		val target = mainTarget(device)
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(allLattices = false))

		assertEquals(
			listOf(
				PipelinePurpose.OverlayEdge to components(palette.latticeSelected),
				PipelinePurpose.OverlayVertexDot to components(palette.latticeSelected),
				PipelinePurpose.OverlayEdge to components(palette.latticeActive),
				PipelinePurpose.OverlayVertexDot to components(palette.latticeActive),
			),
			describe(device.overlayDraws()),
			"the selected and active lattices still draw",
		)
	}

	@Test
	fun aFrameDrawsExactlyTheLatticesAClickInItCanPick() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		val target = mainTarget(device)
		val roleColors =
			mapOf(
				LatticeRole.Idle to components(palette.latticeIdle),
				LatticeRole.Selected to components(palette.latticeSelected),
				LatticeRole.Active to components(palette.latticeActive),
			)

		for (overlays in listOf(FrameOverlays(), FrameOverlays(allLattices = false), FrameOverlays(meshOverlay = false))) {
			device.clearLog()
			renderer.render(target, viewportSize, viewportSize, overlays = overlays)

			val pickable = latticesDrawnIn(LatticeOverlay(roles, sizes), renderer.latticeWorldPoints(), overlays).keys
			assertEquals(
				pickable.mapTo(HashSet()) { deformerId -> roleColors.getValue(roles.getValue(deformerId)) },
				device.overlayDraws().mapTo(HashSet()) { draw -> draw.idleColor },
				"the drawn roles are the pickable ones under $overlays",
			)
		}
	}

	@Test
	fun theShowOverlaysMasterHidesTheLayerButKeepsItWarm() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		val target = mainTarget(device)
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize, overlays = FrameOverlays(meshOverlay = false))

		assertTrue(device.overlayDraws().isEmpty(), "no lattice draws without the overlays")
		assertEquals(3, device.resourceEvents.filterIsInstance<OverlayBuffersCreated>().size, "but its buffers are uploaded for the next frame that shows it")

		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertEquals(6, device.overlayDraws().size)
		assertTrue(device.resourceEvents.isEmpty(), "which uploads nothing")
	}

	@Test
	fun onlyALatticeThePoseMovedUploads() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		val target = mainTarget(device)
		renderer.render(target, viewportSize, viewportSize)
		device.clearLog()

		renderer.render(target, viewportSize, viewportSize)
		assertTrue(device.resourceEvents.isEmpty(), "a still frame uploads nothing")

		renderer.setPose(mapOf(LATTICE_RIG_ANGLE_X to 1f))
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)

		val uploads = device.resourceEvents.filterIsInstance<StorePositionsUpdated>()
		assertEquals(2, uploads.size, "the outer lattice and the inner one riding it upload; the ranged one did not move")
		assertTrue(device.resourceEvents.filterIsInstance<OverlayBuffersCreated>().isEmpty(), "over the buffers they had")
		val outer = assertNotNull(renderer.latticeWorldPoints()[LATTICE_RIG_OUTER])
		assertTrue(uploads.any { upload -> upload.positions.contentEquals(outer.world) }, "the outer lattice uploads its moved points")
	}

	@Test
	fun theWorldPointsAreThePoseAndKeepTheirInstance() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		val atRest = renderer.latticeWorldPoints()

		assertSame(atRest, renderer.latticeWorldPoints(), "asking again over the same pose reads the same lattices")
		assertEquals(listOf(LATTICE_RIG_OUTER, LATTICE_RIG_INNER, LATTICE_RIG_RANGED), atRest.keys.toList())

		renderer.setPose(mapOf(LATTICE_RIG_ANGLE_X to 1f))
		val moved = renderer.latticeWorldPoints()
		assertNotSame(atRest[LATTICE_RIG_OUTER], moved[LATTICE_RIG_OUTER], "a pose that moves a lattice reads it anew")
		assertSame(atRest[LATTICE_RIG_RANGED], moved[LATTICE_RIG_RANGED], "and keeps one it did not move")
	}

	@Test
	fun aCaptureDrawsNoLatticeAndNoneClearsTheLayer() {
		val device = RecordingRenderDevice()
		val renderer = latticeRenderer(device)
		val target = mainTarget(device)
		renderer.render(target, viewportSize, viewportSize)
		device.clearLog()

		assertNotNull(renderer.renderSnapshot(ViewportCamera(0f, 0f, 1f), 32, 32, FrameBackdrop.Grid))
		assertTrue(device.overlayDraws().isEmpty(), "a capture draws no lattice")

		renderer.setLatticeOverlay(null)
		device.clearLog()
		renderer.render(target, viewportSize, viewportSize)
		assertTrue(device.overlayDraws().isEmpty(), "no overlay draws no lattice")
		assertEquals(3, device.resourceEvents.filterIsInstance<OverlayBuffersDestroyed>().size, "and frees every lattice's buffers")
	}
}