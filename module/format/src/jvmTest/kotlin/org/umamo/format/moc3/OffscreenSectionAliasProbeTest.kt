package org.umamo.format.moc3

import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocModel
import org.umamo.format.moc3.moc.Section
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Section 160 is NOT a duplicate of section 152.
 *
 * Both are per-part `i32` arrays that carry an offscreen index (`-1` where a part owns none), and in
 * every two-offscreen sample model they are byte-identical - which is where the "alias" reading came
 * from.  A model with a realistic number of offscreens refutes it: 152 names one slot per offscreen at
 * the owner part's index, while 160 holds ONE MORE value at slots shifted off the owner parts.
 *
 * Its real meaning stays open.  The extra value hints at the render-order rule that gives a sub-group
 * part owning an offscreen its own render slot, but nothing in the corpus pins that, so the fresh bake
 * carries 160 rather than synthesizing it - and this test exists so the refuted identity is not
 * re-derived from the small samples a third time.
 */
class OffscreenSectionAliasProbeTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir
			?.walkTopDown()
			?.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
			?.sortedBy { it.name }
			?.toList()
			.orEmpty()

	/**
	 * Reads a per-part `i32` section as a list.
	 *
	 * @param MocModel model   The parsed container.
	 * @param Section  section The section to read.
	 * @param Int      count   The part count.
	 * @return List<Int>? The values, or null when the section is absent.
	 */
	private fun perPartInts(model: MocModel, section: Section, count: Int): List<Int>? {
		val index = section.indexIn(model.version).takeIf { it >= 0 } ?: return null
		val bytes = model.section(index) ?: return null
		if (bytes.size < count * 4) {
			return null
		}
		return (0 until count).map { slot ->
			(bytes[slot * 4].toInt() and 0xFF) or
				((bytes[slot * 4 + 1].toInt() and 0xFF) shl 8) or
				((bytes[slot * 4 + 2].toInt() and 0xFF) shl 16) or
				(bytes[slot * 4 + 3].toInt() shl 24)
		}
	}

	@Test
	fun sections152And160DivergeOnAMultiOffscreenModel() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping offscreen alias probe")
			return
		}
		var identical = 0
		var divergent = 0
		var largestDivergence: String? = null
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			if (model.version.byteValue < 6) {
				continue
			}
			val partCount = model.countInfo.getOrElse(0) { 0 }
			val byPart = perPartInts(model, Section.OFFSCREEN_BY_PART, partCount) ?: continue
			val alias = perPartInts(model, Section.OFFSCREEN_BY_PART_ALIAS, partCount) ?: continue
			if (byPart == alias) {
				identical++
			} else {
				divergent++
				val offscreens = byPart.count { it >= 0 }
				val aliased = alias.count { it >= 0 }
				largestDivergence = "${file.name}: 152 names $offscreens offscreens, 160 names $aliased"
			}
		}
		println("[section-probe] s152 vs s160: $identical identical, $divergent divergent ($largestDivergence)")
		assertTrue(
			divergent > 0,
			"no corpus model distinguishes s152 from s160 - the alias claim cannot be refuted from this corpus, " +
				"so re-examine whether the fresh bake should synthesize it after all",
		)
	}
}
