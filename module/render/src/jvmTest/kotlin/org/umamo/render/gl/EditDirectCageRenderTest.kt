package org.umamo.render.gl

import org.umamo.format.raster.RasterImage
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.DirectMeshOverlay
import org.umamo.render.puppet.LATTICE_RIG_OUTER
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.OVERLAY_FLAG_ACTIVE
import org.umamo.render.puppet.OverlayColor
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.render.puppet.latticeRigModel
import org.umamo.runtime.model.ElementOwner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the 2D viewport's direct Edit cage on the pixels: a lattice cell fills whole, its one face dot sits at the
 * cell's center, and that center - an anchor vertex past the lattice's own points - draws no vertex dot.
 *
 * A square 1 x 1 cage over world x and z -30..30, behind a 1:1 camera centered on the origin of a 128-pixel frame,
 * so world (x, z) lands at column 64 + x and row 64 - z: the corners at columns and rows 34 and 94, the center at
 * (64, 64).  Skips without a GL context.
 */
class EditDirectCageRenderTest {
	private val viewportSize = 128
	private val blackGrid = GridColors(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
	private val red = OverlayColor(1f, 0f, 0f, 1f)
	private val green = OverlayColor(0f, 1f, 0f, 1f)
	private val blue = OverlayColor(0f, 0f, 1f, 1f)
	private val sizes = MeshOverlaySizes(vertexDotRadiusPx = 4f, edgeWidthPx = 2f, faceDotRadiusPx = 3f)
	private val owner: ElementOwner = ElementOwner.Deformer(LATTICE_RIG_OUTER)

	/**
	 * The cage, its cell active (and so filled) or idle, in [selectMode].
	 *
	 * @param MeshOverlaySelectMode selectMode The select mode.
	 * @param Boolean cellActive Whether the cell is the active one.
	 * @return DirectMeshOverlay The cage.
	 */
	private fun cage(selectMode: MeshOverlaySelectMode, cellActive: Boolean): DirectMeshOverlay {
		val mesh =
			MeshOverlayMesh(
				owner = owner,
				vertexCount = 5,
				edgeEndpoints = intArrayOf(0, 1, 2, 3, 0, 2, 1, 3),
				vertexFlags = ByteArray(5),
				edgeFlags = ByteArray(4),
				faceFlags = if (cellActive) byteArrayOf(OVERLAY_FLAG_ACTIVE, OVERLAY_FLAG_ACTIVE) else ByteArray(2),
				activeVertex = null,
				activeEdge = null,
				activeFace = if (cellActive) 0 else null,
				faceDotCorners = intArrayOf(4, 4, 4, 4, 4, 4),
				vertexDotCount = 4,
			)
		val positions = floatArrayOf(-30f, 30f, 30f, 30f, -30f, -30f, 30f, -30f, 0f, 0f)

		return DirectMeshOverlay(MeshOverlay(MeshOverlayKind.Edit, selectMode, listOf(mesh), sizes), mapOf(owner to positions), mapOf(owner to intArrayOf(0, 1, 3, 0, 3, 2)))
	}

	@Test
	fun theActiveCellFillsWholeAndDotsAtItsCenter() {
		requireHeadlessGl("[edit-cage-render]")
		val live = LiveCageRenderer()
		live.renderer.setEditDirectOverlay(cage(MeshOverlaySelectMode.Face, cellActive = true))

		val frame = live.render()

		assertEquals(listOf(0, 255, 0, 255), frame.at(84, 44), "one triangle of the cell fills")
		assertEquals(listOf(0, 255, 0, 255), frame.at(44, 84), "and so does the other")
		assertEquals(listOf(0, 0, 255, 255), frame.at(64, 64), "the cell's one dot, active, at its center")
	}

	@Test
	fun theCenterDrawsNoVertexDot() {
		requireHeadlessGl("[edit-cage-render]")
		val live = LiveCageRenderer()
		val bare = live.render()
		live.renderer.setEditDirectOverlay(cage(MeshOverlaySelectMode.Vertex, cellActive = false))

		val frame = live.render()

		assertEquals(listOf(255, 0, 0, 255), frame.at(34, 34), "a corner point's dot")
		assertEquals(bare.at(64, 64), frame.at(64, 64), "nothing at the center")
	}

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

	/** A GL renderer over the lattice rig at rest, over a black grid, with opaque cage colors. */
	private inner class LiveCageRenderer {
		private val device = GlRenderDevice()
		val renderer = PuppetRenderer(latticeRigModel(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		private val target: RenderTarget

		init {
			renderer.initGl()
			renderer.setGrid(blackGrid, 100f, 10)
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))
			renderer.setMeshOverlayPalette(
				MeshOverlayPalette.Classic.copy(
					vertexIdle = red,
					vertexSelected = red,
					vertexActive = red,
					faceIdle = green,
					faceSelected = green,
					faceActive = blue,
				),
			)
			renderer.setPose(emptyMap())
			target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		}

		/**
		 * Renders one frame and reads it back.
		 *
		 * @return RasterImage The frame, top row first.
		 */
		fun render(): RasterImage {
			renderer.render(target, viewportSize, viewportSize)

			return device.readPixels(target)
		}
	}
}