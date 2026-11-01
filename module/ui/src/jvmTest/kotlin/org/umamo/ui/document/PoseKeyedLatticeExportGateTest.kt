package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.umamo.edit.DeformerChange
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.KeyformAim
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ParameterChange
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.keyform.captureKeysOnTracks
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.uma.UmaModel
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.ElementEditStage
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.transform.posedLatticeForms
import org.umamo.ui.viewport.AtlasPageBinding
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pose-mode lattices keyed over a real rig, through to the CMO3 the official editor opens and the UMA the editor saves.
 * On the corpus sample, at a pose between two keys of a parameter: the root warp with the most art under it has half
 * its points shaped and keyed on that parameter, and a warp nested in it has a point shaped and keyed on a parameter
 * it was never keyed on, which seeds the axis.  Each keyed lattice shows the shape at the pose, and the export reopens
 * with both lattices and the art under them where the session has them - at the pose, at rest, and at every
 * parameter's extremes - and so does the UMA.  The export is written to build/pose-keyed for Cmo3ShapeGateTest and
 * the official editor.
 *
 * Skips without the corpus sample.
 */
class PoseKeyedLatticeExportGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun posedLatticesKeyAndReopenInPlace() =
		runBlocking {
			val file = sample

			if (file == null) {
				println("cmo3.sample not present; skipping the pose-keyed lattice export gate")

				return@runBlocking
			}

			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(file.readBytes(), file.name, file.path)).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val base = session.model.value
			val drawn = base.deformers.filterIsInstance<Deformer.Warp>().filter { warp -> warpLatticeAt(base, emptyMap(), warp.id) != null }

			// The root warp with the most art under it, keyed on a parameter with two keys to pose between.
			val outer = drawn.filter { warp -> warp.parent == null && warp.geometryGrid?.axes?.any { axis -> axis.keys.size >= 2 } == true }.maxBy { warp -> artUnderWarps(base, warp.id).size }
			val outerAxis = assertNotNull(outer.geometryGrid).axes.first { axis -> axis.keys.size >= 2 }
			val between = (outerAxis.keys[0] + outerAxis.keys[1]) / 2f
			// A warp nested in it, and a parameter that warp is not keyed on.
			val inner = drawn.first { warp -> warp.parent == outer.id && artUnderWarps(base, warp.id).isNotEmpty() }
			val innerAxes = assertNotNull(inner.geometryGrid).axes.map { axis -> axis.parameterId }.toSet()
			val seeded = base.parameters.first { parameter -> parameter.id !in innerAxes && parameter.id != outerAxis.parameterId && parameter.max - parameter.min > 1e-3f }
			val seededValue = seeded.min + (seeded.max - seeded.min) * 0.7f
			val pose = mapOf(outerAxis.parameterId to between, seeded.id to seededValue)
			session.commitPose(ParameterChange.SetValue(pose.keys.toList()), session.pose.value + pose)
			session.setSelection(Selection(setOf(SelectionTarget.Deformer(outer.id), SelectionTarget.Deformer(inner.id)), SelectionTarget.Deformer(outer.id)))
			session.setMode(EditorMode.Pose)
			val shownPose = session.pose.value

			// Shape: half the outer warp's points eight units right and five down, then one of the inner warp's points.
			val outerWorld = assertNotNull(warpLatticeAt(session.model.value, shownPose, outer.id)).world
			val outerMoved = (0 until outerWorld.size / 2).filter { pointIndex -> pointIndex % 2 == 0 }.toSet()
			val outerTarget = shifted(outerWorld, outerMoved, 8f, -5f)
			shape(session, outer.id, outerTarget, outerMoved)
			val innerWorld = assertNotNull(warpLatticeAt(session.model.value, shownPose, inner.id, session.pendingLatticeEdits.value)).world
			val innerTarget = shifted(innerWorld, setOf(0), -4f, 3f)
			shape(session, inner.id, innerTarget, setOf(0))
			assertEquals(setOf(outer.id, inner.id), session.pendingLatticeEdits.value.keys)

			// Key: the outer lattice on its own parameter, the inner one on the parameter it gains.
			session.captureKeysOnTracks(listOf(KeyformTrackRef.Geometry(KeyformOwner.Deformer(outer.id))), outerAxis.parameterId, KeyformAim.Pose)
			session.captureKeysOnTracks(listOf(KeyformTrackRef.Geometry(KeyformOwner.Deformer(inner.id))), seeded.id, KeyformAim.Pose)
			assertTrue(session.pendingLatticeEdits.value.isEmpty(), "the keys consumed both shapes")
			val after = session.model.value
			val keyedOuter = after.deformers.first { deformer -> deformer.id == outer.id } as Deformer.Warp
			val keyedInner = after.deformers.first { deformer -> deformer.id == inner.id } as Deformer.Warp
			assertTrue(assertNotNull(keyedOuter.geometryGrid).axes.first { axis -> axis.parameterId == outerAxis.parameterId }.keys.any { key -> abs(key - between) < 1e-4f }, "the outer warp gained a key between its two")
			assertTrue(assertNotNull(keyedInner.geometryGrid).axes.any { axis -> axis.parameterId == seeded.id }, "the inner warp gained the parameter as an axis")
			val outerDrift = worstDrift(listOf(outerTarget), listOf(assertNotNull(warpLatticeAt(after, shownPose, outer.id)).world))
			val innerDrift = worstDrift(listOf(innerTarget), listOf(assertNotNull(warpLatticeAt(after, shownPose, inner.id)).world))
			println("[pose-keyed] ${file.name}: ${outer.name} keyed at ${outerAxis.parameterId.raw} = $between ($outerDrift px), ${inner.name} keyed on ${seeded.id.raw} = $seededValue ($innerDrift px)")
			assertTrue(outerDrift <= 1e-2f, "the outer lattice shows the shape at the pose ($outerDrift px)")
			assertTrue(innerDrift <= 0.1f, "the inner lattice shows the shape at the pose ($innerDrift px)")

			val checked = listOf(outer.id, inner.id)
			val checkedArt = artUnderWarps(after, outer.id)
			val poses = listOf(shownPose, emptyMap<ParameterId, Float>()) + after.parameters.flatMap { parameter -> listOf(mapOf(parameter.id to parameter.min), mapOf(parameter.id to parameter.max)) }

			// The CMO3: written for the shape gate and the official editor, then reopened.
			val prepared = prepareCmo3Export(document, exportedModelFor(document, session), document.textures, "gate", 0L, 0)
			println("[pose-keyed]   export report: ${prepared.report.notices.map { notice -> notice::class.simpleName }}")
			assertTrue(prepared.report.isEmpty, "the export carries both keys whole: ${prepared.report.notices}")
			val exportedBytes = Cmo3.write(prepared.model)
			val exportFile = File("build/pose-keyed/${file.nameWithoutExtension}PoseKeyedLatticeExportTest.cmo3")
			exportFile.parentFile.mkdirs()
			exportFile.writeBytes(exportedBytes)
			println("[pose-keyed]   wrote ${exportFile.absolutePath}")
			val reopened = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(exportedBytes, "gate.cmo3", "/art/gate.cmo3")).document).puppet
			assertSameRig(after, reopened, checked, checkedArt, poses, "the CMO3")

			// The UMA: saved and reopened.
			val umaFile = File(Files.createTempDirectory("pose-keyed").toFile(), "keyed.uma")
			val written = writeUmaDocument(document, UmaModel.create(umamoWriterInfo()), after, AtlasPageBinding(after.atlas, document.textures), JsonObject(emptyMap()), PlatformFile(umaFile))
			assertIs<UmaWriteOutcome.Written>(written, "the save succeeds: ${(written as? UmaWriteOutcome.Failed)?.reason}")
			val umaReopened = assertIs<DocumentLoad.Loaded>(loadDocument(umaFile.readBytes(), umaFile.name, umaFile.path)).document as PuppetDocument
			assertSameRig(after, umaReopened.puppet, checked, checkedArt, poses, "the UMA")
		}

	/**
	 * Shapes warp [warpId] at the session's pose so the points in [moved] land on [target], and holds it pending, as a
	 * finished Pose-mode Grab does.
	 *
	 * @param EditorSession session The session, in Pose mode.
	 * @param DeformerId warpId The warp.
	 * @param FloatArray target Its points in world space, the moved ones at their targets.
	 * @param Set<Int> moved The moved points.
	 */
	private fun shape(
		session: EditorSession,
		warpId: DeformerId,
		target: FloatArray,
		moved: Set<Int>,
	) {
		val stage = ElementEditStage.Posed(session.pose.value, session.pendingLatticeEdits.value)
		val forms = posedLatticeForms(session.model.value, stage, mapOf(warpId to target), mapOf(warpId to moved))
		assertTrue(warpId in forms, "${warpId.raw} lowered")
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(warpId), MeshOperatorKind.Grab), forms)
	}

	/**
	 * Asserts [reopened] draws each warp in [warpIds] and the art in [artIds] where [after] does at every pose in [poses].
	 *
	 * @param PuppetModel after The session's model.
	 * @param PuppetModel reopened The reopened model.
	 * @param List<DeformerId> warpIds The keyed warps.
	 * @param List<DrawableId> artIds The art under them.
	 * @param List<Map<ParameterId, Float>> poses The poses to compare at.
	 * @param String format What was reopened.
	 */
	private fun assertSameRig(
		after: PuppetModel,
		reopened: PuppetModel,
		warpIds: List<DeformerId>,
		artIds: List<DrawableId>,
		poses: List<Map<ParameterId, Float>>,
		format: String,
	) {
		var latticeDrift = 0f
		var artDrift = 0f

		for (pose in poses) {
			for (warpId in warpIds) {
				val sessionLattice = warpLatticeAt(after, pose, warpId)?.world
				val reopenedLattice = warpLatticeAt(reopened, pose, warpId)?.world
				assertEquals(sessionLattice == null, reopenedLattice == null, "$format: ${warpId.raw} is drawn at $pose exactly when the session draws it")

				if (sessionLattice != null && reopenedLattice != null) {
					latticeDrift = maxOf(latticeDrift, worstDrift(listOf(sessionLattice), listOf(reopenedLattice)))
				}
			}

			artDrift = maxOf(artDrift, worstDrift(captureDrawableWorlds(after, pose, artIds).map { geometry -> geometry.world }, captureDrawableWorlds(reopened, pose, artIds).map { geometry -> geometry.world }))
		}

		println("[pose-keyed]   $format reopens with the lattices within $latticeDrift px and the art within $artDrift px over ${poses.size} poses")
		assertTrue(latticeDrift <= 1e-2f, "$format: the keyed lattices reopen where the session has them ($latticeDrift px)")
		assertTrue(artDrift <= 1e-2f, "$format: the art under them reopens where the session has it ($artDrift px)")
	}

	/**
	 * The meshed drawables under [warpId] through warps alone: its own, and those of every warp nested in it.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId warpId The warp.
	 * @return List<DrawableId> The drawables.
	 */
	private fun artUnderWarps(
		model: PuppetModel,
		warpId: DeformerId,
	): List<DrawableId> {
		val warps = HashSet<DeformerId>().apply { add(warpId) }
		var grew = true

		while (grew) {
			grew = model.deformers.filter { deformer -> deformer is Deformer.Warp && deformer.parent in warps }.any { deformer -> warps.add(deformer.id) }
		}

		return model.drawables.filter { drawable -> drawable.parentDeformerId in warps && drawable.mesh != null }.map { drawable -> drawable.id }
	}

	/**
	 * [world] with each point in [moved] shifted by [dx], [dz].
	 *
	 * @param FloatArray world The points.
	 * @param Set<Int> moved The points to shift.
	 * @param Float dx The x shift.
	 * @param Float dz The z shift.
	 * @return FloatArray The shifted points (a new array).
	 */
	private fun shifted(
		world: FloatArray,
		moved: Set<Int>,
		dx: Float,
		dz: Float,
	): FloatArray =
		FloatArray(world.size) { componentIndex ->
			when {
				componentIndex / 2 !in moved -> world[componentIndex]
				componentIndex % 2 == 0 -> world[componentIndex] + dx
				else -> world[componentIndex] + dz
			}
		}

	/**
	 * The largest per-component difference between two lists of position arrays.
	 *
	 * @param List<FloatArray> first One list.
	 * @param List<FloatArray> second The other, in the same order.
	 * @return Float The largest difference.
	 */
	private fun worstDrift(
		first: List<FloatArray>,
		second: List<FloatArray>,
	): Float {
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