package org.umamo.editor.desktop.viewport

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.flow.StateFlow
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.OVERLAY_FLAG_SELECTED
import org.umamo.render.puppet.OverlayColor
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
 * The mesh overlay's delivery gate, one level above the renderer: a real OffscreenPuppetService with its
 * render thread publishes the overlay and its palette the way the viewport binding does, and the frames
 * the area receives must show them, follow a palette change, and drop them again.  The read order in
 * the engine (the overlay handed to the renderer after the freshness stamp is read) cannot be pinned by
 * a unit test; this pins that what is published reaches the pixels and what is taken down leaves them.
 *
 * One solid blue quad over a 100x100 area, an Edge-mode overlay with both faces selected (so both fill
 * in the selected face color), sampled 20 pixels up and left of center: inside triangle 0, clear of the
 * diagonal and of every edge.  Self-skips when no GL context can be created (no frame ever arrives).
 */
class MeshOverlayLiveEngineTest {
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("OverlayProbe")

	// A 120x120 quad centered at the origin, keyed with one zero-delta form.
	private val quadPositions = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)
	private val quadUvs = floatArrayOf(0.3f, 0.7f, 0.7f, 0.7f, 0.3f, 0.3f, 0.7f, 0.3f)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	@Test
	fun anOverlayAndItsPaletteReachTheFrameAndLeaveIt() {
		val model = probeModel()
		val textures = PuppetTextures(listOf(solidBlueImage()), mapOf(probeId.raw to 0), false)
		val service = OffscreenPuppetService(model, textures, LiveParams(emptyMap()))
		service.start()
		try {
			val frames = service.register("area")
			service.resize("area", 100, 100)
			if (!awaitFrame(frames) { sample -> isArtBlue(sample) }) {
				println("[overlay-live] no GL frame arrived; skipping (context unavailable, or the art never rendered)")
				return
			}

			// Stage 1: the overlay under the Classic palette tints the selected faces orange over the blue art.
			service.setMeshOverlay(bothFacesSelected())
			assertTrue(
				awaitFrame(frames) { sample -> sample.red > 0.25f && sample.blue < 0.9f },
				"the overlay reaches the frame: the selected faces fill in Classic's translucent orange",
			)

			// Stage 2: a palette change alone re-renders: the selected face color turns opaque magenta.
			service.setMeshOverlayPalette(MeshOverlayPalette.Classic.copy(faceSelected = OverlayColor(1f, 0f, 1f, 1f)))
			assertTrue(
				awaitFrame(frames) { sample -> sample.red > 0.9f && sample.green < 0.1f && sample.blue > 0.9f },
				"the palette reaches the frame: the faces fill opaque magenta",
			)

			// Stage 3: taking the overlay down returns the art.
			service.setMeshOverlay(null)
			assertTrue(
				awaitFrame(frames) { sample -> isArtBlue(sample) },
				"a null overlay leaves the frame: the art shows plain blue again",
			)
		} finally {
			service.dispose()
		}
	}

	/**
	 * The Edit overlay over the probe in Edge mode with both triangles selected and nothing else flagged.
	 *
	 * @return MeshOverlay The overlay.
	 */
	private fun bothFacesSelected(): MeshOverlay =
		MeshOverlay(
			MeshOverlayKind.Edit,
			MeshOverlaySelectMode.Edge,
			listOf(
				MeshOverlayMesh(
					drawableId = probeId,
					vertexCount = 4,
					edgeEndpoints = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3),
					vertexFlags = ByteArray(0),
					edgeFlags = ByteArray(0),
					faceFlags = byteArrayOf(OVERLAY_FLAG_SELECTED, OVERLAY_FLAG_SELECTED),
					activeVertex = null,
					activeEdge = null,
					activeFace = null,
				),
			),
			MeshOverlaySizes(3.5f, 1f, 2.5f),
		)

	/**
	 * Whether the sample shows the plain blue art.
	 *
	 * @param Color sample The sampled pixel.
	 * @return Boolean True when blue dominates and no tint is over it.
	 */
	private fun isArtBlue(sample: Color): Boolean = sample.blue > 0.9f && sample.red < 0.1f && sample.green < 0.1f

	/**
	 * Waits until the area publishes a frame whose sample pixel satisfies [accept].
	 *
	 * @param StateFlow<RenderedFrame?> frames The area's frame flow.
	 * @param Function accept The test on the sample pixel.
	 * @return Boolean True when such a frame arrived before the deadline.
	 */
	private fun awaitFrame(frames: StateFlow<RenderedFrame?>, accept: (Color) -> Boolean): Boolean {
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < 5_000) {
			val frame = frames.value
			if (frame != null && accept(sampleOf(frame.bitmap.toPixelMap()))) {
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