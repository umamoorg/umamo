package org.umamo.render.eval

import org.junit.Assume
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.interop.moc3.Moc3Export
import org.umamo.interop.moc3.Moc3Import
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The strongest gate available on the MOC3 export: import a corpus model, export it WITHOUT a
 * reference container, and make the runtime evaluate both files.
 *
 * Our evaluator is out of the loop entirely here.  Both sides run through the same core, so the
 * comparison isolates the only question that matters - did the file we wrote encode the same rig -
 * from the separate question of whether our own math matches theirs.  A semantic round trip cannot
 * do that: it compares our reading of our own output.
 *
 * "The export loads at all" is gated for free.  `runOracleDump` fails the test when `dump_model`
 * exits non-zero, and a file with a stale CountInfo, a mis-sized runtime-slot array, an off-by-one
 * prefix sum, or a draw-group rule violation makes `csmReviveMocInPlace` reject it outright.
 *
 * `vuvH` is a free canary on the texcoord prefix sum (section 44), which nothing else exercises.
 *
 * Scoped to the versions the export covers in full; the rest join as blend shapes, colours, and
 * offscreens land.  Gated on `relive.dumpModel` + `relive.coreLib` + `moc3.samples`.
 */
class Moc3ExportOracleTest {
	/** The versions the export currently covers in full. */
	private val supportedVersions = setOf(1, 2, 3, 4, 5)

	@Test
	fun exportedFilesEvaluateLikeTheirSources() {
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

		val outputDir = File("build/moc3-export").apply { mkdirs() }
		val mismatches = ArrayList<String>()
		var exported = 0
		var comparedDrawables = 0

		for (mocFile in samples) {
			val source = runCatching { Moc3.decode(MocCodec.read(mocFile.readBytes())) }.getOrNull() ?: continue
			if (source.version.byteValue !in supportedVersions) {
				continue
			}
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			val (bytes, report) = Moc3Export.write(puppet, source.version)
			val exportedFile = File(outputDir, mocFile.name).apply { writeBytes(bytes) }
			exported++
			println("[export-oracle] ${mocFile.name}: ${bytes.size} bytes, ${report.notices.size} notices")

			// The default pose alone is a real gate here: it exercises every static section plus the
			// grid blend at each parameter's own default, which is where a mis-lowered binding shows up.
			val sourceDump = runOracleDump(dumpModel, coreLib, mocFile, emptyMap())
			val exportedDump = runOracleDump(dumpModel, coreLib, exportedFile, emptyMap())

			for ((drawableId, expected) in sourceDump.entries) {
				val actual = exportedDump.entries[drawableId]
				if (actual == null) {
					mismatches.add("${mocFile.name}: $drawableId missing from the exported file")
					continue
				}
				if (oracleNeverEvaluated(expected)) {
					continue
				}
				comparedDrawables++
				if (!oracleCloseEnough(expected.vposH, actual.vposH)) {
					mismatches.add("${mocFile.name} $drawableId vposH: ${expected.vposH} vs ${actual.vposH}")
				}
				if (!oracleCloseEnough(expected.vuvH, actual.vuvH)) {
					mismatches.add("${mocFile.name} $drawableId vuvH: ${expected.vuvH} vs ${actual.vuvH}")
				}
				if (!oracleCloseEnough(expected.opacity.toDouble(), actual.opacity.toDouble())) {
					mismatches.add("${mocFile.name} $drawableId op: ${expected.opacity} vs ${actual.opacity}")
				}
			}
		}

		assertTrue(exported > 0, "no corpus model in the export's supported versions")
		println(
			"[export-oracle] $exported models exported and re-evaluated, $comparedDrawables drawables compared, " +
				"${mismatches.size} mismatches",
		)
		assertTrue(
			mismatches.isEmpty(),
			"exported files do not evaluate like their sources:\n" + mismatches.take(20).joinToString("\n"),
		)
	}
}
