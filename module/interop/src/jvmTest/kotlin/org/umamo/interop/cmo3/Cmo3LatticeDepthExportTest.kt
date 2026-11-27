package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.cmo3.export.Cmo3Conversion
import org.umamo.interop.cmo3.export.Cmo3Export
import org.umamo.interop.cmo3.import.Cmo3Import
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.LatticeDepth
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.isFlat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A warp lattice's editing depth through the CMO3 export: CMO3 has no depth, so the export writes the lattice's 2D
 * points as they are and says so in one notice naming the warp, whether the warp was edited on a retained graph or
 * created by a fresh conversion.
 */
class Cmo3LatticeDepthExportTest {
	private val warpId = DeformerId("Face")

	/** The rest lattice, a 64 px square. */
	private val square = floatArrayOf(0f, 0f, 64f, 0f, 0f, 64f, 64f, 64f)

	/** A depth for the square's corners. */
	private val depth = LatticeDepth.PerPoint(floatArrayOf(0f, 8f, 16f, 24f))

	/** The one notice a CMO3 export of the deep warp raises. */
	private val depthNotice = ExportNotice.UnsupportedChange(ExportEntityCategory.Deformer, null, ExportNoticeReason.LatticeDepthFlattened(listOf("Face")))

	private fun puppet(latticeDepth: LatticeDepth): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers =
				listOf(
					Deformer.Warp(
						id = warpId,
						name = warpId.raw,
						parent = null,
						partId = null,
						rows = 1,
						columns = 1,
						isQuadTransform = false,
						geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), WarpLatticeForm(square, latticeDepth)))),
					),
				),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			parameterTree = emptyList(),
			canvasWidth = 64f,
			canvasHeight = 64f,
			worldOriginX = 32f,
			worldOriginZ = -32f,
			runtimeTarget = RuntimeTarget.Cubism53,
		)

	private fun mainXmlLines(cmo3Bytes: ByteArray): List<String> = CaffCodec.read(cmo3Bytes).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content.decodeToString().lines()

	private fun importOf(cmo3Bytes: ByteArray): PuppetModel = Cmo3Import.fromModelSource(assertIs<CModelSource>(Cmo3.read(cmo3Bytes).root))

	private fun withDepth(model: PuppetModel): PuppetModel =
		model.copy(
			deformers =
				model.deformers.map { deformer ->
					val warp = deformer as Deformer.Warp
					val grid = warp.geometryGrid!!
					warp.copy(geometryGrid = KeyformGrid(grid.axes, grid.cells.map { cell -> KeyformCell(cell.coordinate, WarpLatticeForm(cell.form.controlPoints, depth)) }))
				},
		)

	/** A depth-only edit on a retained graph raises the one notice and writes main.xml exactly as it was read. */
	@Test
	fun aDepthOnlyEditRaisesOneNoticeAndLeavesTheGraph() {
		val sourceBytes = Cmo3.write(Cmo3Conversion.freshCmo3(puppet(LatticeDepth.Flat), emptyList(), emptyMap(), "depth", nowMillis = 0L, obfuscateKey = 0).model)
		val cmo3 = Cmo3.read(sourceBytes)
		val edited = withDepth(Cmo3Import.fromModelSource(assertIs<CModelSource>(cmo3.root)))

		val report = Cmo3Export.apply(edited, cmo3)

		assertEquals(listOf<ExportNotice>(depthNotice), report.notices)
		assertEquals(mainXmlLines(sourceBytes), mainXmlLines(Cmo3.write(cmo3)), "depth writes nothing into the graph")
	}

	/** A fresh conversion creates the deep warp with its 2D points, flat, and raises the same one notice. */
	@Test
	fun aFreshConversionOfADeepWarpWritesItsPointsAndRaisesTheNotice() {
		val converted = Cmo3Conversion.freshCmo3(puppet(depth), emptyList(), emptyMap(), "depth", nowMillis = 0L, obfuscateKey = 0)
		val reopened = importOf(Cmo3.write(converted.model)).deformers.single() as Deformer.Warp
		val reopenedForm = reopened.geometryGrid!!.cells.single().form

		assertTrue(depthNotice in converted.report.notices, "the conversion names the warp: ${converted.report.notices}")
		assertEquals(1, converted.report.notices.count { notice -> notice == depthNotice })
		assertContentEquals(square, reopenedForm.controlPoints, "the lattice keeps its 2D points")
		assertTrue(reopenedForm.depth.isFlat, "and reopens flat")
	}
}