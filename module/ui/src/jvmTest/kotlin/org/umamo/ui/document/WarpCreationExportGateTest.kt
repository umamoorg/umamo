package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.uma.UmaModel
import org.umamo.interop.ExportNotice
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.warpLatticeAt
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deformerChain
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.transform.createWarpAdjustable
import org.umamo.ui.transform.deleteTargetKeepingRest
import org.umamo.ui.transform.moveDeformerKeepingRest
import org.umamo.ui.viewport.AtlasPageBinding
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Structural warp edits over a real rig, through to the CMO3 the official editor opens, the UMA the editor saves, and
 * the MOC3 a runtime loads: a warp is created around keyed drawables and a child warp under the same warp, a second
 * warp is re-nested under a third, and a fourth warp with warps and keyed drawables under it is deleted - each on its
 * own subtree, each keeping the art it touches where it rested.  The export reopens with the created, re-nested, and
 * re-homed lattices and their art where the session has them at rest and at every parameter's extremes; the UMA round
 * trip carries the same; and the MOC3 carries the created warp's single keyform and rests its art where the session
 * does.  The CMO3 is written to build/warp-create for Cmo3ShapeGateTest and the official editor.
 *
 * Skips without the corpus sample.
 */
class WarpCreationExportGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun createdReNestedAndDeletedWarpsExportAndReopenWithTheirArtInPlace() =
		runBlocking {
			val file = sample

			if (file == null) {
				println("cmo3.sample not present; skipping the warp creation export gate")

				return@runBlocking
			}

			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(file.readBytes(), file.name, file.path)).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val base = session.model.value
			val shown = base.deformers.filterIsInstance<Deformer.Warp>().filter { warp -> warpLatticeAt(base, emptyMap(), warp.id) != null }
			val childWarpsOf = { parentId: DeformerId -> shown.filter { warp -> warp.parent == parentId && subtreeArt(base, warp.id).isNotEmpty() } }
			val keyedChildrenOf = { parentId: DeformerId -> base.drawables.filter { drawable -> drawable.parentDeformerId == parentId && drawable.mesh != null && drawable.geometryGrid != null }.map { drawable -> drawable.id } }

			// The created warp's home: a warp holding keyed drawables beside a child warp with art.
			val home = shown.filter { warp -> keyedChildrenOf(warp.id).size >= 2 && childWarpsOf(warp.id).isNotEmpty() }.maxBy { warp -> keyedChildrenOf(warp.id).size }
			val wrappedDrawables = keyedChildrenOf(home.id).take(3)
			val wrappedWarp = childWarpsOf(home.id).first()
			// The deleted warp: warps and keyed drawables under it, on a subtree of its own.
			val deleted =
				shown
					.filter { warp -> apart(base, warp.id, home.id) && childWarpsOf(warp.id).isNotEmpty() && keyedChildrenOf(warp.id).isNotEmpty() }
					.maxBy { warp -> childWarpsOf(warp.id).size + keyedChildrenOf(warp.id).size }
			// The re-nested warp and its new parent: apart from both, the parent's lattice around the warp's where one is.
			val reNested = shown.first { warp -> warp.parent != null && apart(base, warp.id, home.id) && apart(base, warp.id, deleted.id) && subtreeArt(base, warp.id).isNotEmpty() }
			val newParent =
				shown.firstOrNull { warp ->
					warp.id != reNested.parent && apart(base, warp.id, reNested.id) && !inSubtree(base, warp.id, home.id) && !inSubtree(base, warp.id, deleted.id) && encloses(base, warp.id, reNested.id)
				}

			val createdArt = (wrappedDrawables + subtreeArt(base, wrappedWarp.id)).distinct()
			val reNestedArt = subtreeArt(base, reNested.id)
			val deletedArt = subtreeArt(base, deleted.id)
			val reHomedWarps = childWarpsOf(deleted.id).map { warp -> warp.id }
			println(
				"[warp-create] ${file.name}: wrapping ${wrappedDrawables.size} keyed drawable(s) and ${wrappedWarp.name} under ${home.name}; " +
					"re-nesting ${reNested.name} under ${newParent?.name ?: "the root"}; deleting ${deleted.name} " +
					"(${reHomedWarps.size} warp(s), ${keyedChildrenOf(deleted.id).size} keyed drawable(s) under it)",
			)

			// Create.
			val wrapped = wrappedDrawables.map { drawableId -> SelectionTarget.Drawable(drawableId) } + SelectionTarget.Deformer(wrappedWarp.id)
			session.setSelection(Selection(wrapped.toSet(), wrapped.first()))
			session.createWarpAdjustable(null)
			val createdId = assertIs<SelectionTarget.Deformer>(session.selection.value.active, "the created warp is selected").id
			val created = assertIs<Deformer.Warp>(session.model.value.deformerById[createdId])
			assertEquals(home.id, created.parent, "the warp is created under the warp they share")
			assertTrue(assertNotNull(created.geometryGrid).axes.isEmpty(), "born with one keyform")
			assertNull(session.notice.value, "the create keeps everything in place: ${session.notice.value?.messageKey}")
			assertStayed(base, session.model.value, createdArt, "create")

			// Re-nest.
			val beforeReNest = session.model.value
			session.moveDeformerKeepingRest(reNested.id, newParent?.id, null)
			assertEquals(newParent?.id, session.model.value.deformerById[reNested.id]?.parent)
			assertNull(session.notice.value, "the re-nest keeps everything in place: ${session.notice.value?.messageKey}")
			assertStayed(beforeReNest, session.model.value, reNestedArt, "re-nest")

			// Delete.
			val beforeDelete = session.model.value
			session.deleteTargetKeepingRest(SelectionTarget.Deformer(deleted.id), cascade = false)
			assertNull(session.model.value.deformerById[deleted.id])
			assertNull(session.notice.value, "the delete keeps everything in place: ${session.notice.value?.messageKey}")
			assertStayed(beforeDelete, session.model.value, deletedArt, "delete")

			val after = session.model.value
			val warpIds = listOf(createdId, wrappedWarp.id, reNested.id) + reHomedWarps
			val art = (createdArt + reNestedArt + deletedArt).distinct()

			// The CMO3: written for the shape gate and the official editor, then reopened.
			val prepared = prepareCmo3Export(document, exportedModelFor(document, session), document.textures, "gate", 0L, 0)
			println("[warp-create]   export report: ${prepared.report.notices.map { notice -> notice::class.simpleName }}")
			assertTrue(prepared.report.isEmpty, "the export carries the edits whole: ${prepared.report.notices}")
			val exportedBytes = Cmo3.write(prepared.model)
			val exportFile = File("build/warp-create/${file.nameWithoutExtension}WarpCreateExportTest.cmo3")
			exportFile.parentFile.mkdirs()
			exportFile.writeBytes(exportedBytes)
			println("[warp-create]   wrote ${exportFile.absolutePath}")
			val reopened = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(exportedBytes, "gate.cmo3", "/art/gate.cmo3")).document).puppet
			assertTrue(assertNotNull(assertIs<Deformer.Warp>(reopened.deformerById[createdId]).geometryGrid).axes.isEmpty(), "the CMO3 reopens the created warp with its one keyform")
			assertSameRig(after, reopened, warpIds, art, "the CMO3")

			// The UMA: saved and reopened.
			val umaFile = File(Files.createTempDirectory("warp-create").toFile(), "created.uma")
			val written = writeUmaDocument(document, UmaModel.create(umamoWriterInfo()), after, AtlasPageBinding(after.atlas, document.textures), JsonObject(emptyMap()), PlatformFile(umaFile))
			assertIs<UmaWriteOutcome.Written>(written, "the save succeeds: ${(written as? UmaWriteOutcome.Failed)?.reason}")
			val umaReopened = assertIs<DocumentLoad.Loaded>(loadDocument(umaFile.readBytes(), umaFile.name, umaFile.path)).document as PuppetDocument
			assertSameRig(after, umaReopened.puppet, warpIds, art, "the UMA")

			// The MOC3: the created warp is written with its single keyform and its art rests where the session has it.
			val lowered = Moc3Export.toMocDocument(after)
			assertTrue(lowered.document.deformers.any { deformer -> deformer.id == createdId.raw }, "the MOC3 carries the created warp")
			assertTrue(lowered.report.notices.none { notice -> notice is ExportNotice.UnsupportedChange && notice.subject == createdId.raw }, "nothing about the created warp is unsupported")
			val mocReopened = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(lowered.document, displayInfo = null))
			assertNotNull(warpLatticeAt(mocReopened, emptyMap(), createdId), "the MOC3's created warp evaluates at rest")
			val sessionCanvas = restMeshesToCanvasSpace(after).drawables.associateBy { drawable -> drawable.id }
			val mocCanvas = mocReopened.drawables.associateBy { drawable -> drawable.id }
			val compared = createdArt.filter { drawableId -> mocCanvas[drawableId]?.mesh != null }
			val mocDrift = worstDrift(compared.map { drawableId -> sessionCanvas.getValue(drawableId).mesh!!.positions }, compared.map { drawableId -> mocCanvas.getValue(drawableId).mesh!!.positions })
			println("[warp-create]   the MOC3 rests ${compared.size} of ${createdArt.size} wrapped drawable(s) within $mocDrift px")
			assertTrue(compared.isNotEmpty(), "the MOC3 writes the wrapped art")
			assertTrue(mocDrift <= 0.1f, "the MOC3 rests the wrapped art where the session does ($mocDrift px)")
		}

	/**
	 * Asserts [drawableIds] rest in [after] where they rested in [before], and reports how far they stray at each
	 * parameter's extremes.  Nothing bounds that: re-parented art follows its new parent's motion, a deleted warp's own
	 * motion is gone, and a created warp carries its parent's motion to the art only at its own control points.
	 *
	 * @param PuppetModel before The model before the edit.
	 * @param PuppetModel after The model after it.
	 * @param List<DrawableId> drawableIds The art the edit touched.
	 * @param String edit The edit, for the messages.
	 */
	private fun assertStayed(before: PuppetModel, after: PuppetModel, drawableIds: List<DrawableId>, edit: String) {
		val restDrift = worstDrift(worldsAt(before, emptyMap(), drawableIds), worldsAt(after, emptyMap(), drawableIds))
		val extremes = before.parameters.flatMap { parameter -> listOf(mapOf(parameter.id to parameter.min), mapOf(parameter.id to parameter.max)) }
		val posedDrift = extremes.maxOfOrNull { pose -> worstDrift(worldsAt(before, pose, drawableIds), worldsAt(after, pose, drawableIds)) } ?: 0f
		println("[warp-create]   $edit: ${drawableIds.size} drawable(s) moved at most $restDrift px at rest, $posedDrift px at the extremes")
		assertTrue(restDrift <= 0.1f, "$edit: the art rests where it rested (moved $restDrift px)")
	}

	/**
	 * Asserts [reopened] draws the warps and the art where [after] does: at rest, and with each parameter at its
	 * minimum and its maximum.
	 *
	 * @param PuppetModel after The session's model.
	 * @param PuppetModel reopened The reopened model.
	 * @param List<DeformerId> warpIds The warps the edits touched.
	 * @param List<DrawableId> art The art under them.
	 * @param String format What was reopened.
	 */
	private fun assertSameRig(after: PuppetModel, reopened: PuppetModel, warpIds: List<DeformerId>, art: List<DrawableId>, format: String) {
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

			artDrift = maxOf(artDrift, worstDrift(worldsAt(after, pose, art), worldsAt(reopened, pose, art)))
		}

		println("[warp-create]   $format reopens with the lattices within $latticeDrift px and the art within $artDrift px over ${poses.size} poses")
		assertTrue(latticeDrift <= 1e-2f, "$format: the lattices reopen where the session has them ($latticeDrift px)")
		assertTrue(artDrift <= 1e-2f, "$format: the art reopens where the session has it ($artDrift px)")
	}

	/**
	 * Every drawable with a mesh under [deformerId], at any depth.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId deformerId The deformer.
	 * @return List<DrawableId> The drawables.
	 */
	private fun subtreeArt(model: PuppetModel, deformerId: DeformerId): List<DrawableId> =
		model.drawables
			.filter { drawable -> drawable.mesh != null && drawable.parentDeformerId?.let { parentId -> model.deformerChain(parentId).any { ancestor -> ancestor.id == deformerId } } == true }
			.map { drawable -> drawable.id }

	/**
	 * Whether [deformerId] is [ancestorId] or sits under it.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId deformerId The deformer.
	 * @param DeformerId ancestorId The would-be ancestor.
	 * @return Boolean True inside the subtree.
	 */
	private fun inSubtree(model: PuppetModel, deformerId: DeformerId, ancestorId: DeformerId): Boolean = model.deformerChain(deformerId).any { deformer -> deformer.id == ancestorId }

	/**
	 * Whether neither deformer sits in the other's subtree, so an edit to one leaves the other's space alone.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId first One deformer.
	 * @param DeformerId second The other.
	 * @return Boolean True when apart.
	 */
	private fun apart(model: PuppetModel, first: DeformerId, second: DeformerId): Boolean = !inSubtree(model, first, second) && !inSubtree(model, second, first)

	/**
	 * Whether warp [outerId]'s lattice bounds hold warp [innerId]'s at rest.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId outerId The would-be parent.
	 * @param DeformerId innerId The warp to hold.
	 * @return Boolean True when the bounds hold.
	 */
	private fun encloses(model: PuppetModel, outerId: DeformerId, innerId: DeformerId): Boolean {
		val outer = warpLatticeAt(model, emptyMap(), outerId)?.world ?: return false
		val inner = warpLatticeAt(model, emptyMap(), innerId)?.world ?: return false
		val outerXs = outer.filterIndexed { index, _ -> index % 2 == 0 }
		val outerZs = outer.filterIndexed { index, _ -> index % 2 == 1 }

		return inner.withIndex().all { (index, value) -> if (index % 2 == 0) value in outerXs.min()..outerXs.max() else value in outerZs.min()..outerZs.max() }
	}

	/**
	 * The drawables' world positions at [pose].
	 *
	 * @param PuppetModel model The model.
	 * @param Map pose The pose.
	 * @param List<DrawableId> drawableIds The drawables.
	 * @return List<FloatArray> Each one's world positions, in the order given.
	 */
	private fun worldsAt(model: PuppetModel, pose: Map<ParameterId, Float>, drawableIds: List<DrawableId>): List<FloatArray> = captureDrawableWorlds(model, pose, drawableIds).map { geometry -> geometry.world }

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