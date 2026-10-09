package org.umamo.render.gl

import org.umamo.format.raster.RasterImage
import org.umamo.render.DecodedImage
import org.umamo.render.FrameOverlays
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.OverlayColor
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.AtlasPage
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
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins wireframe culling on the pixels through the renderer's public API: a back quad whose diagonal
 * crosses the frame's center under a front quad that covers the center off its own diagonal.  Culling
 * leaves the back quad's wire out under the front quad's art and keeps it where nothing is in front;
 * culling off draws it through; a front quad under the order threshold hides nothing while one over it
 * does; a masked front quad hides only inside its mask.
 *
 * Both quads draw a solid blue page at the 1:1 camera, so world (x, y) lands at column 32 + x and row
 * 32 + y of the 64-pixel frame.  The back quad spans -24..24, its diagonal from (24, -24) to (-24, 24)
 * through the center; the front quad spans -8..24, its diagonal from (24, -8) to (-8, 24) eleven pixels
 * from the center.  Skips without a GL context.
 */
class WireframeCullingRenderTest {
	private val viewportSize = 64
	private val paramA = ParameterId("A")
	private val backId = DrawableId("back")
	private val frontId = DrawableId("front")
	private val maskId = DrawableId("mask")
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val quadEdges = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3)
	private val quadUvs = floatArrayOf(0.3f, 0.7f, 0.7f, 0.7f, 0.3f, 0.3f, 0.7f, 0.3f)
	private val backPositions = floatArrayOf(-24f, -24f, 24f, -24f, -24f, 24f, 24f, 24f)
	private val frontPositions = floatArrayOf(-8f, -8f, 24f, -8f, -8f, 24f, 24f, 24f)
	private val maskPositions = floatArrayOf(-4f, -4f, 4f, -4f, -4f, 4f, 4f, 4f)
	private val magenta = OverlayColor(1f, 0f, 1f, 1f)
	private val blackGrid = GridColors(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

	@Test
	fun theWireUnderAFrontDrawableIsCulledAndDrawsWhereNothingIsInFront() {
		requireHeadlessGl("[wireframe-culling]")
		val live = LiveRenderer(coveredModel())
		live.renderer.setMeshOverlay(wireframe(listOf(backId, frontId)))

		val culled = live.render()
		assertTrue(isArt(culled, 32, 32), "under the front quad the back quad's wire is left out: ${culled.at(32, 32)}")
		assertTrue(isWire(culled, 16, 48), "outside the front quad the back quad's wire draws: ${culled.at(16, 48)}")
		assertTrue(isWire(culled, 40, 40), "the front quad's own wire draws over its own art: ${culled.at(40, 40)}")

		val unculled = live.render(FrameOverlays(wireframeCulling = false))
		assertTrue(isWire(unculled, 32, 32), "with culling off the back quad's wire draws through the front quad: ${unculled.at(32, 32)}")
	}

	@Test
	fun aFrontDrawableUnderTheThresholdHidesNothing() {
		requireHeadlessGl("[wireframe-culling]")
		val faint = LiveRenderer(coveredModel(frontOpacity = 0.4f))
		faint.renderer.setMeshOverlay(wireframe(listOf(backId, frontId)))
		assertTrue(isWire(faint.render(), 32, 32), "a front quad at 0.4 alpha covers nothing: ${faint.render().at(32, 32)}")

		val solid = LiveRenderer(coveredModel(frontOpacity = 0.6f))
		solid.renderer.setMeshOverlay(wireframe(listOf(backId, frontId)))
		assertFalse(isWire(solid.render(), 32, 32), "a front quad at 0.6 alpha covers: ${solid.render().at(32, 32)}")
	}

	@Test
	fun aMaskedFrontDrawableHidesOnlyInsideItsMask() {
		requireHeadlessGl("[wireframe-culling]")
		val live = LiveRenderer(coveredModel(masked = true))
		live.renderer.setMeshOverlay(wireframe(listOf(backId, frontId)))

		val frame = live.render()
		assertTrue(isArt(frame, 32, 32), "inside the mask the front quad covers the back quad's wire: ${frame.at(32, 32)}")
		assertTrue(isWire(frame, 26, 38), "outside the mask, under the front quad's unmasked extent, the wire draws: ${frame.at(26, 38)}")
	}

	/**
	 * Whether a pixel, or a neighbor one pixel off, reads as the magenta wire over the blue art.
	 *
	 * @param RasterImage frame The frame, top row first.
	 * @param Int column The column.
	 * @param Int row The row.
	 * @return Boolean True when the wire is there.
	 */
	private fun isWire(frame: RasterImage, column: Int, row: Int): Boolean =
		(-1..1).any { dx -> (-1..1).any { dy -> frame.at(column + dx, row + dy).let { pixel -> pixel[0] > 160 && pixel[2] > 160 && pixel[1] < 80 } } }

	/**
	 * Whether a pixel and its eight neighbors all read as the blue art with no wire over them.
	 *
	 * @param RasterImage frame The frame, top row first.
	 * @param Int column The column.
	 * @param Int row The row.
	 * @return Boolean True when the art shows through untouched.
	 */
	private fun isArt(frame: RasterImage, column: Int, row: Int): Boolean =
		(-1..1).all { dx -> (-1..1).all { dy -> frame.at(column + dx, row + dy).let { pixel -> pixel[2] > 160 && pixel[0] < 80 && pixel[1] < 80 } } }

	/**
	 * One pixel's channels, top row first.
	 *
	 * @param Int column The column.
	 * @param Int row The row from the top.
	 * @return List<Int> Red, green, blue, alpha in 0..255.
	 */
	private fun RasterImage.at(column: Int, row: Int): List<Int> {
		val offset = (row * viewportSize + column) * 4
		return (0 until 4).map { channelIndex -> rgba[offset + channelIndex].toInt() and 0xFF }
	}

	/**
	 * The Object-mode wireframe of the given quads, nothing flagged, two pixels wide.
	 *
	 * @param List<DrawableId> ids The quads.
	 * @return MeshOverlay The overlay.
	 */
	private fun wireframe(ids: List<DrawableId>): MeshOverlay =
		MeshOverlay(
			MeshOverlayKind.ObjectWireframe,
			MeshOverlaySelectMode.Vertex,
			ids.map { id -> MeshOverlayMesh(id, 4, quadEdges, ByteArray(0), ByteArray(0), ByteArray(0), null, null, null, wireframeOnly = true) },
			MeshOverlaySizes(3.5f, 2f, 2.5f),
		)

	/**
	 * A renderer over the model on a solid blue page behind a black grid and the 1:1 camera, posed at rest,
	 * with an opaque magenta idle edge, rendering into a 64-pixel target on demand.
	 *
	 * @param PuppetModel model The model.
	 */
	private inner class LiveRenderer(model: PuppetModel) {
		private val device = GlRenderDevice()
		val renderer = PuppetRenderer(model, PuppetTextures(listOf(solidBlueImage()), model.drawables.associate { drawable -> drawable.id.raw to 0 }, premultipliedAlpha = false), device)
		private val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))

		init {
			renderer.initGl()
			renderer.setGrid(blackGrid, 100f, 10)
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))
			renderer.setMeshOverlayPalette(MeshOverlayPalette.Classic.copy(edgeIdle = magenta))
			renderer.setPose(emptyMap())
		}

		/**
		 * Renders one frame and reads it back.
		 *
		 * @param FrameOverlays overlays What the frame draws beyond the backdrop; everything, culled, by default.
		 * @return RasterImage The frame, top row first.
		 */
		fun render(overlays: FrameOverlays = FrameOverlays()): RasterImage {
			renderer.render(target, viewportSize, viewportSize, overlays = overlays)
			return device.readPixels(target)
		}
	}

	/**
	 * A 16x16 opaque blue page.
	 *
	 * @return DecodedImage The page.
	 */
	private fun solidBlueImage(): DecodedImage {
		val size = 16
		val rgba = ByteArray(size * size * 4)
		for (pixel in rgba.indices step 4) {
			rgba[pixel + 2] = 0xFF.toByte()
			rgba[pixel + 3] = 0xFF.toByte()
		}
		return DecodedImage(rgba, size, size)
	}

	/**
	 * The back quad under the front quad, drawn front-most, on one page; the front quad at the given opacity
	 * and, when masked, clipped by a small mask quad over the center that is drawn behind it.
	 *
	 * @param Float frontOpacity The front quad's opacity.
	 * @param Boolean masked Whether the front quad is masked by the mask quad.
	 * @return PuppetModel The model.
	 */
	private fun coveredModel(frontOpacity: Float = 1f, masked: Boolean = false): PuppetModel {
		val back = quad(backId, backPositions)
		val mask = quad(maskId, maskPositions)
		val front = quad(frontId, frontPositions, opacity = frontOpacity, maskedBy = if (masked) listOf(maskId) else emptyList())
		val drawables = if (masked) listOf(back, mask, front) else listOf(back, front)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			// Front to back, as the org tree is listed.
			rootChildren = drawables.asReversed().map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			atlas = PuppetAtlas(pages = listOf(AtlasPage(16, 16))),
		)
	}

	/**
	 * One keyed quad on the page.
	 *
	 * @param DrawableId id The drawable.
	 * @param FloatArray positions Its four corners.
	 * @param Float opacity Its opacity.
	 * @param List<DrawableId> maskedBy Its masks.
	 * @return Drawable The drawable.
	 */
	private fun quad(id: DrawableId, positions: FloatArray, opacity: Float = 1f, maskedBy: List<DrawableId> = emptyList()): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = maskedBy,
			opacity = opacity,
			mesh = DrawableMesh.withLocalEqualToCanvas(positions, quadUvs, quadIndices),
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(paramA, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size)))),
				),
		)
}