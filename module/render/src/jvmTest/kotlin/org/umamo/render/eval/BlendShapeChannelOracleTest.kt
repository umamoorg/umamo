package org.umamo.render.eval

import org.junit.Assume
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.Rgb
import org.umamo.interop.moc3.Moc3Import
import org.umamo.runtime.model.ParameterId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Posed differential validation of blend-shape CHANNEL deltas, opacity and multiply/screen color.
 *
 * A blend-shape record carries a delta row per key for every channel its object owns, not only for
 * geometry.  [BlendShapeOracleTest] compares vertex-position hashes and so cannot see these at all;
 * this gate poses the driving parameter at the key that actually carries a non-zero channel delta and
 * compares the runtime's exposed per-drawable opacity and colours.
 *
 * Poses are DERIVED like the deformer-channel gate's: the test reads the format layer for records
 * whose keyforms carry a non-zero channel delta and sweeps only those parameters, so a re-authored rig
 * keeps being covered and a rig with no channel deltas costs nothing.  A record's color delta rows are
 * ADDITIVE, so their identity is zero rather than Cubism's (1,1,1) multiply / (0,0,0) screen - a row of
 * all zeros contributes nothing and is not worth a pose.
 *
 * Gated on `relive.dumpModel` + `relive.coreLib` + `moc3.samples`; skips when any is absent.
 */
class BlendShapeChannelOracleTest {
	/** Caps the poses per model so a densely-keyed rig cannot turn this into a multi-minute run. */
	private val maximumPosesPerModel = 12

	/**
	 * Whether [color] carries any additive contribution (absent or all-zero rows contribute nothing).
	 *
	 * @param Rgb? color The delta row.
	 * @return Boolean True when the row would change the result.
	 */
	private fun isNonZero(color: Rgb?): Boolean =
		color != null && (color.r != 0f || color.g != 0f || color.b != 0f)

	/**
	 * Whether [keyform] carries a non-zero opacity or color delta.
	 *
	 * @param BlendShapeKeyform keyform One key's delta payload.
	 * @return Boolean True when a channel would move.
	 */
	private fun carriesChannelDelta(keyform: BlendShapeKeyform): Boolean =
		when (keyform) {
			is BlendShapeKeyform.Mesh ->
				keyform.form.opacity != 0f || isNonZero(keyform.form.multiplyColor) || isNonZero(keyform.form.screenColor)
			is BlendShapeKeyform.Warp ->
				keyform.form.opacity != 0f || isNonZero(keyform.form.multiplyColor) || isNonZero(keyform.form.screenColor)
			is BlendShapeKeyform.Rotation ->
				keyform.form.opacity != 0f || isNonZero(keyform.form.multiplyColor) || isNonZero(keyform.form.screenColor)
			is BlendShapeKeyform.Part -> false
		}

	/**
	 * The poses that activate each record carrying a channel delta: its driving parameter set to the
	 * key position of the key whose row is non-zero, everything else left at its default.
	 *
	 * @param MocDocument document The decoded model.
	 * @return List<Map<String, Float>> One pose per (record, interesting key).
	 */
	private fun channelDeltaPoses(document: MocDocument): List<Map<String, Float>> {
		val poses = LinkedHashSet<Map<String, Float>>()
		for (record: BlendShape in document.blendShapes) {
			val parameterId = document.parameters.getOrNull(record.parameterIndex)?.id ?: continue
			for (keyIndex in record.keyforms.indices) {
				if (keyIndex == record.neutralKeyIndex || !carriesChannelDelta(record.keyforms[keyIndex])) {
					continue
				}
				val keyPosition = record.keyPositions.getOrNull(keyIndex) ?: continue
				poses.add(mapOf(parameterId to keyPosition))
			}
		}
		return poses.take(maximumPosesPerModel)
	}

	@Test
	fun blendShapeChannelDeltasMatchTheOracle() {
		val dumpModel = requireOracleInput("relive.dumpModel")
		val coreLib = requireOracleInput("relive.coreLib")
		val samples =
			System.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { it.isDirectory }
				?.walkTopDown()
				// work/ holds our own bake outputs - same model, different name, no new coverage.
				?.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
				?.sortedBy { it.name }
				?.toList()
				.orEmpty()
		Assume.assumeTrue("[oracle] absent -Dmoc3.samples", samples.isNotEmpty())

		var comparedDrawableStates = 0
		var posedModels = 0
		val mismatches = ArrayList<String>()

		for (mocFile in samples) {
			val mocDocument = runCatching { Moc3.read(mocFile.readBytes()) }.getOrNull() ?: continue
			val poses = channelDeltaPoses(mocDocument)
			if (poses.isEmpty()) {
				continue
			}
			posedModels++
			// The canvas-space rebase is the app's real ingest path and the channels compared here are
			// rebase-invariant, so running the rebased model keeps the gate on the rendered path.
			val puppet = org.umamo.render.restMeshesToCanvasSpace(Moc3Import.fromMocDocument(mocDocument, null))
			println("[oracle] ${mocFile.name}: ${poses.size} blend-shape channel-delta poses")

			for (pose in poses) {
				val dump = runOracleDump(dumpModel, coreLib, mocFile, pose)
				val parameterValues = pose.entries.associate { ParameterId(it.key) to it.value }
				val inputs = preparePose(puppet, parameterValues)
				for (drawableInputs in inputs.drawables) {
					val oracleEntry = dump.entries[drawableInputs.drawableId.raw] ?: continue
					if (oracleNeverEvaluated(oracleEntry)) {
						continue
					}
					comparedDrawableStates++
					val label = "${mocFile.name} pose=$pose ${drawableInputs.drawableId.raw}"
					checkChannel(mismatches, label, "op", oracleEntry.opacity, drawableInputs.opacity)
					if (oracleEntry.multiplyRgba.size == 4 && oracleEntry.screenRgba.size == 4) {
						checkChannel(mismatches, label, "mulR", oracleEntry.multiplyRgba[0], drawableInputs.multiplyColor.red)
						checkChannel(mismatches, label, "mulG", oracleEntry.multiplyRgba[1], drawableInputs.multiplyColor.green)
						checkChannel(mismatches, label, "mulB", oracleEntry.multiplyRgba[2], drawableInputs.multiplyColor.blue)
						checkChannel(mismatches, label, "scrR", oracleEntry.screenRgba[0], drawableInputs.screenColor.red)
						checkChannel(mismatches, label, "scrG", oracleEntry.screenRgba[1], drawableInputs.screenColor.green)
						checkChannel(mismatches, label, "scrB", oracleEntry.screenRgba[2], drawableInputs.screenColor.blue)
					}
				}
			}
		}

		// A run that posed nothing would pass while proving nothing, which is the failure mode every
		// corpus gate here is prone to.  modelC carries non-zero color delta rows, so the corpus reaches this.
		assertTrue(posedModels > 0, "no corpus model carried a blend-shape channel delta to pose")
		println(
			"[oracle] blend-shape channels: $comparedDrawableStates drawable states across $posedModels models, " +
				"${mismatches.size} mismatches",
		)
		assertTrue(
			mismatches.isEmpty(),
			"blend-shape channel deltas disagree with the oracle:\n" + mismatches.take(20).joinToString("\n"),
		)
	}

	/**
	 * Records a channel mismatch when ours differs from the oracle beyond tolerance.
	 *
	 * @param ArrayList mismatches The shared collector.
	 * @param String    label      The model/pose/drawable identifier.
	 * @param String    channel    Which channel is being compared.
	 * @param Float     oracle     The oracle's value.
	 * @param Float     ours       Our evaluated value.
	 */
	private fun checkChannel(mismatches: ArrayList<String>, label: String, channel: String, oracle: Float, ours: Float) {
		if (!oracleCloseEnough(oracle.toDouble(), ours.toDouble())) {
			mismatches.add("$label $channel: oracle=$oracle ours=$ours")
		}
	}
}
