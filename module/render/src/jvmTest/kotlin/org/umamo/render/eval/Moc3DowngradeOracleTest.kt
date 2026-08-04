package org.umamo.render.eval

import org.junit.Assume
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportNotice
import org.umamo.interop.moc3.Moc3Export
import org.umamo.interop.moc3.Moc3Import
import org.umamo.runtime.model.RuntimeFeature
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every corpus model exports at every version BELOW its own, and each of those files loads and
 * evaluates in the official core.
 *
 * A downgrade cannot be checked by comparing against the source - the whole point is that features
 * are gone - so this asserts the two things that must hold instead: the file is loadable (the core
 * rejects or crashes on a document whose CountInfo counts keyforms a stripped section no longer
 * holds, and `runOracleDump` turns that into a failure), and the stripped feature is genuinely
 * ABSENT from the evaluated result rather than merely unwritten in one section.
 *
 * That second half is what makes this more than a smoke test.  Dropping a section without stripping
 * the model produces a file that still loads: the drawables are all there, the geometry is right,
 * and only the colours or the offscreens quietly read from the wrong place.  The per-version
 * invariants below fail on exactly that.
 *
 * Gated on `relive.dumpModel` + `relive.coreLib` + `moc3.samples`.
 */
class Moc3DowngradeOracleTest {
	@Test
	fun everyDowngradeLoadsAndDropsWhatItPromised() {
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

		val outputDir = File("build/moc3-downgrade").apply { mkdirs() }
		val failures = ArrayList<String>()
		var exported = 0
		for (mocFile in samples) {
			val source = runCatching { Moc3.decode(MocCodec.read(mocFile.readBytes())) }.getOrNull() ?: continue
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			// The source's own drawable set is the reference: the ModelWithOffscreen family evaluates none
			// at all (mesh-less parts and offscreens only), so "has drawables" is not a universal truth -
			// "keeps the ones it had" is.
			val sourceDrawables = runOracleDump(dumpModel, coreLib, mocFile, emptyMap()).entries.keys
			for (version in MocVersion.entries) {
				if (version.byteValue >= source.version.byteValue) {
					continue
				}
				val (bytes, report) = Moc3Export.write(puppet, version)
				val exportedFile =
					File(outputDir, "${mocFile.nameWithoutExtension}-v${version.byteValue}.moc3")
						.apply { writeBytes(bytes) }
				exported++
				// Loading IS the assertion here: runOracleDump fails the test on a non-zero exit, which is
				// what a downgrade that left the file internally inconsistent produces.
				val dump = runOracleDump(dumpModel, coreLib, exportedFile, emptyMap())
				val subject = "${mocFile.name} -> v${version.byteValue}"

				val lostDrawables = sourceDrawables - dump.entries.keys
				if (lostDrawables.isNotEmpty()) {
					failures.add("$subject: ${lostDrawables.size} drawables lost, e.g. ${lostDrawables.take(3)}")
				}
				// Offscreen rendering is moc 6; below it every isolated part is demoted to grouped.
				if (dump.offscreens.isNotEmpty()) {
					failures.add("$subject: ${dump.offscreens.size} offscreens survived the downgrade")
				}
				// Per-object multiply/screen colour is moc 4; below it every drawable must evaluate to the
				// identity pair, which is also what proves the colour TABLES went with the sections.
				if (version.byteValue < 4) {
					for ((drawableId, entry) in dump.entries) {
						if (oracleNeverEvaluated(entry)) {
							continue
						}
						val tinted =
							entry.multiplyRgba.take(3).any { channel -> channel != 1f } ||
								entry.screenRgba.take(3).any { channel -> channel != 0f }
						if (tinted) {
							failures.add(
								"$subject $drawableId: colour survived a pre-4.2 downgrade " +
									"(mul=${entry.multiplyRgba.take(3)} scr=${entry.screenRgba.take(3)})",
							)
							break
						}
					}
				}
				// A downgrade that removed nothing must say so: the corpus models all use something the
				// oldest versions cannot carry, so a silent empty report means the strip did not run.
				if (version == MocVersion.V30 && report.notices.none { it is ExportNotice.FeatureStripped }) {
					val inUse =
						puppet.parameters.isNotEmpty() &&
							(
								puppet.drawables.any { drawable -> drawable.blendShapes.isNotEmpty() } ||
									puppet.parts.any { part -> part.isIsolated }
							)
					if (inUse) {
						failures.add("$subject: features were stripped but nothing was reported")
					}
				}
			}
		}
		assertTrue(exported > 0, "no corpus model has a version below its own")
		println("[downgrade-oracle] $exported downgraded files loaded, ${failures.size} failures")
		assertTrue(
			failures.isEmpty(),
			"downgraded exports are not clean:\n" + failures.take(20).joinToString("\n"),
		)
	}

	/** A stripped feature is named by the report, and every notice names at least one subject. */
	@Test
	fun strippedFeaturesAreNamedInTheReport() {
		val samples =
			System.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { it.isDirectory }
				?.walkTopDown()
				?.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
				?.sortedBy { it.name }
				?.toList()
				.orEmpty()
		if (samples.isEmpty()) {
			println("moc3.samples not present; skipping downgrade report test")
			return
		}
		val failures = ArrayList<String>()
		val seenFeatures = linkedSetOf<RuntimeFeature>()
		for (mocFile in samples) {
			val source = runCatching { Moc3.decode(MocCodec.read(mocFile.readBytes())) }.getOrNull() ?: continue
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			val report = Moc3Export.toMocDocument(puppet, MocVersion.V30).report
			for (notice in report.notices.filterIsInstance<ExportNotice.FeatureStripped>()) {
				seenFeatures.add(notice.feature)
				if (notice.subjects.isEmpty()) {
					failures.add("${mocFile.name}: ${notice.feature} was reported with no subjects")
				}
			}
		}
		println("[downgrade] features stripped somewhere in the corpus at v1: $seenFeatures")
		assertTrue(seenFeatures.isNotEmpty(), "no corpus model exercised any strip")
		assertTrue(failures.isEmpty(), failures.joinToString("\n"))
	}
}
