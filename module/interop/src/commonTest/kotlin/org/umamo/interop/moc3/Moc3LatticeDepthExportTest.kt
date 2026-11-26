package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.LatticeDepth
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * A warp lattice's editing depth through the MOC3 export: the file has no depth, so a deep warp is written byte for
 * byte as its flat twin is, and the report names it in one notice.
 */
class Moc3LatticeDepthExportTest {
	private val angleX = ParameterId("ParamAngleX")
	private val partId = PartId("Part1")

	/**
	 * The rig: a page-bound triangle and the Face warp keyed on the angle at -1 and 1, the second key at [secondDepth].
	 *
	 * @param LatticeDepth secondDepth The second key's depth.
	 * @return PuppetModel The rig.
	 */
	private fun puppet(secondDepth: LatticeDepth): PuppetModel {
		val drawable =
			Drawable(
				id = DrawableId("ArtMesh1"),
				name = "ArtMesh1",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				texturePage = 0,
				mesh = DrawableMesh.withLocalEqualToCanvas(positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), indices = intArrayOf(0, 1, 2)),
				geometryGrid = null,
			)
		val warp =
			Deformer.Warp(
				id = DeformerId("Face"),
				name = "Face",
				parent = null,
				partId = partId,
				rows = 1,
				columns = 1,
				isQuadTransform = true,
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(angleX, floatArrayOf(-1f, 1f))),
						listOf(
							KeyformCell(intArrayOf(0), WarpLatticeForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))),
							KeyformCell(intArrayOf(1), WarpLatticeForm(floatArrayOf(0.1f, 0f, 1.1f, 0f, 0.1f, 1f, 1.1f, 1f), secondDepth)),
						),
					),
			)

		return PuppetModel(
			parameters = listOf(Parameter(angleX, angleX.raw, min = -1f, max = 1f, default = 0f)),
			parts = listOf(Part(id = partId, name = "Part1", children = listOf(OrgChild.Drawable(drawable.id)))),
			deformers = listOf(warp),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Part(partId)),
			rootPartId = null,
			canvasWidth = 100f,
			canvasHeight = 100f,
		)
	}

	/** The deep rig's file is its flat twin's byte for byte, and only the deep one raises the depth notice. */
	@Test
	fun aDeepWarpIsWrittenAsItsFlatTwinWithOneNotice() {
		val flat = Moc3Export.toMocDocument(puppet(LatticeDepth.Flat), MocVersion.V50)
		val deep = Moc3Export.toMocDocument(puppet(LatticeDepth.PerPoint(floatArrayOf(0f, 5f, 10f, 15f))), MocVersion.V50)

		assertContentEquals(Moc3.write(flat.document), Moc3.write(deep.document), "the file carries no depth")
		assertEquals(emptyList(), flat.report.notices)
		assertEquals(
			listOf<ExportNotice>(ExportNotice.UnsupportedChange(ExportEntityCategory.Deformer, null, ExportNoticeReason.LatticeDepthFlattened(listOf("Face")))),
			deep.report.notices,
		)
	}
}