package org.umamo.render.eval

import org.junit.Assume
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.ingest.Cmo3Import
import org.umamo.runtime.ingest.Moc3Import
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Posed differential validation of the blend-shape eval against the Umamo C++ runtime: for
 * each blend-shape corpus pair (Model A/Model B/Model C), evaluate a matrix of poses that exercise the
 * additive pass - at-key, midpoint, beyond-range saturation, combined normal+blend, and the
 * min-combined limit curves - and compare per-drawable position hashes against `dump_model`.
 *
 * Match floors are pinned from the first measured run (the DeformationOracleTest pattern): the
 * same nested-warp bounded-ULP residual class shaves a few drawables, plus the mesh-under-
 * morphed-warp coupling documented in BlendDeltaFrameProbeTest. Gate against the Umamo C++ Runtime
 * only; divergences against the reference implementations are recorded in MOC3.md, never gated
 * (reference trust order).
 *
 * Gated on `relive.dumpModel` + `relive.coreLib` + the corpus pair properties; skips when any is absent.
 */
class BlendShapeOracleTest {
	private data class OraclePair(
		val name: String,
		val cmo3: File,
		val moc3: File,
		val poses: List<Map<String, Float>>,
		/** Minimum matched drawables per pose, pinned from the first measured run. */
		val matchFloor: Int,
		/** Same floor for the MOC3-imported puppet leg, pinned from its first measured run. */
		val moc3ImportMatchFloor: Int,
	)

	@Test
	fun posedBlendShapesMatchOracle() {
		val dumpModel = requireInput("relive.dumpModel")
		val coreLib = requireInput("relive.coreLib")
		val pairs = resolvePairs()
		Assume.assumeTrue("[oracle] no blend-shape corpus pairs resolvable", pairs.isNotEmpty())

		var totalCompared = 0
		var totalMatched = 0
		for (pair in pairs) {
			val root = Cmo3.read(pair.cmo3).root as? CModelSource ?: error("${pair.name}: root is not a CModelSource")
			val puppet = Cmo3Import.fromModelSource(root)
			// The MOC3-import leg: the SAME model imported from the baked moc3 itself (the editor's
			// document-loader path), so the blend mapping's reference re-addition and space
			// conversion are gated end-to-end.  Same-file comparison, so any cmo3-vs-moc3
			// authoring-state skew is out of the picture by construction.
			val moc3Puppet = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(Moc3.decode(pair.moc3.readBytes()), null))
			for (pose in pair.poses) {
				val (compared, matched, worst) = comparePose(puppet, dumpModel, coreLib, pair.moc3, pose)
				totalCompared += compared
				totalMatched += matched
				println("[oracle] ${pair.name} src=cmo3 pose=$pose matched=$matched/$compared worstRelErr=$worst")
				// Every blend pose matches at or above its model's DEFAULT-POSE (blend-free) baseline
				// Model A 171/198 default vs >= 170 posed (residuals <= 7.7e-5,
				// the nested-warp ULP class), Model B 48/59 vs >= 48, Model C 213/260 default vs 215
				// posed.  Model C's remaining residuals (<= 6.0e-4) are authored-at-default morphs
				// applying correctly under the neutral-at-value-0 reading, not the cmo3/moc3
				// "authoring-state skew" that a neutral-at-default reading would misattribute them to.
				// Regression = any pose below the pinned floor.
				assertTrue(
					matched >= pair.matchFloor,
					"${pair.name} pose=$pose regressed: matched=$matched/$compared (< ${pair.matchFloor})",
				)
				val (moc3Compared, moc3Matched, moc3Worst) = comparePose(moc3Puppet, dumpModel, coreLib, pair.moc3, pose)
				totalCompared += moc3Compared
				totalMatched += moc3Matched
				println("[oracle] ${pair.name} src=moc3 pose=$pose matched=$moc3Matched/$moc3Compared worstRelErr=$moc3Worst")
				assertTrue(
					moc3Matched >= pair.moc3ImportMatchFloor,
					"${pair.name} src=moc3 pose=$pose regressed: matched=$moc3Matched/$moc3Compared (< ${pair.moc3ImportMatchFloor})",
				)
			}
		}
		println("[oracle] blend-shape posed total: $totalMatched/$totalCompared")
		assertTrue(totalCompared > 0, "expected comparable drawables")
	}

	/**
	 * Evaluates [puppet] at [pose] and compares against the Umamo C++ Runtime's dump of [moc3] at
	 * the same pose.
	 *
	 * @return Triple(compared, matched, worst relative error) for the pose.
	 */
	private fun comparePose(
		puppet: PuppetModel,
		dumpModel: File,
		coreLib: File,
		moc3: File,
		pose: Map<String, Float>,
	): Triple<Int, Int, Double> {
		val geometry =
			CpuDeformationEvaluator().evaluate(puppet, pose.mapKeys { ParameterId(it.key) })
		val dump = runOracleDump(dumpModel, coreLib, moc3, pose)
		var compared = 0
		var matched = 0
		var worst = 0.0
		for (drawable in puppet.drawables) {
			val entry = dump.entries[drawable.id.raw] ?: continue
			val world = geometry.worldPositions[drawable.id] ?: continue
			compared++
			// Same transform as the default-pose gate: (x-ox)/ppu, (y+oy)/ppu (umamo pre-flips Y).
			val hash = oracleTransformedHash(world, dump.originX, -dump.originY, dump.pixelsPerUnit)
			if (oracleCloseEnough(hash, entry.vposH)) {
				matched++
			} else {
				val relativeError = Math.abs(hash - entry.vposH) / maxOf(1.0, Math.abs(entry.vposH))
				worst = maxOf(worst, relativeError)
			}
		}
		return Triple(compared, matched, worst)
	}

	/** The blend-shape corpus pairs with their pose matrices, filtered to what exists locally. */
	private fun resolvePairs(): List<OraclePair> {
		val cmo3s =
			System.getProperty("cmo3.probe")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()
		val moc3Root = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

		fun moc3(name: String): File? = moc3Root?.walkTopDown()?.firstOrNull { it.isFile && it.name == name }

		fun cmo3(prefix: String): File? = cmo3s.firstOrNull { it.name.startsWith(prefix) }

		val pairs = ArrayList<OraclePair>()
		val modelA = cmo3("modelA")
		val modelAMoc = moc3("modelA.moc3")
		if (modelA != null && modelAMoc != null) {
			pairs.add(
				OraclePair(
					"Model A",
					modelA,
					modelAMoc,
					listOf(
						// Default pose first: a blend-free baseline separating pre-existing
						// residuals (nested-warp ULP class, model-state skew) from blend errors.
						emptyMap(),
						mapOf("ParamEyeSize" to -1f),
						mapOf("ParamEyeSize" to -0.5f),
						// Beyond-range: saturates to the end key (confirmed via the Umamo C++ Runtime).
						mapOf("ParamEyeSize" to -1.5f),
						mapOf("ParamEyeSize" to -1f, "ParamAngleX" to 20f),
						// The warp-coupling case documented in BlendDeltaFrameProbeTest.
						mapOf("ParamBodyAngleX2" to 10f),
						mapOf("ParamBrowLY" to -1f),
					),
					matchFloor = 170,
					moc3ImportMatchFloor = 165,
				),
			)
		}
		val modelB = cmo3("modelB")
		val modelBMoc = moc3("modelB.moc3")
		if (modelB != null && modelBMoc != null) {
			pairs.add(
				OraclePair(
					"Model B",
					modelB,
					modelBMoc,
					listOf(
						emptyMap(),
						mapOf("ParamA" to 1f, "ParamMouthOpenY" to 1f),
						// The limit ramp: contribution scaled to 0.5x / suppressed to zero.
						mapOf("ParamA" to 1f, "ParamMouthOpenY" to 0.5f),
						mapOf("ParamA" to 1f, "ParamMouthOpenY" to 0f),
					),
					matchFloor = 48,
					moc3ImportMatchFloor = 44,
				),
			)
		}
		val modelC = cmo3("modelC")
		val modelCMoc = moc3("modelC.moc3")
		if (modelC != null && modelCMoc != null) {
			pairs.add(
				OraclePair(
					"Model C",
					modelC,
					modelCMoc,
					listOf(
						emptyMap(),
						// Min-combination: multiplier = min over the inverse ramps, not the product.
						mapOf("ParamA" to 1f, "ParamI" to 0.6f, "ParamU" to 0.8f),
					),
					matchFloor = 213,
					moc3ImportMatchFloor = 212,
				),
			)
		}
		return pairs
	}

	/**
	 * The file [property] points at, skipping the test when absent (Assume, so it reports SKIPPED).
	 *
	 * @param String property The system property naming the input.
	 * @return File The existing file.
	 */
	private fun requireInput(property: String): File {
		val file = System.getProperty(property)?.let(::File)?.takeIf { it.exists() }
		Assume.assumeTrue("[oracle] absent -D$property", file != null)
		return file!!
	}
}
