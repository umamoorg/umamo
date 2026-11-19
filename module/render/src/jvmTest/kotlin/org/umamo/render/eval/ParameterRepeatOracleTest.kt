package org.umamo.render.eval

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.MocParameter
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.PuppetModel
import org.umamo.testkit.requireSample
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Differential validation, against the runtime, of how a pose outside a parameter's range resolves: a repeating
 * axis wraps, and a normal one clamps.
 *
 * Every pose here is handed to both sides raw.  An out-of-range pose has an in-range twin fixed by construction (it
 * sits a whole number of periods away, or at the end a clamp lands on), and every drawable that agrees with the runtime
 * at the twin must still agree at the pose.  Measuring against the twin rather than a pinned count keeps the gate off
 * the nested-warp residuals the other oracles already carry.
 *
 * Probes are derived from each moc's own records: a parameter bound by at least one keyform axis.  A repeating
 * parameter with no keys drives nothing and is never probed.
 *
 * Exactly max is the one deliberate divergence.  The runtime evaluates it as min; Umamo keeps it as max and wraps only a
 * value outside the range (docs/format/MOC3.md §5.5).  So no leg compares our eval at exactly max, and
 * [theRuntimeSendsExactlyMaxToMin] pins the runtime side of it.
 *
 * Gated on `relive.dumpModel`, `relive.coreLib`, and `moc3.samples`.
 */
class ParameterRepeatOracleTest {
	/** Where inside the range a twin sits, as a fraction of the range; off every corpus key. */
	private val insideFraction = 0.37f

	/** How far past an end the edge poses go, as a fraction of the range. */
	private val edgeFraction = 0.01f

	/** How far past an end the clamp poses go, as a fraction of the range. */
	private val clampOvershoot = 0.5f

	/** Normal parameters the clamp leg poses per moc, in file order; the cap bounds the dump count. */
	private val clampProbesPerModel = 2

	/** How far the gap leg widens a range past its last key, as a fraction of the original range. */
	private val gapWidening = 0.5f

	/** Corpus parameters the gap leg tries before giving up on finding one whose end forms differ. */
	private val gapCandidateLimit = 40

	/**
	 * A parameter a leg poses.
	 *
	 * @property String id The parameter id.
	 * @property Float minimum The declared minimum.
	 * @property Float maximum The declared maximum.
	 * @property FloatArray keys Every key position any binding axis gives it, ascending.
	 */
	private class Probe(val id: String, val minimum: Float, val maximum: Float, val keys: FloatArray) {
		val range: Float get() = maximum - minimum
	}

	/**
	 * A corpus moc, with its canvas-space import built on first use.
	 *
	 * @property File file The moc.
	 * @property MocDocument document Its decoded records.
	 */
	private class CorpusModel(val file: File, val document: MocDocument) {
		val puppet: PuppetModel by lazy { restMeshesToCanvasSpace(Moc3Import.fromMocDocument(document, null)) }
	}

	/**
	 * The two harness binaries.
	 *
	 * @property File dumpModel The dump_model binary.
	 * @property File runtimeLib The runtime.
	 */
	private class OracleTools(val dumpModel: File, val runtimeLib: File)

	@Test
	fun aRepeatingAxisWrapsPastEitherEnd() {
		val tools = oracleTools()
		val failures = ArrayList<String>()
		var probed = 0

		for (model in corpusModels()) {
			for (probe in probesOf(model.document) { parameter -> parameter.repeats }) {
				probed++
				val range = probe.range
				val inside = probe.minimum + insideFraction * range
				val pairs =
					listOf(
						inside + range to inside,
						inside - range to inside,
						inside + 3f * range to inside,
						inside - 3f * range to inside,
						probe.maximum + edgeFraction * range to probe.minimum + edgeFraction * range,
						probe.minimum - edgeFraction * range to probe.maximum - edgeFraction * range,
					)
				checkAgainstTwins(tools, model.puppet, model.file, probe.id, pairs, failures)
			}
		}

		if (probed == 0) {
			skipTest("no corpus moc carries a keyed repeating parameter")
		}

		assertTrue(failures.isEmpty(), "a wrapped pose disagrees where its in-range twin agrees:\n" + failures.joinToString("\n"))
	}

	@Test
	fun aNormalAxisClampsPastEitherEnd() {
		val tools = oracleTools()
		val failures = ArrayList<String>()
		var probed = 0

		for (model in corpusModels()) {
			for (probe in probesOf(model.document) { parameter -> !parameter.repeats }.take(clampProbesPerModel)) {
				probed++
				val overshoot = clampOvershoot * probe.range
				val pairs = listOf(probe.maximum + overshoot to probe.maximum, probe.minimum - overshoot to probe.minimum)
				checkAgainstTwins(tools, model.puppet, model.file, probe.id, pairs, failures)
			}
		}

		if (probed == 0) {
			skipTest("no corpus moc carries a keyed normal parameter")
		}

		assertTrue(failures.isEmpty(), "a clamped pose disagrees where its in-range twin agrees:\n" + failures.joinToString("\n"))
	}

	/**
	 * The runtime own rule at exactly max, which Umamo deliberately does not follow: it evaluates max as min.
	 *
	 * Only a probe whose forms differ at its ends can tell the two apart, so the leg counts the probes where the runtime at
	 * max differs from the runtime a hair below it, which snaps onto the max key, and skips when none does.
	 */
	@Test
	fun theRuntimeSendsExactlyMaxToMin() {
		val tools = oracleTools()
		val failures = ArrayList<String>()
		var probed = 0
		var telling = 0

		for (model in corpusModels()) {
			for (probe in probesOf(model.document) { parameter -> parameter.repeats }) {
				probed++
				val atMaximum = runOracleDump(tools.dumpModel, tools.runtimeLib, model.file, mapOf(probe.id to probe.maximum))
				val atMinimum = runOracleDump(tools.dumpModel, tools.runtimeLib, model.file, mapOf(probe.id to probe.minimum))
				val belowMaximum = runOracleDump(tools.dumpModel, tools.runtimeLib, model.file, mapOf(probe.id to probe.maximum - EPS_KEY / 2))
				val differing = dumpDifferences(atMaximum, atMinimum)

				if (differing.isNotEmpty()) {
					failures.add("${model.file.name} ${probe.id}: the runtime at max differs from min on ${differing.size} drawables, e.g. ${differing.take(5)}")
				}

				if (dumpDifferences(atMaximum, belowMaximum).isNotEmpty()) {
					telling++
				}
			}
		}

		if (probed == 0) {
			skipTest("no corpus moc carries a keyed repeating parameter")
		}

		println("[repeat-oracle] exactly max: $telling of $probed repeating probes tell max from min")

		if (telling == 0) {
			skipTest("no repeating probe's forms differ at its ends, so the runtime dumps cannot tell max from min")
		}

		assertTrue(failures.isEmpty(), "the runtime no longer sends exactly max to min:\n" + failures.joinToString("\n"))
	}

	/**
	 * A repeating axis whose keys stop short of max, which no corpus moc has, so the leg makes one.
	 *
	 * It takes a corpus normal axis keyed at both ends whose forms differ there, widens its max past the last key, and
	 * exports it twice, repeating and not.  Between the last key and max a pose brackets nothing on either axis.  The runtime
	 * then freezes what the axis keys at its last evaluated state, where our eval hides it, which is a gap on every axis
	 * and not this test's subject.  What repeat could add is an interpolation across the seam, so the leg asserts the
	 * runtime's gap dump is the same with repeat on as with it off.  A pose past the widened max still checks our wrap.
	 */
	@Test
	fun aRepeatingAxisTreatsItsLastKeyGapLikeANormalAxis() {
		val tools = oracleTools()
		val (model, probe) = gapCandidate(tools, corpusModels()) ?: skipTest("no corpus normal axis is keyed at both ends with differing end forms")
		val range = probe.range
		val widenedMaximum = probe.maximum + gapWidening * range
		val source = Moc3Import.fromMocDocument(model.document, null)
		val outputDirectory = File("build/parameter-repeat-oracle").apply { mkdirs() }

		/** Exports the widened axis, repeating or not, and reads the file back. */
		fun exportWidened(repeat: Boolean): Pair<File, MocDocument> {
			val synthetic =
				source.copy(
					parameters =
						source.parameters.map { parameter ->
							if (parameter.id.raw == probe.id) parameter.copy(max = widenedMaximum, repeat = repeat) else parameter
						},
				)
			val bytes = Moc3Export.write(synthetic, model.document.version).first
			val file = outputDirectory.resolve("${model.file.nameWithoutExtension}-${if (repeat) "repeat" else "normal"}.moc3").apply { writeBytes(bytes) }
			val document = Moc3.read(bytes)
			val parameter = document.parameters.first { exported -> exported.id == probe.id }
			assertTrue(
				parameter.repeats == repeat && parameter.maximumValue == widenedMaximum,
				"the export dropped the synthetic axis: repeats=${parameter.repeats} max=${parameter.maximumValue}",
			)

			return file to document
		}

		val (repeatingFile, repeatingDocument) = exportWidened(repeat = true)
		val (normalFile, _) = exportWidened(repeat = false)
		val gap = probe.maximum + 0.5f * gapWidening * range
		println("[repeat-oracle] gap leg: ${model.file.name} ${probe.id} keyed [${probe.minimum}, ${probe.maximum}], widened to $widenedMaximum")
		val seamDifferences =
			dumpDifferences(
				runOracleDump(tools.dumpModel, tools.runtimeLib, repeatingFile, mapOf(probe.id to gap)),
				runOracleDump(tools.dumpModel, tools.runtimeLib, normalFile, mapOf(probe.id to gap)),
			)
		assertTrue(
			seamDifferences.isEmpty(),
			"the runtime's last-key gap differs once the axis repeats, on ${seamDifferences.size} drawables, e.g. ${seamDifferences.take(5)}",
		)

		val puppet = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(repeatingDocument, null))
		val inside = probe.minimum + insideFraction * range
		val failures = ArrayList<String>()
		checkAgainstTwins(tools, puppet, repeatingFile, probe.id, listOf(inside + (widenedMaximum - probe.minimum) to inside), failures)
		assertTrue(failures.isEmpty(), "a wrap past the widened max disagrees where its in-range twin agrees:\n" + failures.joinToString("\n"))
	}

	/**
	 * Poses [puppet] at each pair's first value and checks that every drawable agreeing with the runtime at the pair's twin
	 * still agrees, adding one line per regression to [failures].
	 *
	 * @param OracleTools tools The harness binaries.
	 * @param PuppetModel puppet The rig, imported from [moc3].
	 * @param File moc3 The MOC3 the runtime dumps.
	 * @param String parameterId The posed parameter.
	 * @param List pairs Each (value, in-range twin), raw.
	 * @param MutableList failures Where a regression is reported.
	 */
	private fun checkAgainstTwins(
		tools: OracleTools,
		puppet: PuppetModel,
		moc3: File,
		parameterId: String,
		pairs: List<Pair<Float, Float>>,
		failures: MutableList<String>,
	) {
		val comparisonByValue = HashMap<Float, OraclePoseComparison>()

		fun comparisonAt(value: Float): OraclePoseComparison =
			comparisonByValue.getOrPut(value) { compareWithOracle(puppet, tools.dumpModel, tools.runtimeLib, moc3, mapOf(parameterId to value)) }

		for ((value, twin) in pairs) {
			val atTwin = comparisonAt(twin)
			val atValue = comparisonAt(value)
			val regressed = atTwin.agreed - atValue.agreed
			println("[repeat-oracle] ${moc3.name} $parameterId=$value (twin $twin): agreed ${atValue.agreed.size}, twin ${atTwin.agreed.size}")

			if (regressed.isNotEmpty()) {
				failures.add("  ${moc3.name} $parameterId=$value (twin $twin): ${regressed.size} drawables agree only at the twin, e.g. ${regressed.sorted().take(5)}")
			}
		}
	}

	/**
	 * The first corpus normal parameter keyed at both its ends whose runtime dumps at min and max differ.
	 *
	 * @param OracleTools tools The harness binaries.
	 * @param List<CorpusModel> models The corpus, in name order.
	 * @return Pair? The model and the parameter, or null when none qualifies within [gapCandidateLimit] tries.
	 */
	private fun gapCandidate(tools: OracleTools, models: List<CorpusModel>): Pair<CorpusModel, Probe>? {
		var tried = 0

		for (model in models) {
			for (probe in probesOf(model.document) { parameter -> !parameter.repeats }) {
				if (abs(probe.keys.first() - probe.minimum) >= EPS_KEY || abs(probe.keys.last() - probe.maximum) >= EPS_KEY) {
					continue
				}

				val atMinimum = runOracleDump(tools.dumpModel, tools.runtimeLib, model.file, mapOf(probe.id to probe.minimum))
				val atMaximum = runOracleDump(tools.dumpModel, tools.runtimeLib, model.file, mapOf(probe.id to probe.maximum))

				if (dumpDifferences(atMinimum, atMaximum).isNotEmpty()) {
					return model to probe
				}

				tried++

				if (tried >= gapCandidateLimit) {
					return null
				}
			}
		}

		return null
	}

	/**
	 * The drawables whose dumped position, opacity, or order differs between two dumps of one moc.
	 *
	 * @param OracleDump first One dump.
	 * @param OracleDump second The other.
	 * @return List<String> The differing drawable ids.
	 */
	private fun dumpDifferences(first: OracleDump, second: OracleDump): List<String> =
		first.entries.mapNotNull { (drawableId, entry) ->
			val other = second.entries[drawableId] ?: return@mapNotNull drawableId
			val same =
				oracleCloseEnough(entry.vposH, other.vposH) &&
					oracleCloseEnough(entry.opacity.toDouble(), other.opacity.toDouble()) &&
					entry.drawOrder == other.drawOrder &&
					entry.renderOrder == other.renderOrder

			if (same) null else drawableId
		}

	/**
	 * The probes of [document] that [accept] admits: every parameter a keyform axis binds, with a non-empty range.
	 *
	 * @param MocDocument document The decoded moc.
	 * @param Function accept Which parameters to keep.
	 * @return List<Probe> The probes, in file order.
	 */
	private fun probesOf(document: MocDocument, accept: (MocParameter) -> Boolean): List<Probe> {
		val keysByParameter = HashMap<Int, MutableSet<Float>>()

		for (binding in document.bindings) {
			for (axis in binding.axes) {
				keysByParameter.getOrPut(axis.parameterIndex) { HashSet() }.addAll(axis.keyPositions.toList())
			}
		}

		return document.parameters.mapIndexedNotNull { parameterIndex, parameter ->
			val keys = keysByParameter[parameterIndex]

			if (keys.isNullOrEmpty() || !accept(parameter) || parameter.maximumValue <= parameter.minimumValue) {
				null
			} else {
				Probe(parameter.id, parameter.minimumValue, parameter.maximumValue, keys.sorted().toFloatArray())
			}
		}
	}

	/**
	 * Every corpus moc outside `work/`, which holds our own bake outputs, in name order.
	 *
	 * @return List<CorpusModel> The models that decode.
	 */
	private fun corpusModels(): List<CorpusModel> =
		requireSample("moc3.samples")
			.walkTopDown()
			.filter { file -> file.isFile && file.extension == "moc3" && "work" !in file.path.split(File.separatorChar) }
			.sortedBy { file -> file.name }
			.mapNotNull { file -> runCatching { Moc3.read(file.readBytes()) }.getOrNull()?.let { document -> CorpusModel(file, document) } }
			.toList()

	/**
	 * The harness binaries, or a skip naming the one that is missing.
	 *
	 * @return OracleTools The binaries.
	 */
	private fun oracleTools(): OracleTools = OracleTools(requireSample("relive.dumpModel"), requireSample("relive.coreLib"))
}