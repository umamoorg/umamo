package org.umamo.interop.cmo3

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Cmo3AtlasUndedupTest {
	private val pageSize = 32
	private val uvs = floatArrayOf(4f / 32f, 4f / 32f, 28f / 32f, 4f / 32f, 4f / 32f, 28f / 32f, 28f / 32f, 28f / 32f)
	private val indices = intArrayOf(0, 1, 2, 1, 2, 3)

	private fun page(): Cmo3Conversion.AtlasPage {
		val rgba = ByteArray(pageSize * pageSize * 4)
		for (rowIndex in 12 until 19) {
			for (columnIndex in 10 until 15) {
				rgba.fill(0xFF.toByte(), (rowIndex * pageSize + columnIndex) * 4, (rowIndex * pageSize + columnIndex) * 4 + 4)
			}
		}
		return Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, rgba)), pageSize, pageSize)
	}

	private fun drawable(id: String, positions: FloatArray): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions = positions, uvs = uvs.copyOf(), indices = indices.copyOf()),
			geometryGrid = null,
		)

	private fun puppet(drawables: List<Drawable>): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { OrgChild.Drawable(it.id) },
			rootPartId = null,
			canvasWidth = 200f,
			canvasHeight = 200f,
			worldOriginX = 100f,
			worldOriginY = -100f,
			runtimeTarget = RuntimeTarget.Cubism53,
		)

	@Test
	fun mirroredTwinsGetOwnSlots() {
		val straightPositions = FloatArray(uvs.size) { index -> uvs[index] * pageSize }
		val shiftedPositions = FloatArray(uvs.size) { index -> uvs[index] * pageSize + if (index % 2 == 0) 50f else 0f }
		val model = puppet(listOf(drawable("TwinA", straightPositions), drawable("TwinB", shiftedPositions)))
		val pageIndexByDrawableId = mapOf("TwinA" to 0, "TwinB" to 0)
		val result = Cmo3AtlasUndedup.undeduplicate(model, listOf(page()), pageIndexByDrawableId)

		assertEquals(listOf("TwinB"), result.duplicatedDrawableIds, "the second placement duplicates")
		assertEquals(2, result.pages.size, "a synthesized page is appended")
		assertEquals(0, result.pageIndexByDrawableId.getValue("TwinA"), "the first placement keeps its page")
		assertEquals(1, result.pageIndexByDrawableId.getValue("TwinB"), "the duplicate moves to the new page")
		val twinA = result.puppet.drawables.single { it.id.raw == "TwinA" }
		assertContentEquals(uvs, twinA.mesh?.uvs, "the first placement keeps its uvs")

		// The remapped uvs must sample the same pixels on the new page as the originals did on the
		// old page.
		val twinB = result.puppet.drawables.single { it.id.raw == "TwinB" }
		val newUvs = twinB.mesh?.uvs ?: error("twin B lost its mesh")
		val sourcePage = PngCodec.read(page().pngBytes)
		val extraPage = PngCodec.read(result.pages[1].pngBytes)
		var componentIndex = 0
		while (componentIndex + 1 < uvs.size) {
			// Sample just inside the quad corner so both lookups stay in range.
			val sourceX = (uvs[componentIndex] * pageSize).toInt().coerceIn(0, pageSize - 1)
			val sourceY = (uvs[componentIndex + 1] * pageSize).toInt().coerceIn(0, pageSize - 1)
			val newX = (newUvs[componentIndex] * extraPage.width).toInt().coerceIn(0, extraPage.width - 1)
			val newY = (newUvs[componentIndex + 1] * extraPage.height).toInt().coerceIn(0, extraPage.height - 1)
			// Compare a pixel INSIDE the block region via the same offset from the quad corner.
			val insideSourceX = sourceX + (12 - 4)
			val insideSourceY = sourceY + (14 - 4)
			val insideNewX = newX + (12 - 4)
			val insideNewY = newY + (14 - 4)
			if (insideSourceX in 0 until pageSize &&
				insideSourceY in 0 until pageSize &&
				insideNewX in 0 until extraPage.width &&
				insideNewY in 0 until extraPage.height
			) {
				assertEquals(
					sourcePage.rgba[(insideSourceY * pageSize + insideSourceX) * 4 + 3],
					extraPage.rgba[(insideNewY * extraPage.width + insideNewX) * 4 + 3],
					"pixel content under the remapped uvs matches",
				)
			}
			componentIndex += 2
		}
		// The block itself must be present somewhere opaque on the synthesized page.
		assertTrue(extraPage.rgba.indices.step(4).any { offset -> extraPage.rgba[offset + 3].toInt() != 0 }, "the extra page carries pixels")
	}

	@Test
	fun coLocatedDuplicatesAreNotSplit() {
		val positions = FloatArray(uvs.size) { index -> uvs[index] * pageSize }
		val model = puppet(listOf(drawable("VariantA", positions), drawable("VariantB", positions.copyOf())))
		val result = Cmo3AtlasUndedup.undeduplicate(model, listOf(page()), mapOf("VariantA" to 0, "VariantB" to 0))
		assertTrue(result.duplicatedDrawableIds.isEmpty(), "co-located duplicates share their slot")
		assertEquals(1, result.pages.size, "no synthesized page")
		assertEquals(model, result.puppet, "the puppet passes through untouched")
	}

	/**
	 * A 64 x 64 page with two slots - one block in each - so two independent twin pairs share one
	 * synthesized page.
	 */
	private val wideSize = 64
	private val slotAUvs = floatArrayOf(4f / 64f, 4f / 64f, 28f / 64f, 4f / 64f, 4f / 64f, 28f / 64f, 28f / 64f, 28f / 64f)
	private val slotBUvs = floatArrayOf(36f / 64f, 36f / 64f, 60f / 64f, 36f / 64f, 36f / 64f, 60f / 64f, 60f / 64f, 60f / 64f)

	private fun widePage(blocks: List<IntArray>): Cmo3Conversion.AtlasPage {
		val rgba = ByteArray(wideSize * wideSize * 4)
		for (block in blocks) {
			for (rowIndex in block[1] until block[3]) {
				for (columnIndex in block[0] until block[2]) {
					val offset = (rowIndex * wideSize + columnIndex) * 4
					rgba[offset] = block[4].toByte()
					rgba[offset + 1] = (columnIndex * 3).toByte()
					rgba[offset + 2] = (rowIndex * 5).toByte()
					rgba[offset + 3] = 0xFF.toByte()
				}
			}
		}
		return Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(wideSize, wideSize, rgba)), wideSize, wideSize)
	}

	private fun drawableOver(id: String, slotUvs: FloatArray, shiftX: Float): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions = FloatArray(slotUvs.size) { index -> slotUvs[index] * wideSize + if (index % 2 == 0) shiftX else 0f }, uvs = slotUvs.copyOf(), indices = indices.copyOf()),
			geometryGrid = null,
		)

	/**
	 * Every pixel of a duplicated drawable's patch rect, transparent margin included, sits under its
	 * remapped uvs exactly as it sat under the originals: the reserve keeps the margin clear and
	 * nothing is extruded into it.
	 */
	private fun assertPatchCarriedWhole(drawableId: String, result: Cmo3AtlasUndedup.Result, sourcePage: Cmo3Conversion.AtlasPage, slotUvs: FloatArray) {
		val drawable = result.puppet.drawables.single { candidate -> candidate.id.raw == drawableId }
		val newUvs = drawable.mesh?.uvs ?: error("$drawableId lost its mesh")
		val newPage = PngCodec.read(result.pages[result.pageIndexByDrawableId.getValue(drawableId)].pngBytes)
		val source = PngCodec.read(sourcePage.pngBytes)
		val rect = Cmo3ImageChainBuilder.patchRectOf(slotUvs, sourcePage.width, sourcePage.height)!!
		// The remap is a whole-pixel translation: read it off the first vertex.
		val deltaX = Math.round(newUvs[0] * newPage.width - slotUvs[0] * sourcePage.width)
		val deltaY = Math.round(newUvs[1] * newPage.height - slotUvs[1] * sourcePage.height)
		for (rowIndex in rect[1] until rect[3]) {
			for (columnIndex in rect[0] until rect[2]) {
				val sourceOffset = (rowIndex * source.width + columnIndex) * 4
				val newOffset = ((rowIndex + deltaY) * newPage.width + columnIndex + deltaX) * 4
				for (channel in 0 until 4) {
					assertEquals(source.rgba[sourceOffset + channel], newPage.rgba[newOffset + channel], "$drawableId pixel ($columnIndex, $rowIndex) channel $channel")
				}
			}
		}
	}

	@Test
	fun severalTwinsPackOntoOnePageAndEachKeepsItsWholePatch() {
		val page = widePage(listOf(intArrayOf(10, 12, 15, 19, 40), intArrayOf(44, 40, 50, 47, 90)))
		val model =
			puppet(
				listOf(
					drawableOver("A1", slotAUvs, 0f),
					drawableOver("A2", slotAUvs, 50f),
					drawableOver("B1", slotBUvs, 0f),
					drawableOver("B2", slotBUvs, 70f),
				),
			)
		val pageIndexByDrawableId = mapOf("A1" to 0, "A2" to 0, "B1" to 0, "B2" to 0)
		val result = Cmo3AtlasUndedup.undeduplicate(model, listOf(page), pageIndexByDrawableId)
		assertEquals(listOf("A2", "B2"), result.duplicatedDrawableIds.sorted())
		assertTrue(result.sharedDrawableIds.isEmpty())
		assertEquals(2, result.pages.size, "both further placements share one synthesized page")
		assertEquals(1, result.pageIndexByDrawableId.getValue("A2"))
		assertEquals(1, result.pageIndexByDrawableId.getValue("B2"))
		assertEquals(0, result.pageIndexByDrawableId.getValue("A1"))
		assertEquals(0, result.pageIndexByDrawableId.getValue("B1"))
		assertPatchCarriedWhole("A2", result, page, slotAUvs)
		assertPatchCarriedWhole("B2", result, page, slotBUvs)
		val a2 = result.puppet.drawables.single { drawable -> drawable.id.raw == "A2" }.mesh!!.uvs
		val b2 = result.puppet.drawables.single { drawable -> drawable.id.raw == "B2" }.mesh!!.uvs
		assertNotEquals(a2[0] to a2[1], b2[0] to b2[1], "the two patches landed apart")
		val synthesized = PngCodec.read(result.pages[1].pngBytes)
		assertTrue(synthesized.width <= wideSize && synthesized.height <= wideSize, "the page shrinks to its content")

		// Deterministic: the same inputs pack to the same bytes and the same remaps.
		val again = Cmo3AtlasUndedup.undeduplicate(model, listOf(page), pageIndexByDrawableId)
		assertContentEquals(result.pages[1].pngBytes, again.pages[1].pngBytes)
		assertContentEquals(a2, again.puppet.drawables.single { drawable -> drawable.id.raw == "A2" }.mesh!!.uvs)
		assertContentEquals(b2, again.puppet.drawables.single { drawable -> drawable.id.raw == "B2" }.mesh!!.uvs)
	}

	@Test
	fun aTransparentTwinSharesItsSlotSilently() {
		// Slot A has no opaque pixel at all (a hit area's): the packer has nothing to place, so the
		// further placement stays on the source slot with its uvs as they were, and nothing is
		// reported - nothing shows at either placement.
		val page = widePage(listOf(intArrayOf(44, 40, 50, 47, 90)))
		val model =
			puppet(
				listOf(
					drawableOver("GhostA", slotAUvs, 0f),
					drawableOver("GhostB", slotAUvs, 50f),
					drawableOver("B1", slotBUvs, 0f),
					drawableOver("B2", slotBUvs, 70f),
				),
			)
		val result = Cmo3AtlasUndedup.undeduplicate(model, listOf(page), mapOf("GhostA" to 0, "GhostB" to 0, "B1" to 0, "B2" to 0))
		assertTrue(result.sharedDrawableIds.isEmpty(), "a transparent twin is not the export notice's case")
		assertEquals(listOf("B2"), result.duplicatedDrawableIds)
		assertEquals(2, result.pages.size, "one synthesized page, for the twin with art")
		assertEquals(0, result.pageIndexByDrawableId.getValue("GhostB"), "the transparent twin stays on the source page")
		assertContentEquals(slotAUvs, result.puppet.drawables.single { drawable -> drawable.id.raw == "GhostB" }.mesh?.uvs, "with its uvs untouched")
		assertPatchCarriedWhole("B2", result, page, slotBUvs)
	}
}