package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.encode.MocEncoder
import org.umamo.format.moc3.encode.MocLowering
import org.umamo.format.moc3.io.LittleEndianReader
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Section
import org.umamo.interop.moc3.export.Moc3Export
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CountInfo self-consistency on the REFERENCE-FREE export path.
 *
 * `:format`'s own CountInfo gate runs over documents decoded from corpus files, which share a shape
 * the exporter never produces: every v4+ blend-free sample has zero parameter bindings, so the branch
 * that writes KEY_POSITIONS' per-parameter union region is never reached with a non-empty key set.
 * A freshly exported rig hits it on essentially every model, and nothing downstream would notice a
 * wrong count - `Moc3ExportRoundTripTest` compares documents, `Moc3FamilyExportTest` re-imports
 * through our own decoder, and our `MocSections` reads KEY_POSITIONS as a TABLE section, so its
 * declared extent is invisible to every reader we own.  The official core is not: it sizes its
 * parameter key store from field 14 and then reads the section-103/104 runs out of that buffer.
 *
 * Exported at each model's own version and at V53.  A MOC3-imported puppet's runtime target mirrors
 * its source version, so the former is what `Moc3Export.write` defaults to for these documents; the
 * latter is the newest table layout, which a re-target or a target-less document bakes.  Skips
 * without samples.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class Moc3ExportCountInfoTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir
			?.walkTopDown()
			// work/ holds our own bake outputs - same models, no new coverage.
			?.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
			?.sortedBy { it.name }
			?.toList()
			.orEmpty()

	/**
	 * Reads a lowered section back as an [IntArray].
	 *
	 * @param Map        sections The lowered section map, keyed by table index.
	 * @param Section    section  The section to read.
	 * @param MocVersion version  The version whose table index applies.
	 * @return IntArray The decoded values, empty when the section is absent.
	 */
	private fun intsOf(sections: Map<Int, ByteArray>, section: Section, version: MocVersion): IntArray {
		val bytes = sections[section.indexIn(version)] ?: return IntArray(0)
		val reader = LittleEndianReader(bytes)
		return IntArray(bytes.size / 4) { reader.readInt32() }
	}

	@Test
	fun freshlyExportedDocumentsDeclareTheSectionsTheyCarry() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping export CountInfo gate")
			return
		}
		val failures = ArrayList<String>()
		var covered = 0
		var withKeyPositions = 0
		for (file in files) {
			val source = Moc3.decode(MocCodec.read(file.readBytes()))
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			for (targetVersion in listOf(source.version, MocVersion.V53)) {
				val exported = Moc3Export.toMocDocument(puppet, targetVersion).document
				val lowered = MocLowering.lower(exported)
				val countInfo = intsOf(lowered, Section.COUNT_INFO, targetVersion)
				val label = "${file.name} → ${targetVersion.name}"
				covered++

				val keyPositions = lowered[Section.KEY_POSITIONS.indexIn(targetVersion)] ?: ByteArray(0)
				if (keyPositions.isNotEmpty()) {
					withKeyPositions++
				}
				if (countInfo[14] * 4 != keyPositions.size) {
					failures.add(
						"$label: CountInfo[14] declares ${countInfo[14]} floats but KEY_POSITIONS carries" +
							" ${keyPositions.size / 4}",
					)
				}
				val bindingStart = intsOf(lowered, Section.KEYFORM_BINDING_START, targetVersion)
				if (countInfo[12] != bindingStart.size) {
					failures.add(
						"$label: CountInfo[12] declares ${countInfo[12]} bindings but s73 carries ${bindingStart.size}",
					)
				}
				// 103/104 address runs INSIDE the buffer field 14 sizes, so a run ending past the declared
				// extent reads off the end of the arena the runtime just allocated.
				val keyStart = intsOf(lowered, Section.PARAM_KEY_START, targetVersion)
				val keyCount = intsOf(lowered, Section.PARAM_KEY_COUNT, targetVersion)
				if (keyStart.isNotEmpty()) {
					val lastRunEnd = keyStart.last() + keyCount.last()
					if (lastRunEnd > countInfo[14]) {
						failures.add("$label: parameter key runs end at $lastRunEnd, past the declared ${countInfo[14]}")
					}
				}
				// The bake has to survive its own writer: an over-long offset table or a section whose
				// declared count exceeds its slice fails here rather than inside the official core.
				val baked = MocEncoder.bakeFresh(targetVersion, exported)
				MocCodec.read(baked)
			}
		}
		failures.forEach { failureMessage -> println("[export-countinfo] FAIL $failureMessage") }
		assertTrue(failures.isEmpty(), "exported CountInfo disagrees with its sections:\n" + failures.joinToString("\n"))
		println("[export-countinfo] $covered exports checked, $withKeyPositions with a non-empty KEY_POSITIONS")
		// The whole point is the union-region branch the corpus cannot reach; if no export produced key
		// positions at all, this gate proved nothing.
		assertTrue(withKeyPositions > 0, "no exported document carried KEY_POSITIONS - the gate covered nothing")
	}
}
