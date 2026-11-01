package org.umamo.render.gl

import org.umamo.format.raster.RasterImage
import org.umamo.render.FrameOverlays
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.pick.latticeHitsAt
import org.umamo.render.puppet.LATTICE_RIG_ANGLE_X
import org.umamo.render.puppet.LATTICE_RIG_INNER
import org.umamo.render.puppet.LATTICE_RIG_OUTER
import org.umamo.render.puppet.LATTICE_RIG_RANGED
import org.umamo.render.puppet.LatticeOverlay
import org.umamo.render.puppet.LatticeRole
import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.OverlayColor
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.render.puppet.latticeRigModel
import org.umamo.render.puppet.latticeShapesDrawnIn
import org.umamo.runtime.model.DeformerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the lattice layer on the pixels: each warp's control points and grid lines land where its lattice is in
 * world space, in its role's color, an idle lattice only in a frame that shows every deformer and only as its outline
 * and corners, the hovered one opaque, and a pose moves them with no new overlay.  The pick reads the same shapes, so
 * the pixel a lattice colors is the pixel that picks it.
 *
 * The lattice rig behind a 1:1 camera centered on the origin of a 128-pixel frame, so world (x, z) lands at
 * column 64 + x and row 64 - z.  At angle X -1 the outer warp's points sit at columns 54, 84, 114 and rows 64,
 * 94, 124; the inner warp's at columns 69, 99 and rows 79, 109; the ranged warp's at columns 14, 24 and rows
 * 14, 24.  Skips without a GL context.
 */
class LatticeLayerRenderTest {
	private val viewportSize = 128
	private val blackGrid = GridColors(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
	private val red = OverlayColor(1f, 0f, 0f, 1f)
	private val green = OverlayColor(0f, 1f, 0f, 1f)
	private val blue = OverlayColor(0f, 0f, 1f, 1f)
	private val sizes = MeshOverlaySizes(vertexDotRadiusPx = 4f, edgeWidthPx = 2f, faceDotRadiusPx = 3f)
	private val roles = mapOf(LATTICE_RIG_OUTER to LatticeRole.Active, LATTICE_RIG_INNER to LatticeRole.Idle, LATTICE_RIG_RANGED to LatticeRole.Selected)

	@Test
	fun eachLatticeDrawsWhereItIsInItsRolesColor() {
		requireHeadlessGl("[lattice-render]")
		val live = LiveLatticeRenderer()
		val bare = live.render()
		live.renderer.setLatticeOverlay(LatticeOverlay(roles, sizes))

		val frame = live.render()

		assertEquals(listOf(0, 0, 255, 255), frame.at(84, 94), "the active warp's middle control point, an interior one")
		assertEquals(listOf(0, 0, 255, 255), frame.at(69, 64), "the active warp's top grid line, between two points")
		assertEquals(listOf(255, 0, 0, 255), frame.at(69, 79), "the idle warp's control point")
		assertEquals(listOf(0, 255, 0, 255), frame.at(14, 14), "the selected warp's control point")
		assertEquals(bare.at(70, 72), frame.at(70, 72), "inside a cell, nothing draws: a lattice never fills")
	}

	@Test
	fun theIdleLatticeWaitsForAFrameShowingEveryDeformer() {
		requireHeadlessGl("[lattice-render]")
		val live = LiveLatticeRenderer()
		val bare = live.render()
		live.renderer.setLatticeOverlay(LatticeOverlay(roles, sizes))

		val frame = live.render(FrameOverlays(allLattices = false))

		assertEquals(bare.at(69, 79), frame.at(69, 79), "no idle lattice")
		assertEquals(listOf(0, 0, 255, 255), frame.at(84, 94), "the active one still draws")
		assertEquals(listOf(0, 255, 0, 255), frame.at(14, 14), "and the selected one")
	}

	@Test
	fun aPoseMovesTheLatticeAndThePickFollows() {
		requireHeadlessGl("[lattice-render]")
		val live = LiveLatticeRenderer()
		live.renderer.setLatticeOverlay(LatticeOverlay(roles, sizes))
		live.render()

		live.renderer.setPose(mapOf(LATTICE_RIG_ANGLE_X to 1f))
		val frame = live.render()

		assertEquals(listOf(0, 0, 255, 255), frame.at(104, 94), "the middle point slid 20 pixels right")
		assertEquals(
			LATTICE_RIG_OUTER,
			pickAt(live, LatticeOverlay(roles, sizes), 104.5f, 94.5f).first(),
			"and the pick at that pixel finds it",
		)
	}

	@Test
	fun anIdleLatticeDrawsItsOutlineAndCornersAlone() {
		requireHeadlessGl("[lattice-render]")
		val live = LiveLatticeRenderer()
		val bare = live.render()
		val overlay = LatticeOverlay(mapOf(LATTICE_RIG_OUTER to LatticeRole.Idle), sizes)
		live.renderer.setLatticeOverlay(overlay)

		val frame = live.render()

		assertEquals(listOf(255, 0, 0, 255), frame.at(54, 64), "a corner dot")
		assertEquals(listOf(255, 0, 0, 255), frame.at(69, 64), "the top edge, between two points")
		assertEquals(bare.at(84, 61), frame.at(84, 61), "no dot on a side's middle point, only its edge")
		assertEquals(bare.at(84, 94), frame.at(84, 94), "no middle point")
		assertEquals(bare.at(84, 79), frame.at(84, 79), "no inner grid line")
		assertEquals(LATTICE_RIG_OUTER, pickAt(live, overlay, 54.5f, 64.5f).first(), "the corner the dot colors picks the warp")
		assertTrue(pickAt(live, overlay, 84.5f, 94.5f).isEmpty(), "the undrawn middle point picks nothing")
	}

	@Test
	fun theHoveredLatticeDrawsOpaqueInItsAreaAlone() {
		requireHeadlessGl("[lattice-render]")
		val live = LiveLatticeRenderer(idle = red.copy(alpha = 0.5f))
		live.renderer.setLatticeOverlay(LatticeOverlay(mapOf(LATTICE_RIG_OUTER to LatticeRole.Idle, LATTICE_RIG_INNER to LatticeRole.Idle), sizes))

		val plain = live.render()
		val hovered = live.render(FrameOverlays(hoveredLattice = LATTICE_RIG_OUTER))

		// Up and left of the corner, inside its dot and off both edges, so one translucent layer covers the pixel.
		assertTrue(plain.at(52, 62)[0] in 100..160, "the idle corner dot blends at half alpha, got ${plain.at(52, 62)}")
		assertEquals(listOf(255, 0, 0, 255), hovered.at(52, 62), "hovered, it draws opaque")
		assertEquals(plain.at(69, 79), hovered.at(69, 79), "the other idle lattice keeps its alpha")
	}

	/**
	 * The warps a click at a pixel picks, over the shapes the frame draws ([latticeShapesDrawnIn]) at the test's sizes.
	 *
	 * @param LiveLatticeRenderer live The renderer.
	 * @param LatticeOverlay overlay The overlay it holds.
	 * @param Float pointerX The pointer x in pixels.
	 * @param Float pointerY The pointer y in pixels.
	 * @return List<DeformerId> The hit warps, best first.
	 */
	private fun pickAt(live: LiveLatticeRenderer, overlay: LatticeOverlay, pointerX: Float, pointerY: Float): List<DeformerId> =
		latticeHitsAt(
			latticeShapesDrawnIn(overlay, live.renderer.latticeWorldPoints(), FrameOverlays()),
			pointerX,
			pointerY,
			sizes.vertexDotRadiusPx,
			sizes.edgeWidthPx,
			ViewportCamera(0f, 0f, 1f),
			viewportSize,
			viewportSize,
		)

	/**
	 * Reads one pixel of a frame as four 0..255 components.
	 *
	 * @param Int column The pixel column.
	 * @param Int row The pixel row, top first.
	 * @return List<Int> The red, green, blue, and alpha components.
	 */
	private fun RasterImage.at(column: Int, row: Int): List<Int> {
		val offset = (row * viewportSize + column) * 4

		return (0 until 4).map { channelIndex -> rgba[offset + channelIndex].toInt() and 0xFF }
	}

	/**
	 * A GL renderer over the lattice rig, at angle X -1, over a black grid, with opaque selected and active colors.
	 *
	 * @param OverlayColor idle The idle lattice color; opaque red by default.
	 */
	private inner class LiveLatticeRenderer(idle: OverlayColor = red) {
		private val device = GlRenderDevice()
		val renderer = PuppetRenderer(latticeRigModel(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		private val target: RenderTarget

		init {
			renderer.initGl()
			renderer.setGrid(blackGrid, 100f, 10)
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))
			renderer.setMeshOverlayPalette(MeshOverlayPalette.Classic.copy(latticeIdle = idle, latticeSelected = green, latticeActive = blue))
			renderer.setPose(mapOf(LATTICE_RIG_ANGLE_X to -1f))
			target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		}

		/**
		 * Renders one frame and reads it back.
		 *
		 * @param FrameOverlays overlays What the frame draws beyond the backdrop; everything by default.
		 * @return RasterImage The frame, top row first.
		 */
		fun render(overlays: FrameOverlays = FrameOverlays()): RasterImage {
			renderer.render(target, viewportSize, viewportSize, overlays = overlays)

			return device.readPixels(target)
		}
	}
}