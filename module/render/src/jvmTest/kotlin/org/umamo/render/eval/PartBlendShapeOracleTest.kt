package org.umamo.render.eval

import org.junit.Assume
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.interop.moc3.Moc3Import
import org.umamo.runtime.model.ParameterId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Posed differential validation of PART-target blend-shape records against the runtime.
 *
 * A part's only blendable channel is its draw order, so unlike the drawable and deformer records these
 * carry no geometry and no color - and their effect is invisible to every other oracle gate, which
 * compares positions and per-drawable channels.  What a part draw-order delta actually moves is the
 * SORT: a grouped part's whole subtree changes places among its siblings.  So this gate compares the
 * core's post-update render order (`csmGetDrawableRenderOrders`) against ours.
 *
 * Comparing the ORDER rather than the raw draw-order value is deliberate.  The two runtimes agree on
 * the ordering but not necessarily on the number behind it - Umamo sorts floats where the core rounds
 * to an integer first (MOC3.md §5.6) - so the value is a weaker and noisier signal than the permutation
 * it produces, which is what actually reaches the screen.
 *
 * Gated on `relive.dumpModel` + `relive.coreLib` + `moc3.samples`; skips when any is absent.  modelC is
 * the only corpus model carrying a part-target record, so the corpus reaches this through it alone.
 */
class PartBlendShapeOracleTest {
	@Test
	fun partBlendShapeDrawOrderMatchesTheOracleRenderOrder() {
		val dumpModel = requireOracleInput("relive.dumpModel")
		val coreLib = requireOracleInput("relive.coreLib")
		val samples =
			System.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { it.isDirectory }
				?.walkTopDown()
				?.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
				?.sortedBy { it.name }
				?.toList()
				.orEmpty()
		Assume.assumeTrue("[oracle] absent -Dmoc3.samples", samples.isNotEmpty())

		var posedModels = 0
		var comparedPoses = 0
		val mismatches = ArrayList<String>()

		for (mocFile in samples) {
			val mocDocument = runCatching { Moc3.decode(mocFile.readBytes()) }.getOrNull() ?: continue
			val partRecords = mocDocument.blendShapes.filter { it.target == BlendShapeTarget.PART }
			if (partRecords.isEmpty()) {
				continue
			}
			// Pose each record at every non-neutral key carrying an actual delta: a zero row would move
			// nothing and prove nothing.
			val poses = LinkedHashSet<Map<String, Float>>()
			for (record in partRecords) {
				val parameterId = mocDocument.parameters.getOrNull(record.parameterIndex)?.id ?: continue
				for (keyIndex in record.keyforms.indices) {
					val delta = (record.keyforms[keyIndex] as? BlendShapeKeyform.Part)?.drawOrderDelta ?: continue
					if (keyIndex == record.neutralKeyIndex || delta == 0f) {
						continue
					}
					record.keyPositions.getOrNull(keyIndex)?.let { poses.add(mapOf(parameterId to it)) }
				}
			}
			if (poses.isEmpty()) {
				continue
			}
			posedModels++
			val puppet = org.umamo.render.restMeshesToCanvasSpace(Moc3Import.fromMocDocument(mocDocument, null))
			println("[oracle] ${mocFile.name}: ${poses.size} part-blend poses over ${partRecords.size} records")

			for (pose in poses) {
				val dump = runOracleDump(dumpModel, coreLib, mocFile, pose)
				val parameterValues = pose.entries.associate { ParameterId(it.key) to it.value }
				// Our render order is the drawable id sequence back-to-front; the core reports a slot per
				// drawable, so invert it to the same shape before comparing.
				val inputs = preparePose(puppet, parameterValues)
				val ours =
					renderOrder(
						puppet.renderRoot,
						inputs.drawables.associate { it.drawableId to it.drawOrder },
						inputs.partDrawOrders,
					).map { it.raw }
				val theirs =
					dump.entries.entries
						.filter { entry -> !oracleNeverEvaluated(entry.value) }
						.sortedBy { entry -> entry.value.renderOrder }
						.map { entry -> entry.key }
				val oursEvaluated = ours.filter { id -> id in theirs.toSet() }
				comparedPoses++
				if (oursEvaluated != theirs) {
					val firstDivergence = oursEvaluated.zip(theirs).indexOfFirst { (a, b) -> a != b }
					mismatches.add(
						"${mocFile.name} pose=$pose: render order diverges at slot $firstDivergence " +
							"(ours=${oursEvaluated.getOrNull(firstDivergence)} oracle=${theirs.getOrNull(firstDivergence)})",
					)
				}
			}
		}

		assertTrue(posedModels > 0, "no corpus model carried a part-target blend record to pose")
		println("[oracle] part blend shapes: $comparedPoses poses across $posedModels models, ${mismatches.size} mismatches")
		assertTrue(mismatches.isEmpty(), "part blend-shape draw order disagrees:\n" + mismatches.take(10).joinToString("\n"))
	}
}
