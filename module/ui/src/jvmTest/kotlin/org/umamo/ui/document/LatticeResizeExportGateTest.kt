package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.umamo.edit.EditorSession
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.uma.UmaModel
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.transform.setLatticeSizeAdjustable
import org.umamo.ui.transform.subdivideLatticesAdjustable
import org.umamo.ui.viewport.AtlasPageBinding
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Lattice resolution changes over a real rig, through to the CMO3 the official editor opens and the UMA the editor
 * saves: the corpus warp with the most children is subdivided, a second warp with art under it is resized to a size
 * that is not a multiple, and the export reopens with both lattices where the session has them at rest and at every
 * parameter's extremes, and with their children's art resting where it rested before.  The UMA round trip carries the
 * same.  The export is written to build/lattice-resize for Cmo3ShapeGateTest and the official editor.
 *
 * Skips without the corpus sample.
 */
class LatticeResizeExportGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun resizedWarpsExportAndReopenWithTheirChildrenInPlace() =
		runBlocking {
			val file = sample

			if (file == null) {
				println("cmo3.sample not present; skipping the lattice resize export gate")

				return@runBlocking
			}

			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(file.readBytes(), file.name, file.path)).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val base = session.model.value
			val candidates =
				base.deformers
					.filterIsInstance<Deformer.Warp>()
					.filter { candidate -> candidate.rows * 2 <= 100 && candidate.columns * 2 <= 100 && warpLatticeAt(base, emptyMap(), candidate.id) != null && childDrawableIds(base, candidate.id).isNotEmpty() }
					.sortedByDescending { candidate -> childDrawableIds(base, candidate.id).size + base.deformers.count { deformer -> deformer.parent == candidate.id } }
			val subdivided = candidates.first()
			// A second warp outside the first one's subtree, so each change is measured on its own.
			val resized = candidates.drop(1).first { candidate -> subdivided.id !in ancestorIds(base, candidate.id) && candidate.id !in ancestorIds(base, subdivided.id) }
			val children = childDrawableIds(base, subdivided.id) + childDrawableIds(base, resized.id)

			session.subdivideLatticesAdjustable(listOf(subdivided.id), null)
			session.setLatticeSizeAdjustable(resized.id, resized.rows + 1, resized.columns + 1, null)
			val after = session.model.value
			assertNotSame(base, after, "the resizes landed")
			assertEquals(subdivided.rows * 2, (after.deformerById[subdivided.id] as Deformer.Warp).rows)
			assertEquals(resized.columns + 1, (after.deformerById[resized.id] as Deformer.Warp).columns)
			println(
				"[lattice-resize] ${file.name}: ${subdivided.name} ${subdivided.rows}x${subdivided.columns} subdivided " +
					"(quad ${subdivided.isQuadTransform}), ${resized.name} ${resized.rows}x${resized.columns} resized by one, " +
					"${children.size} drawable(s) under them",
			)

			val restDrift = worstDrift(restWorlds(base, children), restWorlds(after, children))
			println("[lattice-resize]   the children's art moved at most $restDrift px at rest")
			assertTrue(restDrift <= 0.1f, "the children rest where they rested (moved $restDrift px)")

			// The CMO3: written for the shape gate and the official editor, then reopened.
			val prepared = prepareCmo3Export(document, exportedModelFor(document, session), document.textures, "gate", 0L, 0)
			println("[lattice-resize]   export report: ${prepared.report.notices.map { notice -> notice::class.simpleName }}")
			assertTrue(prepared.report.isEmpty, "the export carries the resizes whole: ${prepared.report.notices}")
			val exportedBytes = Cmo3.write(prepared.model)
			val exportFile = File("build/lattice-resize/${file.nameWithoutExtension}LatticeResizeExportTest.cmo3")
			exportFile.parentFile.mkdirs()
			exportFile.writeBytes(exportedBytes)
			println("[lattice-resize]   wrote ${exportFile.absolutePath}")
			val reopened = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(exportedBytes, "gate.cmo3", "/art/gate.cmo3")).document).puppet
			assertSameRig(after, reopened, listOf(subdivided.id, resized.id), children, "the CMO3")

			// The UMA: saved and reopened.
			val umaFile = File(Files.createTempDirectory("lattice-resize").toFile(), "resized.uma")
			val written = writeUmaDocument(document, UmaModel.create(umamoWriterInfo()), after, AtlasPageBinding(after.atlas, document.textures), JsonObject(emptyMap()), PlatformFile(umaFile))
			assertIs<UmaWriteOutcome.Written>(written, "the save succeeds: ${(written as? UmaWriteOutcome.Failed)?.reason}")
			val umaReopened = assertIs<DocumentLoad.Loaded>(loadDocument(umaFile.readBytes(), umaFile.name, umaFile.path)).document as PuppetDocument
			assertSameRig(after, umaReopened.puppet, listOf(subdivided.id, resized.id), children, "the UMA")
		}

	/**
	 * Asserts [reopened] draws the resized warps' lattices and their children's art where [after] does: at rest, and
	 * with each parameter at its minimum and its maximum.
	 *
	 * @param PuppetModel after The session's model.
	 * @param PuppetModel reopened The reopened model.
	 * @param List<DeformerId> warpIds The resized warps.
	 * @param List<DrawableId> children The art under them.
	 * @param String format What was reopened.
	 */
	private fun assertSameRig(after: PuppetModel, reopened: PuppetModel, warpIds: List<DeformerId>, children: List<DrawableId>, format: String) {
		val poses = listOf(emptyMap<ParameterId, Float>()) + after.parameters.flatMap { parameter -> listOf(mapOf(parameter.id to parameter.min), mapOf(parameter.id to parameter.max)) }
		var latticeDrift = 0f
		var artDrift = 0f

		for (pose in poses) {
			for (warpId in warpIds) {
				val sessionLattice = warpLatticeAt(after, pose, warpId)?.world
				val reopenedLattice = warpLatticeAt(reopened, pose, warpId)?.world
				assertEquals(sessionLattice == null, reopenedLattice == null, "$format: $warpId is drawn at $pose exactly when the session draws it")

				if (sessionLattice != null && reopenedLattice != null) {
					latticeDrift = maxOf(latticeDrift, worstDrift(listOf(sessionLattice), listOf(reopenedLattice)))
				}
			}

			artDrift = maxOf(artDrift, worstDrift(captureDrawableWorlds(after, pose, children).map { geometry -> geometry.world }, captureDrawableWorlds(reopened, pose, children).map { geometry -> geometry.world }))
		}

		println("[lattice-resize]   $format reopens with the lattices within $latticeDrift px and the art within $artDrift px over ${poses.size} poses")
		assertTrue(latticeDrift <= 1e-2f, "$format: the resized lattices reopen where the session has them ($latticeDrift px)")
		assertTrue(artDrift <= 1e-2f, "$format: the art under them reopens where the session has it ($artDrift px)")
	}

	/**
	 * The deformers above [deformerId], nearest first.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId deformerId The deformer.
	 * @return Set<DeformerId> Its ancestors.
	 */
	private fun ancestorIds(model: PuppetModel, deformerId: DeformerId): Set<DeformerId> =
		generateSequence(model.deformerById[deformerId]?.parent) { parentId -> model.deformerById[parentId]?.parent }.toSet()

	/**
	 * The drawables directly under a warp that carry a mesh.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId warpId The warp.
	 * @return List<DrawableId> The drawables.
	 */
	private fun childDrawableIds(model: PuppetModel, warpId: DeformerId): List<DrawableId> =
		model.drawables.filter { drawable -> drawable.parentDeformerId == warpId && drawable.mesh != null }.map { drawable -> drawable.id }

	/**
	 * The drawables' world positions at rest.
	 *
	 * @param PuppetModel model The model.
	 * @param List<DrawableId> drawableIds The drawables.
	 * @return List<FloatArray> Each one's world positions, in the order given.
	 */
	private fun restWorlds(model: PuppetModel, drawableIds: List<DrawableId>): List<FloatArray> = captureDrawableWorlds(model, emptyMap(), drawableIds).map { geometry -> geometry.world }

	/**
	 * The largest per-component difference between two lists of position arrays.
	 *
	 * @param List<FloatArray> first One list.
	 * @param List<FloatArray> second The other, in the same order.
	 * @return Float The largest difference.
	 */
	private fun worstDrift(first: List<FloatArray>, second: List<FloatArray>): Float {
		assertEquals(first.size, second.size, "the same arrays on both sides")
		var worst = 0f

		for ((left, right) in first.zip(second)) {
			assertEquals(left.size, right.size, "the same vertex counts on both sides")

			for (componentIndex in left.indices) {
				worst = maxOf(worst, abs(left[componentIndex] - right[componentIndex]))
			}
		}

		return worst
	}
}