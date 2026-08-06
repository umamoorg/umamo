package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocLowering
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.Section
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Lowering validation: synthesizing a decoded [MocDocument]'s sections - structural, value tables,
 * auxiliary, keyform grid, blend shapes, runtime slots, and derived indexes - reproduces the original
 * section bytes exactly (decode → lower → byte-compare).  This proves the object→bytes direction for
 * those sections without the runtime.  The offscreen per-part alias is the one exception, skipped
 * below because it is an editor artifact no producer can derive.  Skips gracefully without samples.
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
		// mask (or be masked by) the others - the whole corpus, v1 through v6, lowers byte-exact.
		val failures = ArrayList<String>()
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val doc = Moc3.decode(model)
			// `MocLowering.lower` merges the producers strictly - a section claimed by two of them throws
			// there rather than resolving by merge order - so this test consumes the merged map directly
			// and the disjointness it used to check is now enforced in production.
			val merged = MocLowering.lower(doc)
			assertTrue(merged.isNotEmpty(), "${file.name}: lowered some sections")

			for ((index, bytes) in merged) {
				if (index == Section.COUNT_INFO.indexIn(doc.version)) {
					// Compared separately below, at the ORIGINAL's width rather than the synthesized one.
					continue
				}
				if (index == Section.OFFSCREEN_BY_PART_ALIAS.indexIn(doc.version)) {
					// Not byte-exact by design, and only on modelA: 160 carries a per-part offscreen map
					// that disagrees with 152 there and matches no offscreen owner, so it is an editor
					// artifact we cannot derive - see OffscreenSectionAliasProbeTest.  The lowering writes
					// the owner-consistent inverse instead, which is what makes the file loadable.
					continue
				}
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
			// Compared at the ORIGINAL's width, not the synthesized one: comparing only the synthesized
			// prefix would let a too-narrow block pass while dropping the fields past its end (v5 carries
			// 64 words and a rotation-blend model writes field 33, which a 32-word cap would lose).
			val ci = merged.getValue(Section.COUNT_INFO.indexIn(doc.version))
			val originalCi = model.section(0)!!
			if (!originalCi.contentEquals(ci)) {
				val firstMismatch =
					(0 until maxOf(originalCi.size, ci.size)).first {
						originalCi.getOrNull(it) != ci.getOrNull(it)
					}
				failures.add(
					"${file.name}: CountInfo not byte-exact (${ci.size} bytes vs ${originalCi.size}; " +
						"first mismatch at byte $firstMismatch, field ${firstMismatch / 4})",
				)
			}
			println("${file.name}: v${model.versionByte} ${merged.size} sections lowered")
		}
		failures.forEach { failureMessage -> println("[lowering] FAIL $failureMessage") }
		assertTrue(failures.isEmpty(), "lowering not byte-exact:\n" + failures.joinToString("\n"))
	}
}
