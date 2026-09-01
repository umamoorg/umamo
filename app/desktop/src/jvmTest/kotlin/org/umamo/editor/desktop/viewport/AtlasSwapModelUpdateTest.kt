package org.umamo.editor.desktop.viewport

import androidx.compose.ui.graphics.toPixelMap
import org.umamo.render.DecodedImage
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
import org.umamo.runtime.model.visibleDrawableIds
import org.umamo.ui.viewport.AtlasPageBinding
import org.umamo.ui.viewport.LiveParams
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The live-repack integration gate, one level above the renderer: a real OffscreenPuppetService with
 * its render thread and pairing loop, driven with the exact publish sequence the UI performs on a
 * repack - setModel with a NEW atlas value, setAtlasPages with the binding composed for it - and
 * then a plain UV edit.  The UV edit is the point: after a page-count-changing repack, editing a
 * mapping must still re-render (the modelG report: UV edits stopped updating the render after a
 * 5-page document repacked to 2).
 *
 * Self-skips when no GL context can be created (no frame ever arrives).
 */
class AtlasSwapModelUpdateTest {
	private val paramA = ParameterId("A")
	private val probeId = DrawableId("SwapProbe")

	// A 120x120 quad centered at the origin, keyed with one zero-delta form.
	private val quadPositions = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)
	private val quadIndices = intArrayOf(0, 1, 2, 1, 3, 2)

	private fun quadUvs(centerU: Float, centerV: Float, halfSpan: Float = 0.2f): FloatArray =
		floatArrayOf(
			centerU - halfSpan,
			centerV + halfSpan,
			centerU + halfSpan,
			centerV + halfSpan,
			centerU - halfSpan,
			centerV - halfSpan,
			centerU + halfSpan,
			centerV - halfSpan,
		)

	private fun solidImage(red: Int, green: Int, blue: Int, size: Int = 16): DecodedImage {
		val rgba = ByteArray(size * size * 4)
		for (pixel in rgba.indices step 4) {
			rgba[pixel] = red.toByte()
			rgba[pixel + 1] = green.toByte()
			rgba[pixel + 2] = blue.toByte()
			rgba[pixel + 3] = 0xFF.toByte()
		}
		return DecodedImage(rgba, size, size)
	}

	/** A 16x16 page whose left half is blue and right half is white. */
	private fun blueWhiteImage(): DecodedImage {
		val size = 16
		val rgba = ByteArray(size * size * 4)
		for (rowIndex in 0 until size) {
			for (columnIndex in 0 until size) {
				val pixel = (rowIndex * size + columnIndex) * 4
				val white = columnIndex >= size / 2
				rgba[pixel] = if (white) 0xFF.toByte() else 0x00
				rgba[pixel + 1] = if (white) 0xFF.toByte() else 0x00
				rgba[pixel + 2] = 0xFF.toByte()
				rgba[pixel + 3] = 0xFF.toByte()
			}
		}
		return DecodedImage(rgba, size, size)
	}

	private fun modelWith(atlas: PuppetAtlas, uvs: FloatArray): PuppetModel {
		val drawable =
			Drawable(
				id = probeId,
				name = "SwapProbe",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(quadPositions.copyOf(), uvs, quadIndices),
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
			atlas = atlas,
		)
	}

	/** Waits until the area publishes a frame satisfying [accept], or returns null on timeout. */
	private fun awaitFrame(
		frames: kotlinx.coroutines.flow.StateFlow<org.umamo.ui.viewport.RenderedFrame?>,
		deadlineMillis: Long = 5_000,
		accept: (androidx.compose.ui.graphics.PixelMap) -> Boolean,
	): Boolean {
		val start = System.currentTimeMillis()
		while (System.currentTimeMillis() - start < deadlineMillis) {
			val frame = frames.value
			if (frame != null && accept(frame.bitmap.toPixelMap())) {
				return true
			}
			Thread.sleep(20)
		}
		return false
	}

	private fun centerChannelDominates(pixels: androidx.compose.ui.graphics.PixelMap, channel: String): Boolean {
		val color = pixels[pixels.width / 2, pixels.height / 2]
		val red = color.red
		val green = color.green
		val blue = color.blue
		return when (channel) {
			"red" -> red > green + 0.25f && red > blue + 0.25f
			"blue" -> blue > red + 0.25f && blue > green + 0.25f
			"white" -> red > 0.7f && green > 0.7f && blue > 0.7f
			else -> false
		}
	}

	@Test
	fun aUvEditAfterAPageCountChangingRepackStillRerenders() {
		val baselineAtlas = PuppetAtlas(pages = listOf(AtlasPage(16, 16)))
		val baselineModel = modelWith(baselineAtlas, quadUvs(0.5f, 0.5f))
		val baselineTextures = PuppetTextures(listOf(solidImage(0xFF, 0x00, 0x00)), mapOf(probeId.raw to 0), false)
		val service = OffscreenPuppetService(baselineModel, baselineTextures, LiveParams(emptyMap()))
		service.start()
		try {
			val frames = service.register("area")
			service.resize("area", 100, 100)
			val sawBaseline = awaitFrame(frames) { pixels -> centerChannelDominates(pixels, "red") }
			if (!sawBaseline) {
				println("[atlas-swap-live] no GL frame arrived; skipping (context unavailable, or baseline never rendered)")
				return
			}

			// The repack, in the UI's publish order: the committed model first (the session collector),
			// then the binding composed for its atlas (the resolver).  Page count changes 1 -> 2 and the
			// probe moves to page 1, whose LEFT half is blue.
			val repackedAtlas = PuppetAtlas(pages = listOf(AtlasPage(16, 16), AtlasPage(16, 16)))
			val repackedModel = modelWith(repackedAtlas, quadUvs(0.25f, 0.5f))
			val repackedTextures =
				PuppetTextures(
					listOf(solidImage(0x00, 0xFF, 0x00), blueWhiteImage()),
					mapOf(probeId.raw to 1),
					false,
				)
			service.setModel(repackedModel)
			service.setShownDrawables(repackedModel.visibleDrawableIds())
			service.setAtlasPages(AtlasPageBinding(repackedAtlas, repackedTextures))
			assertTrue(
				awaitFrame(frames) { pixels -> centerChannelDominates(pixels, "blue") },
				"the repacked pair must render (probe on page 1's blue half)",
			)

			// The UV edit: same atlas instance, the mapping moves to the WHITE half.  This is the exact
			// gesture the modelG report says stops updating the render.
			val editedModel =
				repackedModel.copy(
					drawables =
						listOf(
							repackedModel.drawables.single().let { drawable ->
								drawable.copy(mesh = DrawableMesh(drawable.mesh!!.positions, quadUvs(0.75f, 0.5f), drawable.mesh!!.indices))
							},
						),
				)
			service.setModel(editedModel)
			assertTrue(
				awaitFrame(frames) { pixels -> centerChannelDominates(pixels, "white") },
				"a UV edit after the repack must re-render (probe moved to page 1's white half)",
			)
		} finally {
			service.dispose()
		}
	}
}