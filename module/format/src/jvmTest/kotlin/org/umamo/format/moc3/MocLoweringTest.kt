package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocLowering
import org.umamo.format.moc3.moc.MocCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val doc = Moc3.decode(model)
			val structural = MocLowering.structuralSections(doc)
			val valueTables = MocLowering.valueTableSections(doc)
			val auxiliary = MocLowering.auxiliarySections(doc)
			val grid = MocLowering.keyformGridSections(doc)
			assertTrue(structural.isNotEmpty(), "${file.name}: lowered some sections")
			for ((index, bytes) in structural + valueTables + auxiliary + grid) {
				val original = model.section(index)
				assertTrue(
					original != null && original.size >= bytes.size,
					"${file.name}: section $index present & sized (need ${bytes.size}, have ${original?.size})",
				)
				assertContentEquals(
					original.copyOf(bytes.size),
					bytes,
					"${file.name}: section $index lowered byte-exact",
				)
				// Guard against silently dropping data: anything in the original beyond our synthesized
				// element region must be zero padding (a synthesized section shorter than the original
				// with a nonzero tail - e.g. blend-shape deltas appended to a shared value table - would
				// be a truncation bug; such sections must be carried, not synthesized).
				val tail = original.copyOfRange(bytes.size, original.size)
				assertTrue(
					tail.all { it.toInt() == 0 },
					"${file.name}: section $index synthesized too short (nonzero tail dropped: ${tail.size} bytes)",
				)
			}
			// Full CountInfo synthesis is byte-exact for blend-shape/offscreen-free models (the
			// blend-shape totals are not yet synthesized). Validate it where applicable.
			if (doc.blendShapes.isEmpty() && doc.offscreens.isEmpty()) {
				val ci = MocLowering.countInfoSection(doc)
				assertContentEquals(
					model.section(0)!!.copyOf(ci.size),
					ci,
					"${file.name}: CountInfo synthesized byte-exact",
				)
			}
			val total = structural.size + valueTables.size + auxiliary.size + grid.size
			println("${file.name}: v${model.versionByte} ${structural.size}+${valueTables.size}+${auxiliary.size}+${grid.size} = $total sections lowered byte-exact")
		}
	}
}
