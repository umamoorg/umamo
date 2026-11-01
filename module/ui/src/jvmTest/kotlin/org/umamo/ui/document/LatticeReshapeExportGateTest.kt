package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.mesh.commitLatticeReshape
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.uma.UmaModel
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.transform.latticeReshapeOf
import org.umamo.ui.viewport.AtlasPageBinding
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * An Edit-mode lattice reshape over a real rig, through to the CMO3 the official editor opens and the UMA the editor
 * saves: the corpus warp with the most children has an interior point dragged within its cell, the reshape commits, and the export
 * reopens with the warp's lattice where the session has it at rest and at every parameter's extremes, and with its
 * children's art resting where it rested before the reshape.  The UMA round trip carries the same.  The export is
 * written to build/lattice-reshape for Cmo3ShapeGateTest and the official editor.
 *
 * Skips without the corpus sample.
 */
class LatticeReshapeExportGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aReshapedWarpExportsAndReopensWithItsChildrenInPlace() =
		runBlocking {
			val file = sample

			if (file == null) {
				println("cmo3.sample not present; skipping the lattice reshape export gate")

				return@runBlocking
			}

			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(file.readBytes(), file.name, file.path)).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val base = session.model.value
			val warp =
				base.deformers
					.filterIsInstance<Deformer.Warp>()
					.filter { candidate -> candidate.rows >= 2 && candidate.columns >= 2 && warpLatticeAt(base, emptyMap(), candidate.id) != null }
					.maxBy { candidate -> childDrawableIds(base, candidate.id).size + base.deformers.count { deformer -> deformer.parent == candidate.id } }
			val children = childDrawableIds(base, warp.id)
			val childWarps = base.deformers.filter { deformer -> deformer.parent == warp.id && deformer is Deformer.Warp }.map { deformer -> deformer.id }
			assertTrue(children.isNotEmpty(), "the gate needs a warp with art under it")

			// An interior point nudged by a fraction of one cell, so no cell folds over the art under it.
			val lattice = assertNotNull(warpLatticeAt(base, emptyMap(), warp.id))
			val pointIndex = (lattice.rows / 2) * (lattice.columns + 1) + lattice.columns / 2
			val width = (0 until lattice.world.size / 2).maxOf { index -> lattice.world[index * 2] } - (0 until lattice.world.size / 2).minOf { index -> lattice.world[index * 2] }
			val target = lattice.world.copyOf()
			val cellWidth = width / lattice.columns
			target[pointIndex * 2] += cellWidth * 0.3f
			target[pointIndex * 2 + 1] -= cellWidth * 0.2f
			val reshape = latticeReshapeOf(base, mapOf(warp.id to target))
			session.commitLatticeReshape(DeformerChange.ReshapeLattice(listOf(warp.id), MeshOperatorKind.Grab), reshape.model, reshape.unconvertedCount)
			val after = session.model.value
			assertNotSame(base, after, "the reshape landed")
			assertEquals(0, reshape.unconvertedCount, "a drag inside a cell folds nothing")
			println(
				"[lattice-reshape] ${file.name}: ${warp.name} (${warp.rows}x${warp.columns}), ${children.size} drawable(s) and " +
					"${base.deformers.count { deformer -> deformer.parent == warp.id }} deformer(s) under it, ${reshape.unconvertedCount} unconverted",
			)

			val restDrift = worstDrift(restWorlds(base, children), restWorlds(after, children))
			println("[lattice-reshape]   the children's art moved at most $restDrift px at rest")
			assertTrue(restDrift <= 0.1f, "the children rest where they rested (moved $restDrift px)")
			assertTrue(worstDrift(childWarps.map { id -> restLattice(base, id) }, childWarps.map { id -> restLattice(after, id) }) <= 0.1f, "and so do the warps under it")

			// The CMO3: written for the shape gate and the official editor, then reopened.
			val prepared = prepareCmo3Export(document, exportedModelFor(document, session), document.textures, "gate", 0L, 0)
			println("[lattice-reshape]   export report: ${prepared.report.notices.map { notice -> notice::class.simpleName }}")
			assertTrue(prepared.report.isEmpty, "the export carries the reshape whole: ${prepared.report.notices}")
			val exportedBytes = Cmo3.write(prepared.model)
			val exportFile = File("build/lattice-reshape/${file.nameWithoutExtension}LatticeReshapeExportTest.cmo3")
			exportFile.parentFile.mkdirs()
			exportFile.writeBytes(exportedBytes)
			println("[lattice-reshape]   wrote ${exportFile.absolutePath}")
			val reopened = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(exportedBytes, "gate.cmo3", "/art/gate.cmo3")).document).puppet
			assertSameRig(after, reopened, warp.id, children, "the CMO3")

			// The UMA: saved and reopened.
			val umaFile = File(Files.createTempDirectory("lattice-reshape").toFile(), "reshaped.uma")
			val written = writeUmaDocument(document, UmaModel.create(umamoWriterInfo()), after, AtlasPageBinding(after.atlas, document.textures), JsonObject(emptyMap()), PlatformFile(umaFile))
			assertIs<UmaWriteOutcome.Written>(written, "the save succeeds: ${(written as? UmaWriteOutcome.Failed)?.reason}")
			val umaReopened = assertIs<DocumentLoad.Loaded>(loadDocument(umaFile.readBytes(), umaFile.name, umaFile.path)).document as PuppetDocument
			assertSameRig(after, umaReopened.puppet, warp.id, children, "the UMA")
		}

	/**
	 * Asserts [reopened] draws the reshaped warp's lattice and its children's art where [after] does: at rest, and
	 * with each parameter at its minimum and its maximum.
	 *
	 * @param PuppetModel after The session's model.
	 * @param PuppetModel reopened The reopened model.
	 * @param DeformerId warpId The reshaped warp.
	 * @param List<DrawableId> children The art under it.
	 * @param String format What was reopened.
	 */
	private fun assertSameRig(after: PuppetModel, reopened: PuppetModel, warpId: DeformerId, children: List<DrawableId>, format: String) {
		val poses = listOf(emptyMap<ParameterId, Float>()) + after.parameters.flatMap { parameter -> listOf(mapOf(parameter.id to parameter.min), mapOf(parameter.id to parameter.max)) }
		var latticeDrift = 0f
		var artDrift = 0f

		for (pose in poses) {
			val sessionLattice = warpLatticeAt(after, pose, warpId)?.world
			val reopenedLattice = warpLatticeAt(reopened, pose, warpId)?.world
			assertEquals(sessionLattice == null, reopenedLattice == null, "$format: the warp is drawn at $pose exactly when the session draws it")

			if (sessionLattice != null && reopenedLattice != null) {
				latticeDrift = maxOf(latticeDrift, worstDrift(listOf(sessionLattice), listOf(reopenedLattice)))
			}

			artDrift = maxOf(artDrift, worstDrift(captureDrawableWorlds(after, pose, children).map { geometry -> geometry.world }, captureDrawableWorlds(reopened, pose, children).map { geometry -> geometry.world }))
		}

		println("[lattice-reshape]   $format reopens with the lattice within $latticeDrift px and the art within $artDrift px over ${poses.size} poses")
		assertTrue(latticeDrift <= 1e-2f, "$format: the reshaped lattice reopens where the session has it ($latticeDrift px)")
		assertTrue(artDrift <= 1e-2f, "$format: the art under it reopens where the session has it ($artDrift px)")
	}

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
	 * A warp's lattice in world space at rest.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId warpId The warp.
	 * @return FloatArray The world points, empty when the rest pose does not draw it.
	 */
	private fun restLattice(model: PuppetModel, warpId: DeformerId): FloatArray = warpLatticeAt(model, emptyMap(), warpId)?.world ?: FloatArray(0)

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