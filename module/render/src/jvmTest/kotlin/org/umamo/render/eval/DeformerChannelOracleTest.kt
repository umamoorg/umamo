package org.umamo.render.eval

import org.junit.Assume
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.model.Deformer
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.interop.moc3.Moc3Import
import org.umamo.runtime.model.ParameterId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Posed differential validation of the DEFORMER channel cascade against the Umamo C++ runtime.
 *
 * A warp or rotation deformer carries its own opacity and multiply/screen colour per keyform, and
 * the runtime folds those down the deformer chain into every drawable underneath before exposing
 * `csmGetDrawableOpacities` / `...MultiplyColors` / `...ScreenColors`.  This gate compares that
 * exposed per-drawable result against [preparePose]'s, so it covers the whole chain: the drawable's
 * own keyform channels and the deformer cascade between them.
 *
 * Poses are DERIVED, not hand-written: for each model the test reads the format layer to find which
 * deformers actually author a non-identity channel, walks those deformers' keyform bindings to the
 * parameters that drive them, and sweeps each such parameter across its own key positions plus the
 * midpoints between them.  Hand-picked poses would silently stop exercising a model whose rig was
 * re-authored; derived ones follow it.
 *
 * The format layer is also why the interesting-deformer search reads [MocDocument] rather than the
 * imported [org.umamo.runtime.model.PuppetModel]: searching through the app's own import would only
 * prove the cascade agrees with itself, while reading the moc3 source of truth directly keeps the
 * gate honest against an independent read of which deformers actually author a channel.
 *
 * Gated on `relive.dumpModel` + `relive.coreLib` + `moc3.samples`; skips when any is absent.
 */
class DeformerChannelOracleTest {
	/** Cubism identity: fully opaque, white multiply, black screen. */
	private val opaque = 1f
	private val multiplyIdentity = Triple(1f, 1f, 1f)
	private val screenIdentity = Triple(0f, 0f, 0f)

	/** How many midpoints to insert between consecutive key positions on a swept axis. */
	private val midpointsPerSegment = 1

	/** Caps the poses per model so a densely-keyed rig cannot turn this into a multi-minute run. */
	private val maximumPosesPerModel = 24

	@Test
	fun deformerChannelsCascadeToDrawablesLikeTheOracle() {
		val dumpModel = requireOracleInput("relive.dumpModel")
		val coreLib = requireOracleInput("relive.coreLib")
		val samples =
			System.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { it.isDirectory }
				?.walkTopDown()
				// work/ holds our own bake outputs - same model, different name, no new coverage.
				?.filter { it.isFile && it.extension == "moc3" && "work" !in it.path.split(File.separatorChar) }
				?.sortedBy { it.name }
				?.toList()
				.orEmpty()
		Assume.assumeTrue("[oracle] absent -Dmoc3.samples", samples.isNotEmpty())

		var comparedModels = 0
		var comparedDrawableStates = 0
		var skippedNeverEvaluated = 0
		val mismatches = ArrayList<String>()

		for (mocFile in samples) {
			val mocDocument = runCatching { Moc3.decode(mocFile.readBytes()) }.getOrNull() ?: continue
			val drivingParameters = parametersDrivingChannelledDeformers(mocDocument)
			if (drivingParameters.isEmpty()) {
				continue
			}
			// The canvas-space rebase is part of the app's real ingest path, and it must preserve every
			// non-positional keyform channel (the modelA hologram tint regression: the rebase rebuilt
			// MeshForms and silently dropped multiply/screen colours).  The channels this gate compares
			// are rebase-invariant, so running the rebased model keeps the gate on the rendered path.
			val puppet = org.umamo.render.restMeshesToCanvasSpace(Moc3Import.fromMocDocument(mocDocument, null))
			val poses = derivePoses(mocDocument, drivingParameters)
			comparedModels++
			println("[oracle] ${mocFile.name}: ${drivingParameters.size} channelled-deformer parameters, ${poses.size} poses")

			for (pose in poses) {
				val dump = runOracleDump(dumpModel, coreLib, mocFile, pose)
				val parameterValues = pose.entries.associate { ParameterId(it.key) to it.value }
				val inputs = preparePose(puppet, parameterValues)
				for (drawableInputs in inputs.drawables) {
					val oracleEntry = dump.entries[drawableInputs.drawableId.raw] ?: continue
					/*
					 * The runtime core FREEZES an art mesh whose own keyform binding - or any ancestor
					 * deformer's - is out of its keyed parameter range, skipping it entirely during the
					 * update, so every computed field stays at the zero it was allocated with. Umamo
					 * instead evaluates such a drawable live, and that difference spans geometry as well
					 * as these channels (the oracle reports `vpos_h=0` for them), so it is a separate gap
					 * from the channel cascade this test gates - see TODO § Puppet Model, CMO3, MOC3.
					 * Excluded here so this gate keeps measuring one thing.
					 */
					if (oracleNeverEvaluated(oracleEntry)) {
						skippedNeverEvaluated++
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

		Assume.assumeTrue("[oracle] no corpus model authors a deformer channel", comparedModels > 0)
		assertTrue(
			mismatches.isEmpty(),
			"deformer-channel cascade mismatches (${mismatches.size} of $comparedDrawableStates drawable states):\n" +
				mismatches.take(40).joinToString("\n") +
				if (mismatches.size > 40) "\n… and ${mismatches.size - 40} more" else "",
		)
		println(
			"[oracle] compared $comparedDrawableStates drawable channel states across $comparedModels models, " +
				"all within tolerance ($skippedNeverEvaluated skipped as never-evaluated by the oracle)",
		)
	}

	/**
	 * Finds the parameters driving any deformer that authors a non-identity opacity or colour.
	 *
	 * @param MocDocument mocDocument The decoded model.
	 * @return Map<String, FloatArray> Parameter id → the key positions of the axes that drive one.
	 */
	private fun parametersDrivingChannelledDeformers(mocDocument: MocDocument): Map<String, FloatArray> {
		val driving = LinkedHashMap<String, FloatArray>()
		for (deformer in mocDocument.deformers) {
			if (!authorsAChannel(deformer)) {
				continue
			}
			val binding = mocDocument.keyformBinding(deformer.keyformBindingIndex) ?: continue
			for (axis in binding.axes) {
				val parameter = mocDocument.parameters.getOrNull(axis.parameterIndex) ?: continue
				// Union the key positions when several channelled deformers share a parameter, so a
				// sweep visits every key any of them is authored at.
				val existing = driving[parameter.id]
				driving[parameter.id] =
					if (existing == null) {
						axis.keyPositions.copyOf()
					} else {
						(existing.toSortedSet() + axis.keyPositions.toSortedSet()).toFloatArray()
					}
			}
		}
		return driving
	}

	/**
	 * Whether any of this deformer's keyforms departs from the identity channels.
	 *
	 * @param Deformer deformer The warp or rotation deformer.
	 * @return Boolean True when it authors an opacity or colour worth sweeping.
	 */
	private fun authorsAChannel(deformer: Deformer): Boolean {
		val channels =
			when (deformer) {
				is WarpDeformer -> deformer.keyforms.map { Triple(it.opacity, it.multiplyColor, it.screenColor) }
				is RotationDeformer -> deformer.keyforms.map { Triple(it.opacity, it.multiplyColor, it.screenColor) }
			}
		return channels.any { (opacity, multiplyColor, screenColor) ->
			opacity != opaque ||
				(multiplyColor != null && Triple(multiplyColor.r, multiplyColor.g, multiplyColor.b) != multiplyIdentity) ||
				(screenColor != null && Triple(screenColor.r, screenColor.g, screenColor.b) != screenIdentity)
		}
	}

	/**
	 * Builds the pose sweep: the default pose, then each driving parameter visited alone at each of
	 * its key positions and at the midpoints between them.
	 *
	 * One parameter moves at a time on purpose.  A combined sweep would multiply out to thousands of
	 * dumps, and a mismatch in one would not say which axis caused it.
	 *
	 * @param MocDocument       mocDocument       The decoded model (for parameter defaults).
	 * @param Map               drivingParameters Parameter id → key positions to visit.
	 * @return List<Map<String, Float>> The poses to evaluate, default pose first.
	 */
	private fun derivePoses(mocDocument: MocDocument, drivingParameters: Map<String, FloatArray>): List<Map<String, Float>> {
		val poses = ArrayList<Map<String, Float>>()
		poses.add(emptyMap())
		for ((parameterId, keyPositions) in drivingParameters) {
			val parameter = mocDocument.parameters.firstOrNull { it.id == parameterId } ?: continue
			val sorted = keyPositions.distinct().sorted()
			val visits = ArrayList<Float>(sorted)
			for (keyIndex in 0 until sorted.size - 1) {
				val low = sorted[keyIndex]
				val high = sorted[keyIndex + 1]
				for (step in 1..midpointsPerSegment) {
					visits.add(low + (high - low) * step / (midpointsPerSegment + 1f))
				}
			}
			// Clamp to the parameter's DECLARED range. A keyform axis can carry key positions outside
			// it (nothing in the format forbids a rig keeping keys from a since-narrowed range), and
			// the runtime core clamps the written value to [min, max] before evaluating. Sweeping an
			// out-of-range value would compare our extrapolation against the core's clamp - a real
			// difference, but a different one from the channel cascade this test gates.
			for (value in visits.map { it.coerceIn(parameter.minimumValue, parameter.maximumValue) }.distinct().sorted()) {
				// Skip the default - the empty pose already covers it, and repeating it wastes a dump.
				if (value == parameter.defaultValue) {
					continue
				}
				poses.add(mapOf(parameterId to value))
				if (poses.size >= maximumPosesPerModel) {
					println("[oracle] pose sweep capped at $maximumPosesPerModel; later parameters not visited")
					return poses
				}
			}
		}
		return poses
	}

	private fun checkChannel(mismatches: ArrayList<String>, label: String, channel: String, oracle: Float, ours: Float) {
		if (!oracleCloseEnough(oracle.toDouble(), ours.toDouble())) {
			mismatches.add("$label $channel: oracle=$oracle ours=$ours")
		}
	}
}
