package org.umamo.interop.moc3

import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MOC3 export's 16-bit triangle indices: a mesh of 65536 vertices still writes its top index,
 * and one more vertex leaves the mesh out with a notice instead of wrapping its indices.
 */
class Moc3IndexWidthTest {
	@Test
	fun aMeshAtTheSixteenBitLimitKeepsItsTopIndex() {
		val lowered = Moc3Export.toMocDocument(puppetWith(65536), MocVersion.V50)
		val artMesh = lowered.document.artMeshes.single()
		assertEquals(65535, artMesh.triangleIndices.last().toInt() and 0xFFFF)
		assertTrue(lowered.report.notices.none { notice -> (notice as? ExportNotice.UnsupportedChange)?.reason is ExportNoticeReason.VertexCountExceedsIndexWidth })
	}

	@Test
	fun aMeshPastSixteenBitIndicesIsLeftOutWithANotice() {
		val lowered = Moc3Export.toMocDocument(puppetWith(65537), MocVersion.V50)
		assertTrue(lowered.document.artMeshes.isEmpty())
		val reasons = lowered.report.notices.filterIsInstance<ExportNotice.UnsupportedChange>().filter { notice -> notice.subject == "Big" }.map { notice -> notice.reason }
		assertEquals(listOf<ExportNoticeReason>(ExportNoticeReason.VertexCountExceedsIndexWidth(65537, 65536)), reasons)
	}

	/**
	 * A rig of one part holding one drawable whose single triangle uses its last two vertices.
	 *
	 * @param Int vertexCount The mesh's vertex count.
	 * @return PuppetModel The rig.
	 */
	private fun puppetWith(vertexCount: Int): PuppetModel {
		val positions = FloatArray(2 * vertexCount) { component -> (component / 2 % 256).toFloat() + if (component % 2 == 1) (component / 512).toFloat() else 0f }
		val uvs = FloatArray(2 * vertexCount)
		val mesh = DrawableMesh.withLocalEqualToCanvas(positions, uvs, intArrayOf(0, vertexCount - 2, vertexCount - 1))
		val drawable =
			Drawable(
				id = DrawableId("Big"),
				name = "Big",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				texturePage = 0,
				mesh = mesh,
				geometryGrid = null,
			)
		val part = Part(id = PartId("Body"), name = "Body", children = listOf(OrgChild.Drawable(drawable.id)))

		return PuppetModel(
			parameters = emptyList(),
			parts = listOf(part),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Part(part.id)),
			rootPartId = null,
			canvasWidth = 256f,
			canvasHeight = 256f,
		)
	}
}