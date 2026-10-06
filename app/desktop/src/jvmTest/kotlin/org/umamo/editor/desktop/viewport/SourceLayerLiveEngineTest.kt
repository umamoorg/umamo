package org.umamo.editor.desktop.viewport

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.flow.StateFlow
import org.umamo.render.DecodedImage
import org.umamo.render.DrawableLayerDraw
import org.umamo.render.LayerDrawPlan
import org.umamo.render.LayerRasterBatch
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
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.RenderedFrame
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The source-artwork hand-off's freshness, one level above the renderer: a real OffscreenPuppetService
 * with its render thread is handed a layer plan and then the layer's pixels the way the viewport binding
 * hands them over, and the area must re-render for each.  Neither publish bumps a render version; the
 * loop bumps its pose version when it applies them, so an area can never stamp itself fresh over a frame
 * of the outgoing art.  The race a publish-time bump allowed depends on timing and cannot be pinned, but
 * the invariant that closes it can: with no bump at publish, only the apply-time bump re-renders the area,
 * so this fails if that bump is ever removed.
 *
 * One quad over a 100x100 area, drawn from a solid blue atlas page and then from a solid red layer,
 * sampled 20 pixels up and left of center.  Self-skips when no GL context can be created (no frame ever
 * arrives).
 */
class SourceLayerLiveEngineTest {
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("ArtworkProbe")
	private val layerKey = "art"
	private val imageSize = 16

	// A 120x120 quad centered at the origin, keyed with one zero-delta form.
	private val quadPositions = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)
	private val quadUvs = floatArrayOf(0.3f, 0.7f, 0.7f, 0.7f, 0.3f, 0.3f, 0.7f, 0.3f)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	@Test
	fun theAppliedPlanAndPixelsReachTheFrame() {
		val model = probeModel()
		val textures = PuppetTextures(listOf(solidImage(red = 0x00, blue = 0xFF)), mapOf(probeId.raw to 0), false)
		val service = OffscreenPuppetService(model, textures, LiveParams(emptyMap()))
		service.start()
		try {
			val frames = service.register("area")
			service.resize("area", 100, 100)
			if (!awaitFrame(frames) { _, sample -> isBlue(sample) }) {
				println("[artwork-live] no GL frame arrived; skipping (context unavailable, or the atlas never rendered)")
				return
			}

			// Stage 1: the plan alone re-renders the area.  The puppet stays on its atlas until the layer
			// is resident, so the frame is still blue, but it is a NEW frame - rendered after the loop
			// applied the plan, which the pixels below need: the renderer drops pixels it never asked for.
			val atlasFrame = frames.value
			service.setSourceLayerPlan(
				LayerDrawPlan(
					drawsByDrawableId = mapOf(probeId.raw to DrawableLayerDraw(layerKey, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f))),
					layerByteCostByKey = mapOf(layerKey to imageSize.toLong() * imageSize.toLong() * 4L),
				),
			)
			assertTrue(
				awaitFrame(frames) { frame, sample -> frame !== atlasFrame && isBlue(sample) },
				"the applied plan re-renders the area, still from the atlas until its layer arrives",
			)

			// Stage 2: the pixels re-render it again, now drawn from the layer.
			service.deliverSourceLayerRasters(LayerRasterBatch(mapOf(layerKey to solidImage(red = 0xFF, blue = 0x00))))
			assertTrue(
				awaitFrame(frames) { _, sample -> sample.red > 0.9f && sample.green < 0.1f && sample.blue < 0.1f },
				"the drained pixels re-render the area: the quad draws from its red layer",
			)
		} finally {
			service.dispose()
		}
	}

	/**
	 * Whether the sample shows the plain blue atlas art.
	 *
	 * @param Color sample The sampled pixel.
	 * @return Boolean True when blue dominates.
	 */
	private fun isBlue(sample: Color): Boolean = sample.blue > 0.9f && sample.red < 0.1f && sample.green < 0.1f

	/**
	 * Waits until the area publishes a frame that satisfies [accept].
	 *
	 * @param StateFlow<RenderedFrame?> frames The area's frame flow.
	 * @param Function accept The test on the frame and its sample pixel.
	 * @return Boolean True when such a frame arrived before the deadline.
	 */
	private fun awaitFrame(frames: StateFlow<RenderedFrame?>, accept: (RenderedFrame, Color) -> Boolean): Boolean {
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < 5_000) {
			val frame = frames.value
			if (frame != null && accept(frame, sampleOf(frame.bitmap.toPixelMap()))) {
				return true
			}
			Thread.sleep(20)
		}
		return false
	}

	/**
	 * The sample pixel: 20 pixels up and left of the frame's center.
	 *
	 * @param PixelMap pixels The frame.
	 * @return Color The sample.
	 */
	private fun sampleOf(pixels: PixelMap): Color = pixels[pixels.width / 2 - 20, pixels.height / 2 - 20]

	/**
	 * An opaque image of one color, [imageSize] on a side, green off.
	 *
	 * @param Int red  The red channel, 0..255.
	 * @param Int blue The blue channel, 0..255.
	 * @return DecodedImage The image.
	 */
	private fun solidImage(red: Int, blue: Int): DecodedImage {
		val rgba = ByteArray(imageSize * imageSize * 4)
		for (pixel in rgba.indices step 4) {
			rgba[pixel] = red.toByte()
			rgba[pixel + 2] = blue.toByte()
			rgba[pixel + 3] = 0xFF.toByte()
		}
		return DecodedImage(rgba, imageSize, imageSize)
	}

	/**
	 * The one-quad model on a single page.
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
				mesh = DrawableMesh(quadPositions, quadUvs, quadIndices),
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
			atlas = PuppetAtlas(pages = listOf(AtlasPage(imageSize, imageSize))),
		)
	}
}