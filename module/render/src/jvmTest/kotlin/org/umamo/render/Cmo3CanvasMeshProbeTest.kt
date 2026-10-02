package org.umamo.render

import org.junit.Assume
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a CMO3 stores in `CArtMeshSource.positions`, measured against the rig itself.
 *
 * Under a deformer an art mesh's keyforms are in the deformer's space, but `positions` is not: the
 * question this answers is whether it is the mesh's rest form carried through the deformer chain onto
 * the canvas, which is what an export can rebuild for a mesh whose stored array it no longer has.  The
 * import reads `positions` as the drawable's base today, so the deformed rest geometry the evaluator
 * returns at the default pose is compared with the very array the file stored.
 */
class Cmo3CanvasMeshProbeTest {
	/**
	 * The corpus files named by `cmo3.probe`.
	 *
	 * @return List<File> The readable files.
	 */
	private fun corpusFiles(): List<File> =
		System.getProperty("cmo3.probe")
			.orEmpty()
			.split(',')
			.map { entry -> entry.trim() }
			.filter { entry -> entry.isNotEmpty() && !entry.contains("ExportTest") }
			.map(::File)
			.filter { file -> file.isFile }

	@Test
	fun storedPositionsAreTheRestFormOnTheCanvas() {
		val files = corpusFiles()
		Assume.assumeTrue("no cmo3.probe corpus models", files.isNotEmpty())
		var comparedChildren = 0
		for (file in files) {
			val root = Cmo3.read(file).root as? CModelSource ?: continue
			val puppet = Cmo3Import.fromModelSource(root)
			val rest = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions
			val childDistances = ArrayList<Float>()
			val rootDistances = ArrayList<Float>()
			for (drawable in puppet.drawables) {
				val stored = drawable.mesh?.positions ?: continue
				val evaluated = rest[drawable.id] ?: continue
				if (evaluated.size != stored.size) {
					continue
				}
				// The evaluator's world negates y (DrawableSpaceMapping.localToWorld); the file's canvas is y-down.
				var farthest = 0f
				for (vertexIndex in 0 until stored.size / 2) {
					farthest = max(farthest, abs(evaluated[vertexIndex * 2] - stored[vertexIndex * 2]))
					farthest = max(farthest, abs(-evaluated[vertexIndex * 2 + 1] - stored[vertexIndex * 2 + 1]))
				}
				if (drawable.parentDeformerId != null) {
					childDistances.add(farthest)
				} else {
					rootDistances.add(farthest)
				}
			}
			comparedChildren += childDistances.size
			println("[canvas-mesh] ${file.name}: deformer children ${summary(childDistances)}; root meshes ${summary(rootDistances)}")
		}
		assertTrue(comparedChildren > 0, "no deformer-parented mesh was compared")
	}

	/**
	 * One line describing how far the evaluated rest form sits from the stored positions.
	 *
	 * @param List<Float> distances Each mesh's farthest vertex distance, in canvas pixels.
	 * @return String The count, the share within half a pixel and within two pixels, the median, and the maximum.
	 */
	private fun summary(distances: List<Float>): String {
		if (distances.isEmpty()) {
			return "none"
		}
		val sorted = distances.sorted()
		val withinHalf = sorted.count { distance -> distance <= 0.5f }
		val withinTwo = sorted.count { distance -> distance <= 2f }
		return "${sorted.size} (<=0.5px $withinHalf, <=2px $withinTwo, median ${"%.3g".format(sorted[sorted.size / 2])}, max ${"%.3g".format(sorted.last())})"
	}
}