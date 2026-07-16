package org.umamo.render.eval

import org.junit.Assume
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.runtime.ingest.Cmo3Import
import org.umamo.runtime.model.ParameterId
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Corpus E2E for glue: on a real model at a non-default tail pose, every glued seam pair must weld to
 * essentially one point (the editor's weights satisfy `wA + wB ≈ 1`, so `A'` and `B'` converge). This is
 * the real-model counterpart of the synthetic `GlueTest`; without the glue pass the tail strips split by
 * hundreds of pixels. Gated on `-Dcmo3.sample`; self-skips without it.
 */
class GlueCorpusTest {
	@Test
	fun glueWeldsSeamPairsOnCorpus() {
		val file = File(System.getProperty("cmo3.sample") ?: "")
		Assume.assumeTrue("[glue-e2e] no cmo3.sample corpus model", file.isFile)
		val root = Cmo3.read(file).root as? CModelSource ?: return
		val puppet = Cmo3Import.fromModelSource(root)
		assertTrue(puppet.glues.isNotEmpty(), "corpus model should carry glue affecters")

		val geometry = CpuDeformationEvaluator().evaluate(puppet, mapOf(ParameterId("Param_Angle_Rotation3") to 43.31f))
		var maxGap = 0.0
		var weldedPairs = 0
		for (glue in puppet.glues) {
			val vertsA = geometry.worldPositions[glue.meshA] ?: continue
			val vertsB = geometry.worldPositions[glue.meshB] ?: continue
			for (pair in glue.pairs) {
				val gap =
					hypot(
						(vertsA[pair.indexA * 2] - vertsB[pair.indexB * 2]).toDouble(),
						(vertsA[pair.indexA * 2 + 1] - vertsB[pair.indexB * 2 + 1]).toDouble(),
					)
				maxGap = maxOf(maxGap, gap)
				weldedPairs++
			}
		}
		println("[glue-e2e] welded pairs=$weldedPairs maxGap=${"%.4f".format(maxGap)}px")
		assertTrue(weldedPairs > 0, "should have welded at least one pair")
		assertTrue(maxGap < 1.0, "glued seam pairs should weld to ~one point, but maxGap=$maxGap px")
	}
}
