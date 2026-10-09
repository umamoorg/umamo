package org.umamo.editor.desktop.viewport

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.flow.StateFlow
import org.umamo.render.DecodedImage
import org.umamo.render.FrameOverlays
import org.umamo.render.PuppetTextures
import org.umamo.render.puppet.MeshOverlay
import org.umamo.render.puppet.MeshOverlayKind
import org.umamo.render.puppet.MeshOverlayMesh
import org.umamo.render.puppet.MeshOverlayPalette
import org.umamo.render.puppet.MeshOverlaySelectMode
import org.umamo.render.puppet.MeshOverlaySizes
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
import org.umamo.ui.viewport.AreaOverlays
import org.umamo.ui.viewport.GridConfig
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.RenderedFrame
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The wireframe's per-area gate, one level above the renderer: a real OffscreenPuppetService with its render
 * thread holds one Object-mode wireframe for two 2D areas of one document, and each area draws it or not as
 * ITS render options ask, a change to one area's options repainting that area alone.
 *
 * One solid blue quad over each 100x100 area, its diagonal edge through the frame's center, drawn six display
 * pixels wide in an opaque magenta idle edge color so the line survives the supersample's downscale; the
 * center pixel is magenta where the wireframe draws, half magenta where it draws at half opacity, and the
 * art's blue where it does not.  The same two areas carry the selection tint's per-area gate.  Self-skips
 * when no GL context can be created (no frame ever arrives).
 */
class WireframeLiveEngineTest {
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("WireframeProbe")
	private val frontId = DrawableId("WireframeFront")

	// An 80x80 quad in front of the probe, covering the origin while its own diagonal (from (60, -20) to
	// (-20, 60), the line x + y = 40) passes 28 world units from it, so the center pixel shows only the back
	// quad's wire or the front quad's art.
	private val frontPositions = floatArrayOf(-20f, -20f, 60f, -20f, -20f, 60f, 60f, 60f)

	// A 120x120 quad centered at the origin, keyed with one zero-delta form; its triangles share the diagonal
	// from (60, -60) to (-60, 60), which passes through the origin.
	private val quadPositions = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)
	private val quadUvs = floatArrayOf(0.3f, 0.7f, 0.7f, 0.7f, 0.3f, 0.3f, 0.7f, 0.3f)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	@Test
	fun eachAreaDrawsTheWireframeAsItsOwnOptionsAsk() {
		val model = probeModel()
		val textures = PuppetTextures(listOf(solidBlueImage()), mapOf(probeId.raw to 0), false)
		val service = OffscreenPuppetService(model, textures, LiveParams(emptyMap()))
		service.start()
		try {
			val leftFrames = service.register("left")
			val rightFrames = service.register("right")
			service.setAreaOverlays("right", AreaOverlays(GridConfig(), FrameOverlays(axes = true, wireframe = false)))
			service.resize("left", 100, 100)
			service.resize("right", 100, 100)
			service.setMeshOverlayPalette(MeshOverlayPalette.Classic.copy(edgeIdle = OverlayColor(1f, 0f, 1f, 1f)))
			if (!awaitFrame(leftFrames) { sample -> isArtBlue(sample) }) {
				println("[wireframe-live] no GL frame arrived; skipping (context unavailable, or the art never rendered)")
				return
			}
			assertTrue(awaitFrame(rightFrames) { sample -> isArtBlue(sample) }, "both areas show the plain art before any wireframe")

			service.setMeshOverlay(quadWireframe())
			assertTrue(awaitFrame(leftFrames) { sample -> isMagentaEdge(sample) }, "the left area, asking for the wireframe, draws the quad's diagonal through its center")
			assertTrue(awaitFrame(rightFrames) { sample -> isArtBlue(sample) }, "the right area, not asking, keeps the plain art")
			val rightFrame = rightFrames.value

			service.setAreaOverlays("left", AreaOverlays(GridConfig(), FrameOverlays(axes = true, wireframe = false)))

			assertTrue(awaitFrame(leftFrames) { sample -> isArtBlue(sample) }, "the left area no longer asking repaints without the wireframe")
			assertSame(rightFrame, rightFrames.value, "and the right area, untouched, kept its frame")
		} finally {
			service.dispose()
		}
	}

	/**
	 * Each area fades the wireframe as its own opacity asks: over one held wireframe, an area at half opacity
	 * shows the edge at half over the art while an area at full shows it whole.
	 */
	@Test
	fun eachAreaFadesTheWireframeAsItsOwnOpacityAsks() {
		val model = probeModel()
		val textures = PuppetTextures(listOf(solidBlueImage()), mapOf(probeId.raw to 0), false)
		val service = OffscreenPuppetService(model, textures, LiveParams(emptyMap()))
		service.start()
		try {
			val leftFrames = service.register("left")
			val rightFrames = service.register("right")
			service.setAreaOverlays("left", AreaOverlays(GridConfig(), FrameOverlays(axes = true, wireframeOpacity = 0.5f)))
			service.resize("left", 100, 100)
			service.resize("right", 100, 100)
			service.setMeshOverlayPalette(MeshOverlayPalette.Classic.copy(edgeIdle = OverlayColor(1f, 0f, 1f, 1f)))
			if (!awaitFrame(leftFrames) { sample -> isArtBlue(sample) }) {
				println("[wireframe-live] no GL frame arrived; skipping (context unavailable, or the art never rendered)")
				return
			}

			service.setMeshOverlay(quadWireframe())
			assertTrue(awaitFrame(rightFrames) { sample -> isMagentaEdge(sample) }, "the right area, at full opacity, draws the edge whole")
			assertTrue(awaitFrame(leftFrames) { sample -> isHalfMagentaEdge(sample) }, "the left area, at half, draws the edge at half over the art: ${leftFrames.value?.let { frame -> centerOf(frame.bitmap.toPixelMap()) }}")

			service.setAreaOverlays("left", AreaOverlays(GridConfig(), FrameOverlays(axes = true, wireframeOpacity = 0f)))
			assertTrue(awaitFrame(leftFrames) { sample -> isArtBlue(sample) }, "at zero the left area shows the plain art with the wireframe still held")
		} finally {
			service.dispose()
		}
	}

	/**
	 * Each area tints the selection as its own options ask: the one quad selected and tinted toward red, an area
	 * that declines the tint shows the plain art while the other shows it tinted.
	 */
	@Test
	fun eachAreaTintsTheSelectionAsItsOwnOptionsAsk() {
		val model = probeModel()
		val textures = PuppetTextures(listOf(solidBlueImage()), mapOf(probeId.raw to 0), false)
		val service = OffscreenPuppetService(model, textures, LiveParams(emptyMap()))
		service.start()
		try {
			val leftFrames = service.register("left")
			val rightFrames = service.register("right")
			service.setAreaOverlays("right", AreaOverlays(GridConfig(), FrameOverlays(axes = true, selectionTint = false)))
			service.resize("left", 100, 100)
			service.resize("right", 100, 100)
			service.setSelectionHighlightColor(1f, 0f, 0f)
			if (!awaitFrame(leftFrames) { sample -> isArtBlue(sample) }) {
				println("[wireframe-live] no GL frame arrived; skipping (context unavailable, or the art never rendered)")
				return
			}

			service.setSelection(setOf(probeId))
			assertTrue(awaitFrame(leftFrames) { sample -> isTintedArt(sample) }, "the left area tints the selected quad toward red: ${leftFrames.value?.let { frame -> centerOf(frame.bitmap.toPixelMap()) }}")
			assertTrue(awaitFrame(rightFrames) { sample -> isArtBlue(sample) }, "the right area, declining the tint, shows the plain art")
			val rightFrame = rightFrames.value

			service.setAreaOverlays("left", AreaOverlays(GridConfig(), FrameOverlays(axes = true, selectionTint = false)))
			assertTrue(awaitFrame(leftFrames) { sample -> isArtBlue(sample) }, "the left area declining the tint repaints the plain art")
			assertSame(rightFrame, rightFrames.value, "and the right area, untouched, kept its frame")
		} finally {
			service.dispose()
		}
	}

	/**
	 * Each area culls the wireframe as its own options ask: over a back quad whose diagonal crosses the frame's
	 * center and a front quad covering the center off its own diagonal, the area at the defaults reads the art
	 * at the center (the back quad's wire left out under the front), the area with culling off reads the wire.
	 */
	@Test
	fun eachAreaCullsTheWireframeAsItsOwnOptionsAsk() {
		val model = coveredProbeModel()
		val textures = PuppetTextures(listOf(solidBlueImage()), mapOf(probeId.raw to 0, frontId.raw to 0), false)
		val service = OffscreenPuppetService(model, textures, LiveParams(emptyMap()))
		service.start()
		try {
			val leftFrames = service.register("left")
			val rightFrames = service.register("right")
			service.setAreaOverlays("right", AreaOverlays(GridConfig(), FrameOverlays(axes = true, wireframeCulling = false)))
			service.resize("left", 100, 100)
			service.resize("right", 100, 100)
			service.setMeshOverlayPalette(MeshOverlayPalette.Classic.copy(edgeIdle = OverlayColor(1f, 0f, 1f, 1f)))
			if (!awaitFrame(leftFrames) { sample -> isArtBlue(sample) }) {
				println("[wireframe-live] no GL frame arrived; skipping (context unavailable, or the art never rendered)")
				return
			}

			service.setMeshOverlay(quadWireframe(listOf(probeId, frontId)))
			assertTrue(awaitFrame(rightFrames) { sample -> isMagentaEdge(sample) }, "the right area, not culling, draws the back quad's wire through the front quad")
			assertTrue(awaitFrame(leftFrames) { sample -> isArtBlue(sample) }, "the left area, culling, leaves the wire out under the front quad: ${leftFrames.value?.let { frame -> centerOf(frame.bitmap.toPixelMap()) }}")
			val rightFrame = rightFrames.value

			service.setAreaOverlays("left", AreaOverlays(GridConfig(), FrameOverlays(axes = true, wireframeCulling = false)))
			assertTrue(awaitFrame(leftFrames) { sample -> isMagentaEdge(sample) }, "the left area no longer culling draws the wire")
			assertSame(rightFrame, rightFrames.value, "and the right area, untouched, kept its frame")
		} finally {
			service.dispose()
		}
	}

	/**
	 * The Object-mode wireframe over the given quads, nothing flagged, six display pixels wide.
	 *
	 * @param List<DrawableId> ids The quads, the probe alone by default.
	 * @return MeshOverlay The overlay.
	 */
	private fun quadWireframe(ids: List<DrawableId> = listOf(probeId)): MeshOverlay =
		MeshOverlay(
			MeshOverlayKind.ObjectWireframe,
			MeshOverlaySelectMode.Vertex,
			ids.map { id ->
				MeshOverlayMesh(
					drawableId = id,
					vertexCount = 4,
					edgeEndpoints = intArrayOf(0, 1, 1, 2, 0, 2, 1, 3, 2, 3),
					vertexFlags = ByteArray(0),
					edgeFlags = ByteArray(0),
					faceFlags = ByteArray(0),
					activeVertex = null,
					activeEdge = null,
					activeFace = null,
					wireframeOnly = true,
				)
			},
			MeshOverlaySizes(3.5f, 6f, 2.5f),
		)

	/**
	 * Whether the sample shows the plain blue art.
	 *
	 * @param Color sample The sampled pixel.
	 * @return Boolean True when blue dominates and no line is over it.
	 */
	private fun isArtBlue(sample: Color): Boolean = sample.blue > 0.9f && sample.red < 0.1f && sample.green < 0.1f

	/**
	 * Whether the sample shows the magenta edge: red joins the blue while green stays out, whatever the
	 * downscale's blend with the art.
	 *
	 * @param Color sample The sampled pixel.
	 * @return Boolean True when the edge is there.
	 */
	private fun isMagentaEdge(sample: Color): Boolean = sample.red > 0.4f && sample.blue > 0.4f && sample.green < 0.2f

	/**
	 * Whether the sample shows the magenta edge at half opacity over the blue art: half the red, the blue whole,
	 * no green.
	 *
	 * @param Color sample The sampled pixel.
	 * @return Boolean True when the faded edge is there.
	 */
	private fun isHalfMagentaEdge(sample: Color): Boolean = sample.red in 0.25f..0.75f && sample.blue > 0.75f && sample.green < 0.2f

	/**
	 * Whether the sample shows the blue art tinted toward the red selection color: red joins the blue at the
	 * tint's strength while green stays out.
	 *
	 * @param Color sample The sampled pixel.
	 * @return Boolean True when the tint is there.
	 */
	private fun isTintedArt(sample: Color): Boolean = sample.red > 0.2f && sample.blue > 0.4f && sample.green < 0.1f

	/**
	 * Waits until the area publishes a frame whose center pixel satisfies [accept].
	 *
	 * @param StateFlow<RenderedFrame?> frames The area's frame flow.
	 * @param Function accept The test on the center pixel.
	 * @return Boolean True when such a frame arrived before the deadline.
	 */
	private fun awaitFrame(frames: StateFlow<RenderedFrame?>, accept: (Color) -> Boolean): Boolean {
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < 5_000) {
			val frame = frames.value
			if (frame != null && accept(centerOf(frame.bitmap.toPixelMap()))) {
				return true
			}
			Thread.sleep(20)
		}
		return false
	}

	/**
	 * The frame's center pixel, where the quad's diagonal passes.
	 *
	 * @param PixelMap pixels The frame.
	 * @return Color The sample.
	 */
	private fun centerOf(pixels: PixelMap): Color = pixels[pixels.width / 2, pixels.height / 2]

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
	 * The two-quad model: the probe with the front quad drawn over it, on a single 16x16 page.
	 *
	 * @return PuppetModel The model.
	 */
	private fun coveredProbeModel(): PuppetModel {
		val back = probeModel()
		val front =
			Drawable(
				id = frontId,
				name = frontId.raw,
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh.withLocalEqualToCanvas(frontPositions, quadUvs, quadIndices),
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(paramA, floatArrayOf(0f))),
						listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(frontPositions.size)))),
					),
			)
		return back.copy(drawables = back.drawables + front, rootChildren = listOf(OrgChild.Drawable(frontId), OrgChild.Drawable(probeId)))
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