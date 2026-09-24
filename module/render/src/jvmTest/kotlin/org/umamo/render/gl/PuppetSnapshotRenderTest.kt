package org.umamo.render.gl

import org.umamo.format.moc3.Moc3
import org.umamo.format.raster.RasterImage
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.FrameBackdrop
import org.umamo.render.GridColors
import org.umamo.render.PuppetTextures
import org.umamo.render.ViewportCamera
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.TextureFormat
import org.umamo.render.puppet.PuppetRenderer
import org.umamo.render.puppet.fallbackColorFor
import org.umamo.render.restMeshesToCanvasSpace
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
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [PuppetRenderer.renderSnapshot]: the image capture behind Export Image and the saved thumbnail.
 *
 * One flat-color quad sits in the upper-right quadrant of a 200 x 200 capture at a 1:1 camera on the
 * origin, so the quad covers image columns 120..180 and rows 20..80, the world axes cross at column and
 * row 100, and the top-left corner is empty canvas.  The quad is untextured, so it draws its fallback
 * color at 0.85 alpha - a known premultiplied value to check the read-back against.
 *
 * Self-skips in a display-less environment (no GL context), like [SelectionTintTest].
 */
class PuppetSnapshotRenderTest {
	private val imageSize = 200
	private val paramA = ParameterId("A")
	private val quadId = DrawableId("Quad")

	/**
	 * One flat quad spanning world x and z in [low, high].  Mesh positions are the model's own y-down space,
	 * which the deform flips to world z-up, so the quad's mesh y runs from -high to -low.
	 *
	 * @param DrawableId id      The drawable's id.
	 * @param Float      low     The lower world x and z.
	 * @param Float      high    The upper world x and z.
	 * @param Float      opacity The drawable's static opacity.
	 * @return Drawable The quad.
	 */
	private fun quad(id: DrawableId, low: Float, high: Float, opacity: Float = 1f): Drawable {
		val positions = floatArrayOf(low, -low, high, -low, low, -high, high, -high)
		return Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2, 1, 3, 2)),
			// A single zero-delta keyform so the drawable is keyed; the base mesh alone drives its shape.
			geometryGrid = KeyformGrid(listOf(KeyformAxis(paramA, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size))))),
			opacity = opacity,
		)
	}

	/**
	 * A model over [drawables], in that order at the root.
	 *
	 * @param List<Drawable> drawables The drawables.
	 * @return PuppetModel The model.
	 */
	private fun modelOf(drawables: List<Drawable>): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
		)

	/**
	 * The one-quad model: world x and z in [20, 80].
	 *
	 * @return PuppetModel The model.
	 */
	private fun quadModel(): PuppetModel = modelOf(listOf(quad(quadId, 20f, 80f)))

	/**
	 * A renderer over [quadModel], posed at rest, with the quad selected and active and the world axes on:
	 * the viewport's state, which a capture must leave out.
	 *
	 * @param GlRenderDevice device The device to render through.
	 * @return PuppetRenderer The renderer.
	 */
	private fun viewportRenderer(device: GlRenderDevice): PuppetRenderer {
		val renderer = PuppetRenderer(quadModel(), PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), device)
		renderer.initGl()
		renderer.setWorldAxesVisible(true)
		renderer.setShownDrawables(setOf(quadId))
		renderer.setSelection(setOf(quadId))
		renderer.setActiveSelection(quadId)
		renderer.setSelectionHighlightColor(0f, 0f, 1f)
		renderer.setActiveSelectionHighlightColor(0f, 0f, 1f)
		renderer.setPose(emptyMap())
		return renderer
	}

	/**
	 * One pixel's channels, 0..255.
	 *
	 * @param RasterImage image  The top-first image.
	 * @param Int         column The pixel column.
	 * @param Int         row    The pixel row from the top.
	 * @return List<Int> Red, green, blue, and alpha.
	 */
	private fun pixelAt(image: RasterImage, column: Int, row: Int): List<Int> {
		val offset = (row * image.width + column) * 4
		return (0 until 4).map { channel -> image.rgba[offset + channel].toInt() and 0xFF }
	}

	/**
	 * Asserts two channel lists agree within a tolerance.
	 *
	 * @param List<Int> expected  The expected channels.
	 * @param List<Int> actual    The read channels.
	 * @param Int       tolerance The largest allowed difference per channel.
	 * @param String    label     What is being compared, for the failure message.
	 */
	private fun assertClose(expected: List<Int>, actual: List<Int>, tolerance: Int, label: String) {
		val worst = expected.zip(actual).maxOf { (want, got) -> abs(want - got) }
		assertTrue(worst <= tolerance, "$label: expected $expected, read $actual")
	}

	/** A transparent capture leaves empty canvas clear, draws neither axis, and draws the selected quad untinted. */
	@Test
	fun transparentCaptureLeavesTheCanvasClearAndDrawsNoTintOrAxes() {
		requireHeadlessGl("[snapshot-transparent]")
		val renderer = viewportRenderer(GlRenderDevice())
		val image = assertNotNull(renderer.renderSnapshot(ViewportCamera(0f, 0f, 1f), imageSize, imageSize, FrameBackdrop.Transparent))
		assertEquals(imageSize to imageSize, image.width to image.height)

		assertEquals(listOf(0, 0, 0, 0), pixelAt(image, 10, 10), "empty canvas is fully transparent")
		// Column 100 is the world z axis and row 100 the x axis; neither is drawn without the grid.
		assertEquals(listOf(0, 0, 0, 0), pixelAt(image, 100, 150), "the z axis is not drawn")
		assertEquals(listOf(0, 0, 0, 0), pixelAt(image, 50, 100), "the x axis is not drawn")

		// Premultiplied: the fallback color scaled by its 0.85 alpha, untinted although the quad is selected.
		val fallback = fallbackColorFor(quadId.raw)
		val alpha = 0.85f
		val expected = listOf(fallback[0] * alpha, fallback[1] * alpha, fallback[2] * alpha, alpha).map { channel -> (channel * 255f).roundToInt() }
		assertClose(expected, pixelAt(image, 150, 50), tolerance = 2, label = "the quad, premultiplied and untinted")
	}

	/** A solid capture fills the empty canvas with its color and blends the translucent quad over it. */
	@Test
	fun solidCaptureFillsTheCanvasAndBlendsTheQuadOverIt() {
		requireHeadlessGl("[snapshot-solid]")
		val renderer = viewportRenderer(GlRenderDevice())
		val image = assertNotNull(renderer.renderSnapshot(ViewportCamera(0f, 0f, 1f), imageSize, imageSize, FrameBackdrop.Clear(1f, 0.5f, 0f, 1f)))

		assertClose(listOf(255, 128, 0, 255), pixelAt(image, 10, 10), tolerance = 1, label = "the canvas takes the fill")
		val fallback = fallbackColorFor(quadId.raw)
		val alpha = 0.85f
		val background = listOf(1f, 0.5f, 0f)
		val expected = (0 until 3).map { channel -> ((fallback[channel] * alpha + background[channel] * (1f - alpha)) * 255f).roundToInt() } + 255
		assertClose(expected, pixelAt(image, 150, 50), tolerance = 2, label = "the quad over the fill")
	}

	/** A capture hands back the camera, the selection tint, and the side-target capacity the viewport had. */
	@Test
	fun captureRestoresTheViewportState() {
		requireHeadlessGl("[snapshot-restore]")
		val device = GlRenderDevice()
		val renderer = viewportRenderer(device)
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		val target = device.createRenderTarget(RenderTargetSpec(imageSize, imageSize, TextureFormat.Rgba8, sampled = true))
		renderer.render(target, imageSize, imageSize)
		val capacityBefore = renderer.sideTargetCapacity()

		// A capture at another camera and a far larger size: its side targets grow past the viewport's.
		renderer.renderSnapshot(ViewportCamera(500f, 500f, 3f), 1200, 900, FrameBackdrop.Transparent)
		val capacityAfter = renderer.sideTargetCapacity()
		assertTrue(
			capacityAfter.first <= capacityBefore.first && capacityAfter.second <= capacityBefore.second,
			"a capture that grew the side targets releases them (before=$capacityBefore after=$capacityAfter)",
		)

		// The viewport render afterwards is the one it was: same camera (the quad where it was) and still
		// tinted toward the selection blue.
		renderer.render(target, imageSize, imageSize)
		val viewport = device.readPixels(target)
		val quadPixel = pixelAt(viewport, 150, 50)
		assertTrue(quadPixel[2] > quadPixel[0] && quadPixel[2] > quadPixel[1], "the quad is still selection-tinted at its old place (read $quadPixel)")
	}

	/** A capture told to stop between tiles returns nothing and still hands the viewport its state back. */
	@Test
	fun anAbandonedCaptureReturnsNothingAndRestoresTheViewportState() {
		requireHeadlessGl("[snapshot-abandon]")
		val device = GlRenderDevice()
		val renderer = viewportRenderer(device)
		renderer.setCamera(ViewportCamera(0f, 0f, 1f))
		val target = device.createRenderTarget(RenderTargetSpec(imageSize, imageSize, TextureFormat.Rgba8, sampled = true))
		renderer.render(target, imageSize, imageSize)
		val capacityBefore = renderer.sideTargetCapacity()

		// Nine tiles; the host lets the first one through and then shuts down.
		var tilesAllowed = 1
		val image =
			renderer.renderSnapshot(ViewportCamera(500f, 500f, 3f), 1200, 900, FrameBackdrop.Transparent, tileEdge = 400) {
				tilesAllowed-- > 0
			}
		assertNull(image, "an abandoned capture has no image")
		val capacityAfter = renderer.sideTargetCapacity()
		assertTrue(
			capacityAfter.first <= capacityBefore.first && capacityAfter.second <= capacityBefore.second,
			"an abandoned capture releases the side targets it grew (before=$capacityBefore after=$capacityAfter)",
		)

		renderer.render(target, imageSize, imageSize)
		val quadPixel = pixelAt(device.readPixels(target), 150, 50)
		assertTrue(quadPixel[2] > quadPixel[0] && quadPixel[2] > quadPixel[1], "the quad is still selection-tinted at its old place (read $quadPixel)")
	}

	/**
	 * A shown drawable posed at zero opacity draws nothing, so the capture's framing leaves it out.  The CPU
	 * deform needs no context, so this runs everywhere.
	 */
	@Test
	fun posedContentBoundsLeaveOutAZeroOpacityDrawable() {
		val ghostId = DrawableId("Ghost")
		val renderer =
			PuppetRenderer(
				modelOf(listOf(quad(quadId, 20f, 80f), quad(ghostId, 300f, 400f, opacity = 0f))),
				PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false),
				GlRenderDevice(),
			)
		renderer.setPose(emptyMap())
		val bounds = assertNotNull(renderer.posedContentBounds(setOf(quadId, ghostId)))
		assertEquals(listOf(20f, 20f, 60f, 60f), listOf(bounds.minX, bounds.minY, bounds.width, bounds.height), "only the drawn quad is framed")
		assertNull(renderer.posedContentBounds(setOf(ghostId)), "a pose that draws nothing has nothing to frame")
	}

	/** A capture split into partial tiles matches the same capture in one piece, grid lines included. */
	@Test
	fun aTiledCaptureMatchesAWholeOne() {
		requireHeadlessGl("[snapshot-tiles]")
		val renderer = viewportRenderer(GlRenderDevice())
		// A contrasting grid, so a seam that shifted anything by a pixel would show on its lines too.
		renderer.setGrid(GridColors(0f, 0f, 0f, 1f, 1f, 1f, 0.5f, 0.5f, 0.5f), 25f, subdivisions = 5)
		// Not a multiple of the tile edge in either direction, and off-center, so edge tiles are partial.
		val camera = ViewportCamera(13f, -7f, 1.37f)
		val whole = assertNotNull(renderer.renderSnapshot(camera, 211, 157, FrameBackdrop.Grid))
		val tiled = assertNotNull(renderer.renderSnapshot(camera, 211, 157, FrameBackdrop.Grid, tileEdge = 64))
		var worst = 0
		var worstAt = 0
		for (byteIndex in whole.rgba.indices) {
			val difference = abs((whole.rgba[byteIndex].toInt() and 0xFF) - (tiled.rgba[byteIndex].toInt() and 0xFF))
			if (difference > worst) {
				worst = difference
				worstAt = byteIndex / 4
			}
		}
		assertTrue(worst <= 2, "tiles meet exactly: worst channel difference $worst at pixel (${worstAt % 211}, ${worstAt / 211})")
	}

	/**
	 * The same seam check on a real rig, where tiles cut through masks, isolated groups, and extended-blend
	 * composites - each of which renders into screen-space side targets and scissors to its own bounds per
	 * tile.  Model A carries all three.  Corpus- and GL-gated: skips without Model A or a context.
	 */
	@Test
	fun aTiledCaptureOfACorpusRigMatchesAWholeOne() {
		val mocFile =
			System.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { directory -> directory.isDirectory }
				?.walkTopDown()
				?.firstOrNull { candidate -> candidate.name == "modelA.moc3" }
		if (mocFile == null) {
			println("modelA.moc3 not present; skipping the corpus tiled-capture check")
			return
		}
		requireHeadlessGl("[snapshot-corpus-tiles]")
		val puppet = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(Moc3.read(mocFile.readBytes()), null))
		val renderer = PuppetRenderer(puppet, PuppetTextures(emptyList(), emptyMap(), premultipliedAlpha = false), GlRenderDevice())
		renderer.initGl()
		renderer.setPose(emptyMap())
		val width = 700
		val height = 900
		val camera = ViewportCamera.fit(renderer.contentBounds(), width, height)
		val whole = assertNotNull(renderer.renderSnapshot(camera, width, height, FrameBackdrop.Transparent))
		val tiled = assertNotNull(renderer.renderSnapshot(camera, width, height, FrameBackdrop.Transparent, tileEdge = 128))

		val coveredPixels = (3 until whole.rgba.size step 4).count { alphaIndex -> whole.rgba[alphaIndex].toInt() != 0 }
		assertTrue(coveredPixels > width * height / 20, "the rig covers a real part of the capture ($coveredPixels pixels)")
		var worst = 0
		var worstAt = 0
		for (byteIndex in whole.rgba.indices) {
			val difference = abs((whole.rgba[byteIndex].toInt() and 0xFF) - (tiled.rgba[byteIndex].toInt() and 0xFF))
			if (difference > worst) {
				worst = difference
				worstAt = byteIndex / 4
			}
		}
		// A composite boundary re-quantizes to 8 bits, the same tolerance the composite parity gate allows.
		assertTrue(worst <= 3, "tiles meet on the rig: worst channel difference $worst at pixel (${worstAt % width}, ${worstAt / width})")
	}
}