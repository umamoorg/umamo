package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocLowering
import org.umamo.format.moc3.moc.MocCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Lowering validation: synthesizing the structural/topology sections from a decoded [MocDocument]
 * reproduces the original section bytes exactly (decode → lower → byte-compare). This proves the
 * object→bytes direction for those sections without the runtime. Skips gracefully without samples.
 */
class MocLoweringTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
			?: emptyList()

	@Test
	fun structuralSectionsLowerByteExact() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping lowering test")
			return
		}
		// Failures collect per file instead of aborting the loop, so one model's regression cannot
		// mask (or be masked by) the others - the whole corpus, v1 through v6, now lowers byte-exact.
		val failures = ArrayList<String>()
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val doc = Moc3.decode(model)
			val structural = MocLowering.structuralSections(doc)
			val valueTables = MocLowering.valueTableSections(doc)
			val auxiliary = MocLowering.auxiliarySections(doc)
			val grid = MocLowering.keyformGridSections(doc)
			val blend = MocLowering.blendShapeSections(doc)
			assertTrue(structural.isNotEmpty(), "${file.name}: lowered some sections")
			for ((index, bytes) in structural + valueTables + auxiliary + grid + blend) {
				val original = model.section(index)
				if (original == null || original.size < bytes.size) {
					failures.add("${file.name}: section $index present & sized (need ${bytes.size}, have ${original?.size})")
					continue
				}
				if (!original.copyOf(bytes.size).contentEquals(bytes)) {
					val firstMismatch = bytes.indices.first { original[it] != bytes[it] }
					failures.add("${file.name}: section $index not byte-exact (first mismatch at byte $firstMismatch of ${bytes.size})")
					continue
				}
				// Guard against silently dropping data: anything in the original beyond our synthesized
				// element region must be zero padding (a synthesized section shorter than the original
				// with a nonzero tail - e.g. blend-shape deltas appended to a shared value table - would
				// be a truncation bug; such sections must be carried, not synthesized).
				val tail = original.copyOfRange(bytes.size, original.size)
				if (!tail.all { it.toInt() == 0 }) {
					failures.add("${file.name}: section $index synthesized too short (nonzero tail dropped: ${tail.size} bytes)")
				}
			}
			// Full CountInfo synthesis, including the blend-shape/offscreen totals (fields 23-36).
			val ci = MocLowering.countInfoSection(doc)
			val originalCi = model.section(0)!!.copyOf(ci.size)
			if (!originalCi.contentEquals(ci)) {
				val firstMismatch = ci.indices.first { originalCi[it] != ci[it] }
				failures.add("${file.name}: CountInfo not byte-exact (first mismatch at byte $firstMismatch, field ${firstMismatch / 4})")
			}
			val total = structural.size + valueTables.size + auxiliary.size + grid.size + blend.size
			println("${file.name}: v${model.versionByte} ${structural.size}+${valueTables.size}+${auxiliary.size}+${grid.size}+${blend.size} = $total sections lowered")
		}
		failures.forEach { failureMessage -> println("[lowering] FAIL $failureMessage") }
		assertTrue(failures.isEmpty(), "lowering not byte-exact:\n" + failures.joinToString("\n"))
	}
}
