package org.umamo.editor.desktop.viewport

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.flow.StateFlow
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.render.puppet.DirectMeshOverlay
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.render.puppet.OVERLAY_FLAG_SELECTED
import org.umamo.render.puppet.OverlayColor
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.RenderedFrame
import org.umamo.ui.viewport.UvSceneContent
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A UV area's mesh overlay, one level above the renderer: a real OffscreenPuppetService with its render
 * thread publishes the overlay on the area's content the way the UV editor does, and the frames the area
 * receives must show it, follow a palette change (which the area watches through the atlas render
 * version), and drop it again when the content carries none.
 *
 * A solid blue 16x16 page in a 100x100 UV area, fit with a margin, under an Edge-mode overlay of one quad
 * over the whole page with both faces selected, so both fill in the selected face color.  The sample sits
 * 20 pixels up and right of center: inside the upper-right triangle, clear of the diagonal from the
 * bottom-right corner to the top-left one and of every edge.  Self-skips when no GL context can be
 * created (no frame ever arrives).
 */
class UvOverlayLiveEngineTest {
	private val pageId = DrawableId("PageQuad")

	@Test
	fun anOverlayOnTheContentReachesTheUvFrameFollowsThePaletteAndLeaves() {
		val model = PuppetModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, atlas = PuppetAtlas(pages = listOf(AtlasPage(16, 16))))
		val service = OffscreenPuppetService(model, PuppetTextures(listOf(solidBlueImage()), emptyMap(), false), LiveParams(emptyMap()))
		service.start()
		try {
			val frames = service.registerUvScene("uv", UvSceneContent.AtlasPage(0), null)
			service.resize("uv", 100, 100)
			if (!awaitFrame(frames) { sample -> isPageBlue(sample) }) {
				println("[uv-overlay-live] no GL frame arrived; skipping (context unavailable, or the page never rendered)")
				return
			}

			// Stage 1: the overlay under the Classic palette tints the selected faces orange over the page.
			service.setUvSceneContent("uv", UvSceneContent.AtlasPage(0, bothFacesSelected()), null)
			assertTrue(
				awaitFrame(frames) { sample -> sample.red > 0.25f && sample.blue < 0.9f },
				"the overlay reaches the UV frame: the selected faces fill in Classic's translucent orange",
			)

			// Stage 2: a palette change alone re-renders the UV area: the selected face color turns opaque magenta.
			service.setMeshOverlayPalette(MeshOverlayPalette.Classic.copy(faceSelected = OverlayColor(1f, 0f, 1f, 1f)))
			assertTrue(
				awaitFrame(frames) { sample -> sample.red > 0.9f && sample.green < 0.1f && sample.blue > 0.9f },
				"the palette reaches the UV frame: the faces fill opaque magenta",
			)

			// Stage 3: content with no overlay returns the plain page.
			service.setUvSceneContent("uv", UvSceneContent.AtlasPage(0), null)
			assertTrue(
				awaitFrame(frames) { sample -> isPageBlue(sample) },
				"content without an overlay leaves the frame: the page shows plain blue again",
			)
		} finally {
			service.dispose()
		}
	}

	/**
	 * An Edge-mode overlay of one quad over the whole page, both triangles selected and nothing else flagged.
	 *
	 * @return DirectMeshOverlay The overlay, in the page's display positions (texels, y up).
	 */
	private fun bothFacesSelected(): DirectMeshOverlay {
		val mesh =
			MeshOverlayMesh(
				drawableId = pageId,
				vertexCount = 4,
				edgeEndpoints = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3),
				vertexFlags = ByteArray(0),
				edgeFlags = ByteArray(0),
				faceFlags = byteArrayOf(OVERLAY_FLAG_SELECTED, OVERLAY_FLAG_SELECTED),
				activeVertex = null,
				activeEdge = null,
				activeFace = null,
			)
		return DirectMeshOverlay(
			MeshOverlay(MeshOverlayKind.Edit, MeshOverlaySelectMode.Edge, listOf(mesh), MeshOverlaySizes(3.5f, 1f, 2.5f)),
			mapOf(pageId to floatArrayOf(0f, 0f, 16f, 0f, 0f, 16f, 16f, 16f)),
			mapOf(pageId to intArrayOf(0, 1, 2, 1, 3, 2)),
		)
	}

	/**
	 * Whether the sample shows the plain blue page.
	 *
	 * @param Color sample The sampled pixel.
	 * @return Boolean True when blue dominates and no tint is over it.
	 */
	private fun isPageBlue(sample: Color): Boolean = sample.blue > 0.9f && sample.red < 0.1f && sample.green < 0.1f

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
	 * The sample pixel: 20 pixels up and right of the frame's center.
	 *
	 * @param PixelMap pixels The frame.
	 * @return Color The sample.
	 */
	private fun sampleOf(pixels: PixelMap): Color = pixels[pixels.width / 2 + 20, pixels.height / 2 - 20]

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
}