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
 * The expectation below is a ratchet, not a target: it records what is still missing so the set can
 * only shrink.  Removing a name from it means a producer now covers that section.
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
	 * All of them need an intermediate another producer already computes - the per-form color rows need
	 * the object color bases, the offscreen alias needs the offscreen-by-part map, and the parameter
	 * binding/key starts need the binding pool - so each belongs beside that producer rather than in a
	 * file of its own that would recompute (and eventually contradict) the same inputs.
	 */
	private val knownUnproduced =
		setOf(
			Section.WARP_FORM_MULTIPLY_ROW,
			Section.WARP_FORM_SCREEN_ROW,
			Section.ROTATION_FORM_MULTIPLY_ROW,
			Section.ROTATION_FORM_SCREEN_ROW,
			Section.ARTMESH_FORM_MULTIPLY_ROW,
			Section.ARTMESH_FORM_SCREEN_ROW,
			Section.OFFSCREEN_BY_PART_ALIAS,
			Section.PARAM_BINDING_START,
			Section.PARAM_KEY_START,
			Section.PARAM_KEY_COUNT,
			Section.BLENDSHAPE_PARAMETER_BEGIN,
			Section.BLENDSHAPE_PARAMETER_COUNT,
		)

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
