package org.umamo.render.gl

import org.umamo.format.raster.RasterImage
import org.umamo.render.DecodedImage
import org.umamo.render.GridColors
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.DirectMeshOverlay
import org.umamo.render.puppet.IslandEdgeRole
import org.umamo.render.puppet.IslandFillRole
import org.umamo.render.puppet.IslandStyle
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.OverlayColor
import org.umamo.render.puppet.PlacementCropQuad
import org.umamo.render.puppet.PlacementPreview
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins a UV area's mesh overlay on the pixels through the renderer's public API: over the four-quadrant
 * page, the dots, edges, and fills land where their display positions are (surface texels, y up), in the
 * palette's colors, blended premultiplied over the page; the grid paints the surround outside the page and
 * a border just outside its edge; and the overlay draws over both, so a vertex off the page stays visible.
 * The Object-mode islands fill and outline in their style's roles, a front island's fill over a back
 * island's edge; and a placement preview draws a scrim over the old spot and the crop at its new
 * placement, the same pixels from an uploaded crop as from the resident layer texture.
 *
 * Two cameras.  The fit camera shows the 64-texel page across the 64-pixel frame, so display (x, y) lands
 * at column x and top-first row 64 - y.  The zoomed-out camera centers the page at half zoom, so the page
 * covers columns and rows 16 to 48 and display (x, y) lands at column 16 + x / 2 and row 48 - y / 2.
 * Skips without a GL context.
 */
class UvOverlayRenderTest {
	private val viewportSize = 64
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val quadEdges = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3)
	private val sizes = MeshOverlaySizes(vertexDotRadiusPx = 4f, edgeWidthPx = 2f, faceDotRadiusPx = 3f)
	private val yellow = OverlayColor(1f, 1f, 0f, 1f)
	private val magenta = OverlayColor(1f, 0f, 1f, 1f)
	private val cyan = OverlayColor(0f, 1f, 1f, 1f)
	private val halfGray = OverlayColor(0.5f, 0.5f, 0.5f, 0.5f)
	private val halfYellow = OverlayColor(1f, 1f, 0f, 0.5f)
	private val orange = OverlayColor(1f, 0.5f, 0f, 1f)
	private val purple = OverlayColor(0.5f, 0f, 1f, 1f)
	private val surround = listOf(0, 0, 255, 255)
	private val frameColor = listOf(0, 255, 0, 255)

	// A black grid, a pure blue surround, and a pure green one-pixel border, so each reads back exactly.
	private val gridColors =
		GridColors(
			0f,
			0f,
			0f,
			0f,
			0f,
			0f,
			0f,
			0f,
			0f,
			surroundRed = 0f,
			surroundGreen = 0f,
			surroundBlue = 1f,
			frameRed = 0f,
			frameGreen = 1f,
			frameBlue = 0f,
			frameWidthPx = 1f,
		)

	// Opaque dot and edge colors unlike every page quadrant, the surround, and the border; translucent
	// face fills so the fill case pins the premultiplied blend over the page.
	private val palette =
		MeshOverlayPalette(
			vertexIdle = yellow,
			vertexSelected = cyan,
			vertexActive = magenta,
			edgeIdle = cyan,
			edgeSelected = yellow,
			edgeActive = magenta,
			faceIdle = halfGray,
			faceSelected = halfYellow,
			faceActive = magenta,
			warning = orange,
			pinnedPlacement = purple,
		)

	@Test
	fun theDotsAndEdgesLandOnTheirDisplayPositions() {
		requireHeadlessGl("[uv-overlay]")
		val scene = UvScene(fitCamera())
		val overlay = direct(quad(16f, 16f, 48f, 48f), vertexFlags = byteArrayOf(0, 0, 0, 2), activeVertex = 3)

		val frame = scene.render(overlay)

		assertEquals(rgba(yellow), frame.at(16, 47), "an idle vertex's dot sits on its display position, y up")
		assertEquals(rgba(magenta), frame.at(48, 16), "the active vertex's dot, in the active color")
		assertEquals(rgba(cyan), frame.at(32, 47), "the band of an idle edge, at its midpoint")
	}

	@Test
	fun aSelectedFaceFillsPremultipliedOverThePage() {
		requireHeadlessGl("[uv-overlay]")
		val scene = UvScene(fitCamera())
		val reference = scene.render(null)

		val frame = scene.render(direct(quad(16f, 16f, 48f, 48f), faceFlags = byteArrayOf(1, 0)))

		assertClose(blend(reference.at(22, 41), halfYellow), frame.at(22, 41), 2, "the selected triangle fills over the page")
		assertEquals(reference.at(42, 21), frame.at(42, 21), "outside Face mode an idle triangle keeps the page")
	}

	@Test
	fun anOffPageVertexStaysVisible() {
		requireHeadlessGl("[uv-overlay]")
		val scene = UvScene(zoomedOutCamera())
		val reference = scene.render(null)
		assertEquals(surround, reference.at(6, 36), "without the overlay the spot left of the page is the surround")

		// The quad's left edge sits at display x = -20, past the page's left edge.
		val frame = scene.render(direct(quad(-20f, 24f, 12f, 40f)))

		assertEquals(rgba(yellow), frame.at(6, 36), "a vertex off the page draws its dot over the surround")
	}

	@Test
	fun theSurroundAndBorderPaintOutsideTheSurface() {
		requireHeadlessGl("[uv-overlay]")
		val scene = UvScene(zoomedOutCamera())

		val frame = scene.render(null)

		assertEquals(surround, frame.at(4, 32), "far left of the page is the surround")
		assertEquals(surround, frame.at(32, 60), "far below it too")
		assertEquals(frameColor, frame.at(15, 32), "the column just outside the page's left edge is the border")
		assertEquals(frameColor, frame.at(32, 48), "the row just below its bottom edge too")
		assertEquals(surround, frame.at(13, 32), "a border one pixel wide")
		val inside = frame.at(24, 24)
		assertTrue(inside[0] > inside[1] + 60 && inside[0] > inside[2] + 60, "inside the edge is the page's own top-left quadrant (red), read $inside")
	}

	@Test
	fun islandRolesColorFillsAndEdges() {
		requireHeadlessGl("[uv-overlay]")
		val scene = UvScene(fitCamera())
		val reference = scene.render(null)

		val frame =
			scene.render(
				islands(
					quad(8f, 8f, 24f, 24f) to IslandStyle(IslandFillRole.Idle, IslandEdgeRole.Warning),
					quad(40f, 40f, 56f, 56f) to IslandStyle(IslandFillRole.Selected, IslandEdgeRole.Pinned),
				),
			)

		assertClose(rgba(orange), frame.at(16, 56), 1, "a warning island outlines in the warning color")
		// Interiors sampled clear of each quad's diagonal (x + y = 32 and x + y = 96) as well as its edges.
		assertClose(blend(reference.at(12, 52), halfGray), frame.at(12, 52), 2, "and fills idle over the page")
		assertClose(rgba(purple), frame.at(48, 24), 1, "a pinned island outlines in the pinned color")
		assertClose(blend(reference.at(44, 20), halfYellow), frame.at(44, 20), 2, "and a selected one fills selected")
	}

	@Test
	fun aFrontIslandsFillCoversABackIslandsEdge() {
		requireHeadlessGl("[uv-overlay]")
		val scene = UvScene(fitCamera())

		// The back island's right edge (x = 40) runs through the front island's interior.
		val frame =
			scene.render(
				islands(
					quad(8f, 8f, 40f, 40f) to IslandStyle(IslandFillRole.Idle, IslandEdgeRole.Idle),
					quad(24f, 16f, 56f, 32f) to IslandStyle(IslandFillRole.Selected, IslandEdgeRole.Idle),
				),
			)

		val underTheFront = frame.at(40, 44)
		assertNotEquals(rgba(cyan), underTheFront, "the back edge does not draw over the front island")
		assertClose(blend(rgba(cyan), halfYellow), underTheFront, 2, "the front island's fill lies over it")
	}

	@Test
	fun aCropDrawsAtItsNewPlacement() {
		requireHeadlessGl("[uv-overlay]")
		val scene = UvScene(fitCamera())
		val reference = scene.render(null)
		// The crop turned a quarter: its right runs up the page and its top runs left, so its red top half
		// lands at x 32 to 40 and its blue bottom half at x 40 to 48, over y 24 to 40.
		val turned = floatArrayOf(0f, -16f, 48f, 16f, 0f, 24f)
		val scrim = OverlayColor(0f, 0f, 0f, 0.5f)
		val placement =
			PlacementPreview(
				scrim,
				listOf(floatArrayOf(16f, 0f, 4f, 0f, 16f, 4f)),
				listOf(PlacementCropQuad("moving", halvesImage(8), turned, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f))),
				null,
				emptyList(),
			)

		val frame = scene.render(null, placement)

		assertEquals(listOf(255, 0, 0, 255), frame.at(36, 32), "the crop's top half, turned to the left")
		assertEquals(listOf(0, 0, 255, 255), frame.at(44, 32), "its bottom half, turned to the right")
		assertEquals(reference.at(52, 32), frame.at(52, 32), "outside the crop the page shows")
		assertClose(blend(reference.at(12, 52), scrim), frame.at(12, 52), 2, "the old spot lies under the scrim")
	}

	@Test
	fun theLayerTextureAndTheUploadedCropDrawTheSamePixels() {
		requireHeadlessGl("[uv-overlay]")
		val scene = UvScene(fitCamera())
		// A 16-pixel tile whose opaque art is its middle 8 pixels; the crop is that trim.
		val tile = DecodedImage(ByteArray(16 * 16 * 4), 16, 16)
		val trim = halvesImage(8)
		for (rowIndex in 0 until 8) {
			trim.rgba.copyInto(tile.rgba, ((rowIndex + 4) * 16 + 4) * 4, rowIndex * 8 * 4, (rowIndex + 1) * 8 * 4)
		}
		// Twice its size, so the bilinear sampling runs across the trim's edges.
		val placement =
			PlacementPreview(
				OverlayColor(0f, 0f, 0f, 0f),
				emptyList(),
				listOf(PlacementCropQuad("tileA", trim, floatArrayOf(17f, 0f, 21.5f, 0f, 15f, 19.25f), floatArrayOf(0.5f, 0f, 0.25f, 0f, 0.5f, 0.25f))),
				null,
				emptyList(),
			)
		val fromCrop = scene.render(null, placement)

		scene.renderer.setSourceLayerPlan(LayerDrawPlan(emptyMap(), mapOf("tileA" to 16L * 16L * 4L)))
		scene.renderer.deliverSourceLayerRasters(LayerRasterBatch(mapOf("tileA" to tile)))
		val fromLayer = scene.render(null, placement)

		val worst = fromCrop.rgba.indices.maxOf { byteIndex -> abs((fromCrop.rgba[byteIndex].toInt() and 0xFF) - (fromLayer.rgba[byteIndex].toInt() and 0xFF)) }
		assertTrue(worst <= 1, "the two sources draw the same pixels (worst channel difference $worst)")
		assertEquals(listOf(255, 0, 0, 255), fromLayer.at(30, 32), "and the layer path draws the crop at all, its red half here")
	}

	@Test
	fun theOverlayDrawsOverTheBorder() {
		requireHeadlessGl("[uv-overlay]")
		val scene = UvScene(zoomedOutCamera())
		assertEquals(frameColor, scene.render(null).at(15, 32), "the border column")

		// Vertex 0 sits on the page's left edge, at display (0, 32).
		val frame = scene.render(direct(quad(0f, 32f, 24f, 48f)))

		assertEquals(rgba(yellow), frame.at(15, 32), "the dot covers the border beside its vertex")
		assertNotEquals(frameColor, frame.at(15, 26), "and the left edge's band covers it below the dot")
	}

	/**
	 * An islands overlay of quads, in the order given (back to front), each in its style.
	 *
	 * @param Pair<FloatArray, IslandStyle> islands Each island's display positions and style.
	 * @return DirectMeshOverlay The overlay.
	 */
	private fun islands(vararg islands: Pair<FloatArray, IslandStyle>): DirectMeshOverlay {
		val meshes =
			islands.mapIndexed { islandIndex, island ->
				MeshOverlayMesh(DrawableId("island$islandIndex"), 4, quadEdges, ByteArray(0), ByteArray(0), ByteArray(0), null, null, null, island.second)
			}
		return DirectMeshOverlay(
			MeshOverlay(MeshOverlayKind.Islands, MeshOverlaySelectMode.Vertex, meshes, sizes),
			meshes.withIndex().associate { (islandIndex, mesh) -> mesh.drawableId to islands[islandIndex].first },
			meshes.associate { mesh -> mesh.drawableId to quadIndices },
		)
	}

	/**
	 * A square image, top half opaque red and bottom half opaque blue (top row first).
	 *
	 * @param Int side Its side in pixels.
	 * @return DecodedImage The image.
	 */
	private fun halvesImage(side: Int): DecodedImage {
		val rgba = ByteArray(side * side * 4)
		for (rowIndex in 0 until side) {
			for (columnIndex in 0 until side) {
				val pixel = (rowIndex * side + columnIndex) * 4
				rgba[pixel] = (if (rowIndex < side / 2) 255 else 0).toByte()
				rgba[pixel + 2] = (if (rowIndex < side / 2) 0 else 255).toByte()
				rgba[pixel + 3] = 255.toByte()
			}
		}
		return DecodedImage(rgba, side, side)
	}

	/**
	 * The camera that fits the page across the frame.
	 *
	 * @return ViewportCamera The camera.
	 */
	private fun fitCamera(): ViewportCamera = ViewportCamera(32f, 32f, 1f)

	/**
	 * The camera that shows the page at half size in the middle of the frame.
	 *
	 * @return ViewportCamera The camera.
	 */
	private fun zoomedOutCamera(): ViewportCamera = ViewportCamera(32f, 32f, 0.5f)

	/**
	 * A quad's display positions from its bottom-left to its top-right corner: vertices bottom-left,
	 * bottom-right, top-left, top-right, so triangle 0 is the lower-left half.
	 *
	 * @param Float left The left edge.
	 * @param Float bottom The bottom edge, y up.
	 * @param Float right The right edge.
	 * @param Float top The top edge.
	 * @return FloatArray The positions.
	 */
	private fun quad(left: Float, bottom: Float, right: Float, top: Float): FloatArray = floatArrayOf(left, bottom, right, bottom, left, top, right, top)

	/**
	 * A Vertex-mode Edit overlay over one quad at [positions].
	 *
	 * @param FloatArray positions The quad's display positions.
	 * @param ByteArray vertexFlags The vertex flags.
	 * @param ByteArray faceFlags The face flags.
	 * @param Int? activeVertex The active vertex, or null.
	 * @return DirectMeshOverlay The overlay.
	 */
	private fun direct(
		positions: FloatArray,
		vertexFlags: ByteArray = ByteArray(4),
		faceFlags: ByteArray = ByteArray(2),
		activeVertex: Int? = null,
	): DirectMeshOverlay {
		val id = DrawableId("quad")
		val mesh = MeshOverlayMesh(id, 4, quadEdges, vertexFlags, ByteArray(quadEdges.size / 2), faceFlags, activeVertex, null, null)
		return DirectMeshOverlay(MeshOverlay(MeshOverlayKind.Edit, MeshOverlaySelectMode.Vertex, listOf(mesh), sizes), mapOf(id to positions), mapOf(id to quadIndices))
	}

	/**
	 * An opaque color's channels as read back.
	 *
	 * @param OverlayColor color The color.
	 * @return List<Int> Red, green, blue, alpha in 0..255.
	 */
	private fun rgba(color: OverlayColor): List<Int> =
		listOf(color.red, color.green, color.blue, color.alpha).map { channel -> (channel * 255f).roundToInt() }

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
	 * The premultiplied frame pixel a straight-alpha color leaves over [under].
	 *
	 * @param List<Int> under The frame pixel beneath, as read back.
	 * @param OverlayColor color The straight-alpha color drawn over it.
	 * @return List<Int> The expected pixel.
	 */
	private fun blend(under: List<Int>, color: OverlayColor): List<Int> {
		val keep = 1f - color.alpha
		return listOf(
			(color.red * color.alpha * 255f + under[0] * keep).roundToInt(),
			(color.green * color.alpha * 255f + under[1] * keep).roundToInt(),
			(color.blue * color.alpha * 255f + under[2] * keep).roundToInt(),
			(color.alpha * 255f + under[3] * keep).roundToInt(),
		)
	}

	/**
	 * Asserts every channel of [actual] is within [tolerance] of [expected].
	 *
	 * @param List<Int> expected The expected channels.
	 * @param List<Int> actual The channels read back.
	 * @param Int tolerance The largest channel difference allowed.
	 * @param String label What the pixel is, for the failure message.
	 */
	private fun assertClose(expected: List<Int>, actual: List<Int>, tolerance: Int, label: String) {
		val worst = expected.zip(actual).maxOf { (want, got) -> abs(want - got) }
		assertTrue(worst <= tolerance, "$label: expected $expected, read $actual")
	}

	/**
	 * A page (RGBA, top row first) with four distinctly colored quadrants: top-left red, top-right green,
	 * bottom-left blue, bottom-right white.
	 *
	 * @param Int size The page's edge in texels.
	 * @return DecodedImage The page.
	 */
	private fun quadrantPage(size: Int): DecodedImage {
		val rgba = ByteArray(size * size * 4)
		for (rowIndex in 0 until size) {
			for (columnIndex in 0 until size) {
				val top = rowIndex < size / 2
				val left = columnIndex < size / 2
				val channels =
					when {
						top && left -> intArrayOf(255, 0, 0)
						top -> intArrayOf(0, 255, 0)
						left -> intArrayOf(0, 0, 255)
						else -> intArrayOf(255, 255, 255)
					}
				val pixel = (rowIndex * size + columnIndex) * 4
				rgba[pixel] = channels[0].toByte()
				rgba[pixel + 1] = channels[1].toByte()
				rgba[pixel + 2] = channels[2].toByte()
				rgba[pixel + 3] = 255.toByte()
			}
		}
		return DecodedImage(rgba, size, size)
	}

	/**
	 * A renderer over an empty model showing the quadrant page as a UV area, with the test grid and palette,
	 * rendering into one frame-sized target.
	 *
	 * @param ViewportCamera camera The view.
	 */
	private inner class UvScene(
		camera: ViewportCamera,
	) {
		private val device = GlRenderDevice()
		private val target = device.createRenderTarget(RenderTargetSpec(viewportSize, viewportSize, TextureFormat.Rgba8, sampled = true))
		val renderer =
			PuppetRenderer(
				PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null),
				PuppetTextures(listOf(quadrantPage(viewportSize)), emptyMap(), premultipliedAlpha = false),
				device,
			)

		init {
			renderer.initGl()
			renderer.setGrid(gridColors, 100f, 1)
			renderer.setMeshOverlayPalette(palette)
			renderer.setCamera(camera)
		}

		/**
		 * Renders the page with [overlay] and [placement] over it and reads the frame back.
		 *
		 * @param DirectMeshOverlay? overlay The overlay, or null for none.
		 * @param PlacementPreview? placement The placement preview, or null for none.
		 * @return RasterImage The frame, top row first.
		 */
		fun render(overlay: DirectMeshOverlay?, placement: PlacementPreview? = null): RasterImage {
			renderer.renderAtlasPage(target, 0, viewportSize, viewportSize, "uv", overlay, placement)
			return device.readPixels(target)
		}
	}
}