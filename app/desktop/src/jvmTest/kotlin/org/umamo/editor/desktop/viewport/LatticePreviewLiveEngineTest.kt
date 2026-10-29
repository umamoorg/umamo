package org.umamo.editor.desktop.viewport

import org.umamo.render.ContentBounds
import org.umamo.render.DecodedImage
import org.umamo.render.PuppetTextures
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.viewport.LiveParams
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The lattice preview's delivery, one level above the renderer: a real OffscreenPuppetService with its render thread
 * takes a warp's previewed lattice through the shared pose hand-off, re-poses on it with no model push, and the
 * warp's drawn lattice moves; clearing the preview puts it back.  Read through the lattice extent the picks use,
 * which comes off the same deformer worlds the art is drawn with.
 *
 * A quad of art inside a 1 x 1 warp over canvas -60..60.  Self-skips when no GL context can be created (no pose is
 * ever applied, so the lattice is never measured).
 */
class LatticePreviewLiveEngineTest {
	private val parameter = ParameterId("A")
	private val warpId = DeformerId("cage")
	private val artId = DrawableId("art")
	private val cage = floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f)

	@Test
	fun aPreviewedLatticeIsDrawnWithoutAModelPushAndClears() {
		val service = OffscreenPuppetService(model(), PuppetTextures(listOf(page()), mapOf(artId.raw to 0), false), LiveParams(emptyMap()))
		service.start()

		try {
			service.register("area")
			service.resize("area", 100, 100)

			if (!awaitExtent(service) { bounds -> bounds.minX == -60f }) {
				println("[lattice-preview-live] the lattice was never measured; skipping (no GL context)")

				return
			}

			val shifted = FloatArray(cage.size) { componentIndex -> if (componentIndex % 2 == 0) cage[componentIndex] + 30f else cage[componentIndex] }
			service.setLatticePreview(mapOf(warpId to WarpLatticeForm(shifted)))
			assertTrue(awaitExtent(service) { bounds -> bounds.minX == -30f }, "the previewed lattice is drawn thirty units right")

			service.setLatticePreview(emptyMap())
			assertTrue(awaitExtent(service) { bounds -> bounds.minX == -60f }, "clearing the preview puts it back")
		} finally {
			service.dispose()
		}
	}

	/**
	 * Waits until the warp's drawn lattice extent satisfies [accept].
	 *
	 * @param OffscreenPuppetService service The service.
	 * @param Function accept The test on the extent.
	 * @return Boolean True when such an extent appeared before the deadline.
	 */
	private fun awaitExtent(service: OffscreenPuppetService, accept: (ContentBounds) -> Boolean): Boolean {
		val start = System.currentTimeMillis()

		while (System.currentTimeMillis() - start < 5_000) {
			val extent = service.latticeWorldExtent(setOf(warpId))

			if (extent != null && accept(extent)) {
				return true
			}

			Thread.sleep(20)
		}

		return false
	}

	/**
	 * A 16x16 opaque page.
	 *
	 * @return DecodedImage The page.
	 */
	private fun page(): DecodedImage = DecodedImage(ByteArray(16 * 16 * 4) { 0xFF.toByte() }, 16, 16)

	/**
	 * The quad inside the warp.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		val warp =
			Deformer.Warp(
				id = warpId,
				name = "cage",
				parent = null,
				partId = null,
				rows = 1,
				columns = 1,
				isQuadTransform = true,
				geometryGrid = KeyformGrid(listOf(KeyformAxis(parameter, floatArrayOf(-1f, 1f))), listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(cage)), KeyformCell(intArrayOf(1), WarpLatticeForm(cage)))),
			)
		val art =
			Drawable(
				id = artId,
				name = "art",
				parentDeformerId = warpId,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh =
					DrawableMesh(
						floatArrayOf(-60f, -60f, 60f, -60f, -60f, 60f, 60f, 60f),
						floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
						floatArrayOf(0.3f, 0.7f, 0.7f, 0.7f, 0.3f, 0.3f, 0.7f, 0.3f),
						intArrayOf(0, 1, 2, 1, 3, 2),
					),
				geometryGrid = null,
			)

		return PuppetModel(
			parameters = listOf(Parameter(parameter, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = listOf(warp),
			drawables = listOf(art),
			rootChildren = listOf(OrgChild.Drawable(artId)),
			rootPartId = null,
			atlas = PuppetAtlas(pages = listOf(AtlasPage(16, 16))),
		)
	}
}