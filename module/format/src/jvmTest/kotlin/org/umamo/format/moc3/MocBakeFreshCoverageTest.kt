package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocEncoder
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.Section
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Measures how much of a real file a reference-free bake can actually reproduce.
 *
 * [MocEncoder.bake] hides an unproduced section by carrying it from the source container, so nothing
 * else notices the gap; [MocEncoder.bakeFresh] has nothing to carry from, and every index it cannot
 * produce is emitted EMPTY.  That is the difference between "we can edit a moc" and "we can write
 * one", so the gap is tracked as a number rather than discovered later by a runtime that will not
 * load the result.
 *
 * The expectation below is a ratchet, not a target: it names the sections no producer covers, and it
 * is empty, so the gate fails the moment a fresh bake stops producing a section a corpus model carries.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class MocBakeFreshCoverageTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
			?: emptyList()

	/**
	 * The sections still carried rather than synthesized, corpus-wide.
	 *
	 * Empty, and that is the point: every section any corpus model carries is now produced by a fresh
	 * bake.  An entry added here is a claim that some index cannot be derived - state which model shows
	 * it and why, because the last four entries all turned out to be derivable, and two of them
	 * (the parameter key runs, the offscreen alias) were load-breaking while they sat here.
	 */
	private val knownUnproduced = emptySet<Section>()

	@Test
	fun freshBakeCoversEverySectionExceptTheKnownGaps() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping fresh bake coverage test")
			return
		}
		val missingOverall = LinkedHashSet<String>()
		var totalProduced = 0
		var totalPresent = 0
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val document = Moc3.decode(model)
			val produced = MocEncoder.bakeFreshCoverage(document)
			val nameByIndex =
				Section.entries
					.mapNotNull { section -> section.indexIn(document.version).takeIf { it >= 0 }?.to(section) }
					.toMap()
			// Only sections the FILE actually carries count as a gap: an index a given model leaves empty
			// needs nothing produced for it.
			val presentIndices = (0 until model.sectionCount).filter { (model.section(it)?.size ?: 0) > 0 }
			totalPresent += presentIndices.size
			totalProduced += presentIndices.count { it in produced }
			for (index in presentIndices) {
				if (index !in produced) {
					missingOverall.add(nameByIndex[index]?.name ?: "index $index")
				}
			}
		}
		assertEquals(
			knownUnproduced.map { it.name }.toSortedSet(),
			missingOverall.toSortedSet(),
			"the set of unproduced sections changed",
		)
		assertTrue(totalPresent > 0, "no sections were examined")
		println(
			"[fresh-coverage] $totalProduced/$totalPresent present sections synthesized across ${files.size} " +
				"models; ${missingOverall.size} distinct sections still carried",
		)
	}
}