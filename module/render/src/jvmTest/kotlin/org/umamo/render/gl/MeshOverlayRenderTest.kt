package org.umamo.render.gl

import org.umamo.format.raster.RasterImage
import org.umamo.render.FrameOverlays
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTarget
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the mesh overlay on the pixels through the renderer's public API: the dots, edges, and fills land
 * where the art's vertices are, in the palette's colors, by the select mode's rules, blended
 * premultiplied over the art; the sizes follow the render scale; and clearing the overlay leaves a frame
 * byte for byte identical to a renderer that never had one.
 *
 * One untextured band quad over a black grid behind the 1:1 camera, so mesh (x, y) lands at column 32 + x
 * and row 32 + y of the 64-pixel frame: the corners sit at (16, 40), (48, 40), (16, 62), (48, 62), with
 * triangle 0 above the diagonal from (48, 40) to (16, 62) and triangle 1 below it.  Skips without a GL
 * context.
 */
class MeshOverlayRenderTest {
	private val viewportSize = 64
	private val paramA = ParameterId("A")
	private val quadId = DrawableId("band")
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)
	private val quadEdges = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3)
	private val sizes = MeshOverlaySizes(vertexDotRadiusPx = 4f, edgeWidthPx = 2f, faceDotRadiusPx = 3f)
	private val blackGrid = GridColors(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
	private val red = OverlayColor(1f, 0f, 0f, 1f)
	private val green = OverlayColor(0f, 1f, 0f, 1f)
	private val blue = OverlayColor(0f, 0f, 1f, 1f)
	private val magenta = OverlayColor(1f, 0f, 1f, 1f)
	private val halfGray = OverlayColor(0.5f, 0.5f, 0.5f, 0.5f)
	private val halfYellow = OverlayColor(1f, 1f, 0f, 0.5f)

	// Opaque dot and edge colors so a covered pixel reads the color itself; translucent face fills so the
	// fill test pins the premultiplied blend over the art.
	private val palette =
		MeshOverlayPalette(
			vertexIdle = red,
			vertexSelected = green,
			vertexActive = blue,
			edgeIdle = magenta,
			edgeSelected = green,
			edgeActive = blue,
			faceIdle = halfGray,
			faceSelected = halfYellow,
			faceActive = blue,
		)

	@Test
	fun vertexModeDrawsDotsAndEdgesOverTheArt() {
		requireHeadlessGl("[overlay-render]")
		val live = LiveOverlayRenderer()
		val reference = live.render()
		live.renderer.setMeshOverlay(overlay(MeshOverlaySelectMode.Vertex, vertexFlags = byteArrayOf(0, 0, 0, 2), activeVertex = 3))

		val frame = live.render()

		assertEquals(listOf(0, 0, 255, 255), frame.at(48, 62), "the active vertex's dot, in the active color")
		assertEquals(listOf(255, 0, 0, 255), frame.at(16, 40), "an idle vertex's dot")
		assertEquals(listOf(255, 0, 255, 255), frame.at(32, 40), "the band of an idle edge, at its midpoint")
		assertEquals(reference.at(22, 44), frame.at(22, 44), "triangle 0's interior keeps the art: outside Face mode an idle face has no fill")
		assertEquals(reference.at(42, 58), frame.at(42, 58), "triangle 1's interior keeps the art")
	}

	@Test
	fun faceModeFillsEveryFaceAndDotsTheCentroids() {
		requireHeadlessGl("[overlay-render]")
		val live = LiveOverlayRenderer()
		val reference = live.render()
		live.renderer.setMeshOverlay(overlay(MeshOverlaySelectMode.Face, faceFlags = byteArrayOf(0, 2), activeFace = 1))

		val frame = live.render()

		assertClose(blend(reference.at(22, 44), halfGray), frame.at(22, 44), 2, "the idle triangle fills in the idle color over the art")
		assertClose(blend(reference.at(42, 58), halfYellow), frame.at(42, 58), 2, "the active triangle fills as selected, since the active color belongs to its dot")
		assertClose(listOf(128, 128, 128, 255), frame.at(26, 47), 2, "triangle 0's centroid dot is the idle color made opaque")
		assertEquals(listOf(0, 0, 255, 255), frame.at(37, 54), "triangle 1's centroid dot is the active color")
	}

	@Test
	fun theWireframeFadesWithTheFramesOpacity() {
		requireHeadlessGl("[overlay-render]")
		val live = LiveOverlayRenderer()
		val reference = live.render()
		live.renderer.setMeshOverlay(overlay(MeshOverlaySelectMode.Vertex, kind = MeshOverlayKind.ObjectWireframe))

		val full = live.render()
		val faded = live.render(overlays = FrameOverlays(wireframeOpacity = 0.5f))
		val gone = live.render(overlays = FrameOverlays(wireframeOpacity = 0f))

		assertEquals(listOf(255, 0, 255, 255), full.at(32, 40), "at full opacity the edge band is the opaque idle edge color")
		assertClose(blend(reference.at(32, 40), magenta.copy(alpha = 0.5f)), faded.at(32, 40), 2, "at half opacity the band is the edge color at half alpha over the art")
		assertEquals(reference.at(32, 40), gone.at(32, 40), "at zero the art shows through untouched")
	}

	@Test
	fun theDotsFollowTheRenderScaleAndAClearedOverlayLeavesNoTrace() {
		requireHeadlessGl("[overlay-render]")
		val live = LiveOverlayRenderer()
		val vertexOverlay = overlay(MeshOverlaySelectMode.Vertex)

		// Vertex 0 sits at (16, 40); the dot's four-pixel radius reaches three pixels above it, not six.
		val reference = live.render()
		live.renderer.setMeshOverlay(vertexOverlay)
		val atScaleOne = live.render()
		assertEquals(listOf(255, 0, 0, 255), atScaleOne.at(16, 37), "three pixels above the corner is inside the dot")
		assertEquals(reference.at(16, 34), atScaleOne.at(16, 34), "six pixels above it is past the dot and its fade")

		// A supersampled frame: twice the pixels through a camera at twice the zoom, as the engine renders
		// it, so the corner lands at (32, 80) and the dot must reach eight framebuffer pixels to keep its
		// on-screen size: six pixels above the corner is now inside it.
		val doubled = viewportSize * 2
		live.renderer.setRenderScale(2f)
		live.renderer.setCamera(ViewportCamera(0f, 0f, 2f))
		live.renderer.setMeshOverlay(null)
		val referenceDoubled = live.render(size = doubled)
		live.renderer.setMeshOverlay(vertexOverlay)
		val atScaleTwo = live.render(size = doubled)
		assertEquals(listOf(255, 0, 0, 255), atScaleTwo.at(32, 74, width = doubled), "six framebuffer pixels above the corner is inside the doubled dot")
		assertEquals(referenceDoubled.at(32, 68, width = doubled), atScaleTwo.at(32, 68, width = doubled), "twelve is past it")

		live.renderer.setRenderScale(1f)
		live.renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		live.renderer.setMeshOverlay(null)
		val cleared = live.render()
		val fresh = LiveOverlayRenderer().render()
		assertContentEquals(fresh.rgba, cleared.rgba, "with the overlay cleared the frame is the frame of a renderer that never had one")
	}

	/**
	 * An Edit overlay over the quad with the given flags and actives.
	 *
	 * @param MeshOverlaySelectMode selectMode The select mode.
	 * @param ByteArray vertexFlags The vertex flags.
	 * @param ByteArray faceFlags The face flags.
	 * @param Int? activeVertex The active vertex, or null.
	 * @param Int? activeFace The active triangle, or null.
	 * @param MeshOverlayKind kind The overlay kind; the Edit cage by default.
	 * @return MeshOverlay The overlay.
	 */
	private fun overlay(
		selectMode: MeshOverlaySelectMode,
		vertexFlags: ByteArray = ByteArray(4),
		faceFlags: ByteArray = ByteArray(2),
		activeVertex: Int? = null,
		activeFace: Int? = null,
		kind: MeshOverlayKind = MeshOverlayKind.Edit,
	): MeshOverlay =
		MeshOverlay(
			kind,
			selectMode,
			listOf(MeshOverlayMesh(quadId, 4, quadEdges, vertexFlags, ByteArray(5), faceFlags, activeVertex, null, activeFace)),
			sizes,
		)

	/**
	 * One pixel's channels, top row first.
	 *
	 * @param Int column The column.
	 * @param Int row The row from the top.
	 * @param Int width The frame width in pixels.
	 * @return List<Int> Red, green, blue, alpha in 0..255.
	 */
	private fun RasterImage.at(column: Int, row: Int, width: Int = viewportSize): List<Int> {
		val offset = (row * width + column) * 4
		return (0 until 4).map { channelIndex -> rgba[offset + channelIndex].toInt() and 0xFF }
	}

	/**
	 * The premultiplied frame pixel a straight-alpha color leaves over [under]: ONE / ONE_MINUS_SRC_ALPHA
	 * with the color premultiplied in the shader.
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
	 * A renderer over the band quad behind a black grid and the 1:1 camera, posed at rest, with the test
	 * palette, rendering into a target of the frame's size on demand.
	 */
	private inner class LiveOverlayRenderer {
		private val device = GlRenderDevice()
		val renderer = PuppetRenderer(bandModel(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		private var target: RenderTarget? = null
		private var targetSize = 0

		init {
			renderer.initGl()
			renderer.setGrid(blackGrid, 100f, 10)
			renderer.setCamera(ViewportCamera(0f, 0f, 1f))
			renderer.setMeshOverlayPalette(palette)
			renderer.setPose(emptyMap())
		}

		/**
		 * Renders one frame at the given square size and reads it back.
		 *
		 * @param Int size The frame's edge in pixels.
		 * @param FrameOverlays overlays What the frame draws beyond the backdrop; everything by default.
		 * @return RasterImage The frame, top row first.
		 */
		fun render(size: Int = viewportSize, overlays: FrameOverlays = FrameOverlays()): RasterImage {
			val frameTarget = targetOf(size)
			renderer.render(frameTarget, size, size, overlays = overlays)
			return device.readPixels(frameTarget)
		}

		/**
		 * The target of the given size, allocated on the first ask and replaced when the size changes.
		 *
		 * @param Int size The edge in pixels.
		 * @return RenderTarget The target.
		 */
		private fun targetOf(size: Int): RenderTarget {
			val current = target
			if (current != null && targetSize == size) {
				return current
			}
			current?.let { stale -> device.destroyRenderTarget(stale) }
			val fresh = device.createRenderTarget(RenderTargetSpec(size, size, TextureFormat.Rgba8, sampled = true))
			target = fresh
			targetSize = size
			return fresh
		}
	}

	/**
	 * The one-quad model: an untextured band keyed with a zero-delta cell, so it draws its fallback color.
	 *
	 * @return PuppetModel The model.
	 */
	private fun bandModel(): PuppetModel {
		val positions = floatArrayOf(-16f, 8f, 16f, 8f, -16f, 30f, 16f, 30f)
		val band =
			Drawable(
				id = quadId,
				name = quadId.raw,
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh.withLocalEqualToCanvas(positions, FloatArray(positions.size), quadIndices),
				geometryGrid = KeyformGrid(listOf(KeyformAxis(paramA, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size))))),
			)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(band),
			rootChildren = listOf(OrgChild.Drawable(quadId)),
			rootPartId = null,
		)
	}
}