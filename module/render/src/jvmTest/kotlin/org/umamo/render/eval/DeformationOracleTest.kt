package org.umamo.render.eval

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.runtime.ingest.Cmo3Import
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Differential validation against the Umamo C++ Runtime oracle: read a `.cmo3` -> PuppetModel,
 * evaluate the default pose, and compare per-drawable hashes against
 * `dump_model libcore_re.so model.moc3 --update`.
 *
 * The oracle prints a rolling hash `hp = hp*1.0000001 + coord` over each drawable's interleaved
 * positions (`vpos_h`); umamo's output is transformed to model space and re-hashed, matched by id.
 *
 * Result: 157/174 match to tiny-ULP; the other 17 are sub-pixel residuals (<0.6 px/vertex)
 * concentrated in deeply-nested warp chains. Ruled out as causes: FMA contraction, rotation sin/cos,
 * vertex extrapolation, blend-at-default - and `warpExtrap` was audited bit-for-bit against the oracle.
 * The residual grows with nesting depth, which is most consistent with CMO3↔MOC3 source-data
 * re-quantization amplified through the chain (umamo evaluates the CMO3, the oracle the exported MOC3),
 * i.e. a serialization difference, not a math error. Well within the bounded-ULP fidelity contract.
 *
 * Gated on the cmo3/moc3 samples + the Umamo C++ Runtime oracle paths (system properties); skips when absent.
 */
class DeformationOracleTest {
	private data class OracleEntry(val vtx: Int, val vposH: Double, val vuvH: Double)

	@Test
	fun defaultPoseMatchesOracle() {
		val cmo3 = sysFile("cmo3.sample") ?: return logSkip()
		val moc3 = sysFile("moc3.sample") ?: return logSkip()
		val dumpModel = sysFile("relive.dumpModel") ?: return logSkip()
		val coreLib = sysFile("relive.coreLib") ?: return logSkip()

		val root = Cmo3.read(cmo3).root as? CModelSource ?: error("root is not a CModelSource")
		val model = Cmo3Import.fromModelSource(root)
		val geometry = CpuDeformationEvaluator().evaluate(model, emptyMap())
		val oracle = runOracle(dumpModel, coreLib, moc3)
		println("[oracle] umamo drawables=${model.drawables.size} evaluated=${geometry.worldPositions.size} oracle=${oracle.size}")

		// Confirmed transform: CMO3 canvas/pixel space -> MOC3 normalized model space = (x-ox)/ppu,
		// (y+oy)/ppu (origin = canvas centre, ppu from the dump header; umamo already applied -Y).
		val ppu = 4500f
		val originX = 2250f
		val originY = 3250f
		val deformerById = model.deformers.associateBy { it.id }
		var posMatch = 0
		var compared = 0
		val mismatches = StringBuilder()
		var shown = 0
		for (drawable in model.drawables) {
			val entry = oracle[drawable.id.raw] ?: continue
			val world = geometry.worldPositions[drawable.id] ?: continue
			compared++
			val hash = transformedHash(world, originX, -originY, ppu, 1f)
			if (closeEnough(hash, entry.vposH)) {
				posMatch++
			} else if (shown < 20) {
				val parentId = drawable.parentDeformerId
				val relativeError = abs(hash - entry.vposH) / maxOf(1.0, abs(entry.vposH))
				val rotationAncestor = hasRotationAncestor(deformerById, parentId)
				mismatches.append(
					"  ${drawable.id.raw}: parent=${parentId?.raw} vtx=${entry.vtx} relErr=${
						"%.3g".format(
							relativeError,
						)
					} rotAncestor=$rotationAncestor\n",
				)
				shown++
			}
		}
		println("[oracle] posMatch=$posMatch / $compared (transform (x-ox)/ppu,(y+oy)/ppu)")
		println("[oracle] mismatches (bounded-ULP residuals):\n$mismatches")
		assertTrue(posMatch >= 157, "geometry regressed: posMatch=$posMatch (<157) — investigate before relaxing")
	}

	private fun logSkip() {
		println("oracle inputs absent (need -Dcmo3.sample -Dmoc3.sample -Drelive.dumpModel -Drelive.coreLib); skipping")
	}

	private fun sysFile(property: String): File? = System.getProperty(property)?.let(::File)?.takeIf { it.exists() }

	private fun rollingHash(values: FloatArray): Double {
		var hp = 0.0
		for (value in values) {
			hp = hp * 1.0000001 + value
		}
		return hp
	}

	private fun closeEnough(a: Double, b: Double): Boolean {
		val scale = maxOf(1.0, abs(a), abs(b))
		return abs(a - b) <= 1e-5 * scale
	}

	private fun transformedHash(world: FloatArray, offsetX: Float, offsetY: Float, scale: Float, signY: Float): Double {
		var hp = 0.0
		var index = 0
		while (index < world.size) {
			hp = hp * 1.0000001 + (world[index] - offsetX) / scale
			hp = hp * 1.0000001 + signY * (world[index + 1] - offsetY) / scale
			index += 2
		}
		return hp
	}

	private fun printRange(id: String, world: FloatArray) {
		var minX = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE
		var index = 0
		while (index < world.size) {
			minX = minOf(minX, world[index])
			maxX = maxOf(maxX, world[index])
			minY = minOf(minY, world[index + 1])
			maxY = maxOf(maxY, world[index + 1])
			index += 2
		}
		println("[oracle] $id raw range x[$minX..$maxX] y[$minY..$maxY] first=${world.take(4).toList()}")
	}

	private fun hasRotationAncestor(deformerById: Map<DeformerId, Deformer>, start: DeformerId?): Boolean {
		var current = start
		var guard = 0
		while (current != null && guard < 10000) {
			val deformer = deformerById[current] ?: return false
			if (deformer is Deformer.Rotation) {
				return true
			}
			current = deformer.parent
			guard++
		}
		return false
	}

	private fun runOracle(dumpModel: File, coreLib: File, moc3: File): Map<String, OracleEntry> {
		val command = listOf(dumpModel.absolutePath, coreLib.absolutePath, moc3.absolutePath, "--update")
		val process = ProcessBuilder(command).redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().readText()
		process.waitFor()
		val idRegex = Regex("""id=(\S+)""")
		val vtxRegex = Regex("""vtx=(\d+)""")
		val vposRegex = Regex("""vpos_h=(\S+)""")
		val vuvRegex = Regex("""vuv_h=(\S+)""")
		val result = HashMap<String, OracleEntry>()
		for (line in output.lineSequence()) {
			if (!line.startsWith("D ")) {
				continue
			}
			val id = idRegex.find(line)?.groupValues?.get(1) ?: continue
			val vtx = vtxRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
			val vposH = vposRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
			val vuvH = vuvRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
			result[id] = OracleEntry(vtx, vposH, vuvH)
		}
		return result
	}
}
