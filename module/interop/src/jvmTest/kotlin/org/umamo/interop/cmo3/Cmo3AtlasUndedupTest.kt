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
}