package org.umamo.editor.desktop.viewport

import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.flow.StateFlow
import org.umamo.render.DecodedImage
import org.umamo.render.FrameOverlays
import org.umamo.render.PuppetTextures
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
import org.umamo.ui.viewport.AreaOverlays
import org.umamo.ui.viewport.GridConfig
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.RenderedFrame
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The per-area render options one level above the renderer: a real OffscreenPuppetService with its render
 * thread holds two 2D areas of one document, each drawing what ITS options ask for, and a change to one
 * area's options repaints that area alone.  The art is hidden so a frame is nothing but the backdrop.
 * Self-skips when no GL context can be created (no frame ever arrives).
 */
class AreaOverlaysLiveEngineTest {
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("OverlaysProbe")

	// A 120x120 quad centered at the origin, keyed with one zero-delta form; hidden for the whole test, it only
	// gives the areas' fit something to frame, which puts the world origin at the frame's center.
	private val quadPositions = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)
	private val quadUvs = floatArrayOf(0.3f, 0.7f, 0.7f, 0.7f, 0.3f, 0.3f, 0.7f, 0.3f)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	@Test
	fun eachAreaDrawsItsOwnOverlaysAndAChangeRepaintsOnlyItsArea() {
		val model = probeModel()
		val textures = PuppetTextures(listOf(solidBlueImage()), mapOf(probeId.raw to 0), false)
		val service = OffscreenPuppetService(model, textures, LiveParams(emptyMap()))
		service.start()
		try {
			service.setShownDrawables(emptySet())
			val leftFrames = service.register("left")
			val rightFrames = service.register("right")
			service.setAreaOverlays("right", AreaOverlays(GridConfig(), FrameOverlays(gridLines = false, axes = false, meshOverlay = true)))
			service.resize("left", 100, 100)
			service.resize("right", 100, 100)
			if (!awaitFrame(leftFrames) { pixels -> !isUniform(pixels) }) {
				println("[area-overlays-live] no GL frame arrived; skipping (context unavailable)")
				return
			}

			assertTrue(awaitFrame(rightFrames) { pixels -> isUniform(pixels) }, "no lines and no axes: the right area's frame is one flat color")
			val rightFrame = rightFrames.value
			assertTrue(
				awaitFrame(leftFrames) { pixels -> hasAxisRedNearTheCenterRow(pixels) },
				"the left area draws the red X axis through its center; center column: ${leftFrames.value?.let { frame -> centerColumnOf(frame.bitmap.toPixelMap()) }}",
			)

			service.setAreaOverlays("left", AreaOverlays(GridConfig(), FrameOverlays(gridLines = true, axes = false, meshOverlay = true)))

			assertTrue(awaitFrame(leftFrames) { pixels -> !hasAxisRedNearTheCenterRow(pixels) && !isUniform(pixels) }, "axes off repaints the left area without its axis, lines kept")
			assertSame(rightFrame, rightFrames.value, "and the right area, untouched, kept its frame")
		} finally {
			service.dispose()
		}
	}

	/**
	 * Whether every pixel is the top-left one's color.
	 *
	 * @param PixelMap pixels The frame.
	 * @return Boolean True when the frame is one flat color.
	 */
	private fun isUniform(pixels: PixelMap): Boolean {
		val first = pixels[0, 0]
		for (y in 0 until pixels.height) {
			for (x in 0 until pixels.width) {
				if (pixels[x, y] != first) {
					return false
				}
			}
		}
		return true
	}

	/**
	 * Whether a red-dominant pixel sits right of center within three rows of the center row: the X axis line,
	 * which the fit puts through the frame's middle.  Dominance rather than an absolute red, because the
	 * engine supersamples and box-downscales, so the one-pixel line comes back blended with the backdrop;
	 * the grid's own lines are grey, with no channel ahead of the others.
	 *
	 * @param PixelMap pixels The frame.
	 * @return Boolean True when the axis is there.
	 */
	private fun hasAxisRedNearTheCenterRow(pixels: PixelMap): Boolean {
		val centerRow = pixels.height / 2
		val column = pixels.width / 2 + 10
		for (row in centerRow - 3..centerRow + 3) {
			val sample = pixels[column, row]
			if (sample.red - maxOf(sample.green, sample.blue) > 0.12f) {
				return true
			}
		}
		return false
	}

	/**
	 * The frame's center column, top to bottom, as rounded RGB triples: what a failed axis check prints so
	 * the failure says where the line went.
	 *
	 * @param PixelMap pixels The frame.
	 * @return String One triple per row.
	 */
	private fun centerColumnOf(pixels: PixelMap): String =
		(0 until pixels.height).joinToString(" ") { row ->
			val sample = pixels[pixels.width / 2 + 10, row]
			"%d:(%.2f,%.2f,%.2f)".format(row, sample.red, sample.green, sample.blue)
		}

	/**
	 * Waits until the area publishes a frame [accept] takes.
	 *
	 * @param StateFlow<RenderedFrame?> frames The area's frame flow.
	 * @param Function accept The test on the frame's pixels.
	 * @return Boolean True when such a frame arrived before the deadline.
	 */
	private fun awaitFrame(frames: StateFlow<RenderedFrame?>, accept: (PixelMap) -> Boolean): Boolean {
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < 5_000) {
			val frame = frames.value
			if (frame != null && accept(frame.bitmap.toPixelMap())) {
				return true
			}
			Thread.sleep(20)
		}
		return false
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
	 * The one-quad model on a single 16x16 page.
	 *
	 * @return PuppetModel The model.
	 */
	private fun probeModel(): PuppetModel {
		val drawable =
			Drawable(
				id = probeId,
				name = probeId.raw,
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh.withLocalEqualToCanvas(quadPositions, quadUvs, quadIndices),
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(paramA, floatArrayOf(0f))),
						listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(quadPositions.size)))),
					),
			)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
			atlas = PuppetAtlas(pages = listOf(AtlasPage(16, 16))),
		)
	}
}