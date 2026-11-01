package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.mesh.commitObjectTransform
import org.umamo.edit.structure.duplicateSelectedObjects
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.uma.UmaModel
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.transform.withWarpsTransformed
import org.umamo.ui.viewport.AtlasPageBinding
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Object-mode moves of whole warps over a real rig, through to the CMO3 the official editor opens and the UMA the
 * editor saves.  On the corpus sample: the root warp with the most art under it turns and grows as an object, every
 * drawable reached through warps alone landing on the world affine of where it was; a warp nested in another warp
 * moves, its rest lattice landing on the affine of its rest; and a warp is duplicated with its art and the copy moved
 * away.  The export reopens with every one of those lattices and their art where the session has them, at rest and at
 * every parameter's extremes, and so does the UMA.  The export is written to build/object-warp for Cmo3ShapeGateTest
 * and the official editor.
 *
 * Skips without the corpus sample.
 */
class ObjectWarpTransformExportGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun movedNestedAndDuplicatedWarpsExportAndReopenInPlace() =
		runBlocking {
			val file = sample

			if (file == null) {
				println("cmo3.sample not present; skipping the object warp export gate")

				return@runBlocking
			}

			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(file.readBytes(), file.name, file.path)).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val base = session.model.value
			val drawn = base.deformers.filterIsInstance<Deformer.Warp>().filter { warp -> warpLatticeAt(base, emptyMap(), warp.id) != null }

			// The root warp with the most art under it turns 12 degrees about its lattice's center and grows by a tenth.
			val rootWarp = drawn.filter { warp -> warp.parent == null }.maxBy { warp -> artUnderWarps(base, warp.id).size }
			val rootArt = artUnderWarps(base, rootWarp.id)
			val rootTurn = turnAndGrowAbout(centerOf(restLattice(base, rootWarp.id)), 12f, 1.1f)
			val rootArtBefore = restWorlds(base, rootArt)
			commit(session, rootWarp.id, rootTurn, MeshOperatorKind.Rotate)
			val rootDrift = worstDrift(rootArtBefore.map { world -> moved(world, rootTurn) }, restWorlds(session.model.value, rootArt))
			println("[object-warp] ${file.name}: ${rootWarp.name} with ${rootArt.size} drawable(s) under warps alone turned; art off the affine by at most $rootDrift px")
			assertTrue(rootDrift <= 0.1f, "the art under the turned warp lands on the affine ($rootDrift px)")

			// A warp nested in another warp moves twenty units right and grows by a twentieth.
			val nested = drawn.filter { warp -> warp.parent in drawn.map { candidate -> candidate.id } && warp.id != rootWarp.id && warp.parent != rootWarp.id }.maxBy { warp -> artUnderWarps(base, warp.id).size }
			val nestedShift = turnAndGrowAbout(centerOf(restLattice(session.model.value, nested.id)), 0f, 1.05f).also { affine -> affine[2] += 20f }
			val nestedBefore = restLattice(session.model.value, nested.id)
			commit(session, nested.id, nestedShift, MeshOperatorKind.Grab)
			val nestedDrift = worstDrift(listOf(moved(nestedBefore, nestedShift)), listOf(restLattice(session.model.value, nested.id)))
			println("[object-warp]   ${nested.name} (under a warp) moved; its rest lattice off the affine by at most $nestedDrift px")
			assertTrue(nestedDrift <= 0.1f, "the nested warp's rest lattice lands on the affine ($nestedDrift px)")

			// A warp with art under it is duplicated with that art, and the copy moved away as Object mode's Shift+D does.
			val duplicated = drawn.first { warp -> warp.id != rootWarp.id && warp.id != nested.id && base.drawables.any { drawable -> drawable.parentDeformerId == warp.id && drawable.mesh != null } }
			val duplicatedArt = session.model.value.drawables.filter { drawable -> drawable.parentDeformerId == duplicated.id && drawable.mesh != null }.map { drawable -> drawable.id }
			session.setSelection(Selection((duplicatedArt.map { id -> SelectionTarget.Drawable(id) } + SelectionTarget.Deformer(duplicated.id)).toSet(), SelectionTarget.Deformer(duplicated.id)))
			session.duplicateSelectedObjects()
			val copyId = DeformerId("${duplicated.id.raw}.001")
			commit(session, copyId, floatArrayOf(1f, 0f, 60f, 0f, 1f, -40f), MeshOperatorKind.Grab)
			val copyArt = session.model.value.drawables.filter { drawable -> drawable.parentDeformerId == copyId }.map { drawable -> drawable.id }
			assertEquals(duplicatedArt.size, copyArt.size, "every selected drawable was copied inside the copy")
			println("[object-warp]   ${duplicated.name} duplicated with ${copyArt.size} drawable(s) and the copy moved")

			val after = session.model.value
			val checked = listOf(rootWarp.id, nested.id, copyId)
			val checkedArt = rootArt + artUnderWarps(after, nested.id) + copyArt

			// The CMO3: written for the shape gate and the official editor, then reopened.
			val prepared = prepareCmo3Export(document, exportedModelFor(document, session), document.textures, "gate", 0L, 0)
			println("[object-warp]   export report: ${prepared.report.notices.map { notice -> notice::class.simpleName }}")
			assertTrue(prepared.report.isEmpty, "the export carries every move whole: ${prepared.report.notices}")
			val exportedBytes = Cmo3.write(prepared.model)
			val exportFile = File("build/object-warp/${file.nameWithoutExtension}ObjectWarpExportTest.cmo3")
			exportFile.parentFile.mkdirs()
			exportFile.writeBytes(exportedBytes)
			println("[object-warp]   wrote ${exportFile.absolutePath}")
			val reopened = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(exportedBytes, "gate.cmo3", "/art/gate.cmo3")).document).puppet
			assertSameRig(after, reopened, checked, checkedArt, "the CMO3")

			// The UMA: saved and reopened.
			val umaFile = File(Files.createTempDirectory("object-warp").toFile(), "moved.uma")
			val written = writeUmaDocument(document, UmaModel.create(umamoWriterInfo()), after, AtlasPageBinding(after.atlas, document.textures), JsonObject(emptyMap()), PlatformFile(umaFile))
			assertIs<UmaWriteOutcome.Written>(written, "the save succeeds: ${(written as? UmaWriteOutcome.Failed)?.reason}")
			val umaReopened = assertIs<DocumentLoad.Loaded>(loadDocument(umaFile.readBytes(), umaFile.name, umaFile.path)).document as PuppetDocument
			assertSameRig(after, umaReopened.puppet, checked, checkedArt, "the UMA")
		}

	/**
	 * Moves warp [warpId] as a whole by [affine] and commits it as one Object-mode step.
	 *
	 * @param EditorSession session The session.
	 * @param DeformerId warpId The warp.
	 * @param FloatArray affine The world affine.
	 * @param MeshOperatorKind kind The operator the move files as.
	 */
	private fun commit(session: EditorSession, warpId: DeformerId, affine: FloatArray, kind: MeshOperatorKind) {
		val model = session.model.value
		val moved = model.withWarpsTransformed(mapOf(warpId to affine))
		assertTrue(moved !== model, "${warpId.raw} moved")
		session.commitObjectTransform(MeshChange.TransformObjects(emptyList(), kind, listOf(warpId)), moved)
	}

	/**
	 * Asserts [reopened] draws each warp in [warpIds] and the art in [artIds] where [after] does: at rest, and with each
	 * parameter at its minimum and its maximum.
	 *
	 * @param PuppetModel after The session's model.
	 * @param PuppetModel reopened The reopened model.
	 * @param List<DeformerId> warpIds The moved warps.
	 * @param List<DrawableId> artIds The art under them.
	 * @param String format What was reopened.
	 */
	private fun assertSameRig(after: PuppetModel, reopened: PuppetModel, warpIds: List<DeformerId>, artIds: List<DrawableId>, format: String) {
		val poses = listOf(emptyMap<ParameterId, Float>()) + after.parameters.flatMap { parameter -> listOf(mapOf(parameter.id to parameter.min), mapOf(parameter.id to parameter.max)) }
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

		println("[object-warp]   $format reopens with the lattices within $latticeDrift px and the art within $artDrift px over ${poses.size} poses")
		assertTrue(latticeDrift <= 1e-2f, "$format: the moved lattices reopen where the session has them ($latticeDrift px)")
		assertTrue(artDrift <= 1e-2f, "$format: the art under them reopens where the session has it ($artDrift px)")
	}

	/**
	 * The meshed drawables under [warpId] through warps alone: its own, and those of every warp nested in it.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId warpId The warp.
	 * @return List<DrawableId> The drawables.
	 */
	private fun artUnderWarps(model: PuppetModel, warpId: DeformerId): List<DrawableId> {
		val warps = HashSet<DeformerId>().apply { add(warpId) }
		var grew = true

		while (grew) {
			grew = model.deformers.filter { deformer -> deformer is Deformer.Warp && deformer.parent in warps }.any { deformer -> warps.add(deformer.id) }
		}

		return model.drawables.filter { drawable -> drawable.parentDeformerId in warps && drawable.mesh != null }.map { drawable -> drawable.id }
	}

	/**
	 * A world affine that turns by [degrees] and scales by [factor] about [center].
	 *
	 * @param Pair<Float, Float> center The pivot.
	 * @param Float degrees The turn.
	 * @param Float factor The scale.
	 * @return FloatArray The affine.
	 */
	private fun turnAndGrowAbout(center: Pair<Float, Float>, degrees: Float, factor: Float): FloatArray {
		val radians = degrees * PI.toFloat() / 180f
		val a = cos(radians) * factor
		val b = -sin(radians) * factor
		val d = sin(radians) * factor
		val e = cos(radians) * factor

		return floatArrayOf(a, b, center.first - a * center.first - b * center.second, d, e, center.second - d * center.first - e * center.second)
	}

	/**
	 * The mean of a lattice's points.
	 *
	 * @param FloatArray world The world points.
	 * @return Pair<Float, Float> The centroid.
	 */
	private fun centerOf(world: FloatArray): Pair<Float, Float> {
		val count = world.size / 2

		return (0 until count).sumOf { index -> world[index * 2].toDouble() }.toFloat() / count to (0 until count).sumOf { index -> world[index * 2 + 1].toDouble() }.toFloat() / count
	}

	/**
	 * [world] moved by [affine].
	 *
	 * @param FloatArray world Interleaved x, z points.
	 * @param FloatArray affine The affine.
	 * @return FloatArray The moved points.
	 */
	private fun moved(world: FloatArray, affine: FloatArray): FloatArray =
		FloatArray(world.size) { componentIndex ->
			val x = world[componentIndex - componentIndex % 2]
			val z = world[componentIndex - componentIndex % 2 + 1]

			if (componentIndex % 2 == 0) affine[0] * x + affine[1] * z + affine[2] else affine[3] * x + affine[4] * z + affine[5]
		}

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
	 * @return FloatArray The world points.
	 */
	private fun restLattice(model: PuppetModel, warpId: DeformerId): FloatArray = assertNotNull(warpLatticeAt(model, emptyMap(), warpId)).world

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