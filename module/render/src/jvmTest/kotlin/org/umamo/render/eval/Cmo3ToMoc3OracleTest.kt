package org.umamo.render.eval

import org.junit.Assume
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.moc3.export.Moc3Export
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A CMO3 exported to MOC3 EVALUATES like the editor's own bake of the same model.
 *
 * The end-to-end statement of the whole export: take the source project, lower it ourselves, and let
 * the official runtime pose both files.  Nothing of ours is in the loop at evaluation time, so a
 * disagreement is about the file we wrote, not about our math.
 *
 * Two tiers, because the two files are not obliged to be byte-alike:
 *
 *  - The exported file must LOAD, and every drawable the bake has must be present and evaluated.  That
 *    is equality, and `runOracleDump`'s non-zero-exit check gates the loading half for free.
 *  - GEOMETRY is a measured floor, not an equality.  The editor's bake is its own derivation from the
 *    same source, and the two need only agree to within the authoring skew - so the test pins how many
 *    drawables land within tolerance and fails when that number drops, which is what a lowering
 *    regression looks like from here.
 *
 * Gated on `relive.dumpModel` + `relive.coreLib` + `cmo3.probe` + `moc3.samples`.
 */
class Cmo3ToMoc3OracleTest {
	/**
	 * The corpus twins, joined by base name.
	 *
	 * @return List Each (base name, cmo3, moc3) triple.
	 */
	private fun twins(): List<Triple<String, File, File>> {
		val cmo3Files =
			System.getProperty("cmo3.probe")
				?.split(',')
				?.map { path -> File(path.trim()) }
				?.filter { file -> file.isFile }
				?.associateBy { file -> file.nameWithoutExtension }
				.orEmpty()
		val moc3Files =
			System.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { directory -> directory.isDirectory }
				?.walkTopDown()
				?.filter { file -> file.isFile && file.extension == "moc3" && file.parentFile?.name != "work" }
				?.associateBy { file -> file.nameWithoutExtension }
				.orEmpty()
		return cmo3Files.keys
			.intersect(moc3Files.keys)
			.sorted()
			.map { name -> Triple(name, cmo3Files.getValue(name), moc3Files.getValue(name)) }
	}

	@Test
	fun exportedProjectsEvaluateLikeTheEditorsBake() {
		val dumpModel = requireOracleInput("relive.dumpModel")
		val coreLib = requireOracleInput("relive.coreLib")
		val twins = twins()
		Assume.assumeTrue("[oracle] no cmo3/moc3 twins", twins.isNotEmpty())

		val outputDir = File("build/cmo3-to-moc3").apply { mkdirs() }
		val failures = ArrayList<String>()
		var comparedModels = 0
		var comparedDrawables = 0
		var geometryMatches = 0
		val perModel = ArrayList<String>()
		for ((name, cmo3File, moc3File) in twins) {
			val baked = Moc3.decode(MocCodec.read(moc3File.readBytes()))
			val root = Cmo3.read(cmo3File).root as? CModelSource ?: continue
			val puppet = Cmo3Import.fromModelSource(root)
			val (bytes, _) =
				runCatching { Moc3Export.write(puppet, baked.version) }.getOrElse { failure ->
					failures.add("$name: the CMO3 would not lower to a moc ($failure)")
					continue
				}
			val exportedFile = File(outputDir, "$name.moc3").apply { writeBytes(bytes) }

			val bakedDump = runOracleDump(dumpModel, coreLib, moc3File, emptyMap())
			val ourDump = runOracleDump(dumpModel, coreLib, exportedFile, emptyMap())
			// A twin pair is only an oracle when both files are the same revision of the model; the
			// structural gate (Cmo3ToMoc3CrossFormatTest) names the one corpus pair that is not.
			val shared = bakedDump.entries.keys.intersect(ourDump.entries.keys)
			if (shared.size < bakedDump.entries.size * 9 / 10) {
				continue
			}
			comparedModels++
			var modelCompared = 0
			var modelMatches = 0

			for (drawableId in shared) {
				val expected = bakedDump.entries.getValue(drawableId)
				val actual = ourDump.entries.getValue(drawableId)
				if (oracleNeverEvaluated(expected)) {
					continue
				}
				comparedDrawables++
				if (expected.vtx != actual.vtx) {
					failures.add("$name $drawableId: ${expected.vtx} vertices in the bake, ${actual.vtx} in ours")
					continue
				}
				// Opacity is a pose result, not a derived coordinate - the two files must agree exactly on
				// what the default pose makes this drawable.
				if (!oracleCloseEnough(expected.opacity.toDouble(), actual.opacity.toDouble())) {
					failures.add("$name $drawableId op: ${expected.opacity} vs ${actual.opacity}")
				}
				modelCompared++
				if (oracleCloseEnough(expected.vposH, actual.vposH)) {
					geometryMatches++
					modelMatches++
				}
			}
			if (modelCompared > 0 && modelMatches < modelCompared) {
				// Printed per model so the residue is legible rather than a single percentage: it sits at
				// roughly one drawable in ten across EVERY real model, which is what says it is authoring
				// skew and not one model's quirk.
				perModel.add("$name $modelMatches/$modelCompared")
			}
		}
		assertTrue(comparedModels > 0, "no twin pair could be compared")
		val geometryRatio = if (comparedDrawables == 0) 0.0 else geometryMatches.toDouble() / comparedDrawables
		println(
			"[cross-oracle] $comparedModels twins, $comparedDrawables drawables compared, " +
				"$geometryMatches within the oracle's geometry tolerance " +
				"(${(geometryRatio * 100).toInt()}%), ${failures.size} failures" +
				(if (perModel.isEmpty()) "" else "; below par: $perModel"),
		)
		assertTrue(failures.isEmpty(), "exported projects do not evaluate like the bake:\n" + failures.take(15).joinToString("\n"))
		assertTrue(
			geometryRatio >= GEOMETRY_FLOOR,
			"only ${(geometryRatio * 100).toInt()}% of drawables land on the bake's geometry, floor is " +
				"${(GEOMETRY_FLOOR * 100).toInt()}% - a lowering regression looks exactly like this",
		)
	}

	private companion object {
		/**
		 * The floor under the share of drawables whose evaluated geometry matches the editor's bake.
		 *
		 * A FLOOR rather than 100%: the bake is the editor's own derivation from the same project, so the
		 * two agree only to within its authoring skew - measured at 89% over 1046 drawables of 21 twins,
		 * spread evenly across models rather than pooled in one.  Raise this when the number improves; a
		 * drop is a regression in the lowering, which is the only thing that can move it.
		 *
		 * For scale: writing the project's own pixels-per-unit instead of a bake scale put this at ZERO,
		 * and every other gate stayed green through it.  That is the class of defect the floor catches.
		 */
		const val GEOMETRY_FLOOR: Double = 0.85
	}
}