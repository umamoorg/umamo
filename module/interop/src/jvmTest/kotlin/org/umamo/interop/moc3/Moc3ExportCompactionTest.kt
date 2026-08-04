package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Channel compaction must be invisible to the export.
 *
 * The import runs `withChannelsCompacted` by default: a channel track whose values are constant along
 * an axis has that axis's interior keys DROPPED, and a fully-constant track is lifted into a static.
 * The export's re-bundling is supposed to be the exact inverse - `refinedToUnion` re-interpolates the
 * dropped keys back - so importing the same file with compaction on and off and exporting both must
 * produce identical documents.
 *
 * This is worth its own gate because the two halves live far apart and neither is obviously the
 * other's inverse by inspection.  A compaction that drops one key too many, or a refinement that
 * restores it at a subtly different value, would leave the round trip passing (it compacts and
 * restores consistently) while quietly changing what a SECOND export writes.  Here the uncompacted
 * import is the control: it never lost the key in the first place.
 *
 * For an owner WITH geometry the comparison is exact: a MOC3 stores one grid per object, so every
 * channel track the import fans out starts with the geometry's own axes, and the geometry keeps those
 * axes in the union whatever compaction does to the channels.
 *
 * A GEOMETRY-LESS owner - a part, a glue - is different, and the difference is legitimate rather than
 * a bug.  Its axes come only from its channel tracks, so lifting a constant track to a static removes
 * the last axis and collapses the grid to a single cell.  The uncompacted export then writes N
 * identical values where the compacted one writes 1.  Both are correct and evaluate identically, so
 * the invariant asserted for those is the one that actually holds: the collapsed value equals every
 * value it replaced.
 */
class Moc3ExportCompactionTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	/** The versions the export currently covers in full. */
	private val supportedVersions = setOf(1, 2, 3, 4, 5, 6)

	private fun samples(): List<File> =
		samplesDir
			?.walkTopDown()
			?.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
			?.sortedBy { it.name }
			?.toList()
			.orEmpty()

	/**
	 * Asserts a geometry-less owner's track either kept its shape or collapsed value-preservingly.
	 *
	 * @param ArrayList failures    The shared collector.
	 * @param String    fileName    The model being checked.
	 * @param String    what        The owner and channel being checked.
	 * @param FloatArray control    The uncompacted values.
	 * @param FloatArray collapsed  The compacted values.
	 */
	private fun checkCollapsed(
		failures: ArrayList<String>,
		fileName: String,
		what: String,
		control: FloatArray,
		collapsed: FloatArray,
	) {
		if (control.contentEquals(collapsed)) {
			return
		}
		if (collapsed.size == 1 && control.all { value -> value == collapsed[0] }) {
			// The legitimate collapse: every cell held the same value, so one cell says the same thing.
			return
		}
		failures.add(
			"$fileName: $what collapsed lossily (uncompacted=${control.toList().take(4)} " +
				"compacted=${collapsed.toList().take(4)})",
		)
	}

	@Test
	fun compactionDoesNotChangeWhatTheExportWrites() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping export compaction test")
			return
		}
		val failures = ArrayList<String>()
		var covered = 0
		for (file in files) {
			val source = Moc3.decode(MocCodec.read(file.readBytes()))
			if (source.version.byteValue !in supportedVersions) {
				continue
			}
			covered++
			val compacted =
				Moc3Export.toMocDocument(
					Moc3Import.fromMocDocument(source, displayInfo = null, compactChannels = true),
					source.version,
				).document
			val uncompacted =
				Moc3Export.toMocDocument(
					Moc3Import.fromMocDocument(source, displayInfo = null, compactChannels = false),
					source.version,
				).document

			/**
			 * Records a divergence between the two exports.
			 *
			 * @param String what     What differed.
			 * @param Any?   expected The uncompacted (control) value.
			 * @param Any?   actual   The compacted value.
			 */
			fun check(what: String, expected: Any?, actual: Any?) {
				if (expected != actual) {
					failures.add("${file.name}: $what uncompacted=$expected compacted=$actual")
				}
			}

			// The document types implement structural equality over their arrays, so these compare values
			// and not identities - which is the whole point.
			check("parameters", uncompacted.parameters, compacted.parameters)
			val compactedParts = compacted.parts.associateBy { it.id }
			for (part in uncompacted.parts) {
				val other = compactedParts[part.id] ?: continue
				check("part ${part.id} parent", part.parentPartIndex, other.parentPartIndex)
				check("part ${part.id} visibility", part.isVisible, other.isVisible)
				checkCollapsed(failures, file.name, "part ${part.id} draw order", part.drawOrderKeyforms, other.drawOrderKeyforms)
			}
			check("deformers", uncompacted.deformers, compacted.deformers)
			// Geometry-less owners: a collapsed grid is expected, so assert the collapse is VALUE-preserving
			// rather than demanding the same cell count.
			val compactedGlues = compacted.glues.associateBy { it.id }
			for (glue in uncompacted.glues) {
				val other = compactedGlues[glue.id] ?: continue
				check("glue ${glue.id} mesh pair", glue.meshAIndex to glue.meshBIndex, other.meshAIndex to other.meshBIndex)
				check("glue ${glue.id} pairs", glue.pairs, other.pairs)
				checkCollapsed(failures, file.name, "glue ${glue.id} intensity", glue.intensityKeyforms, other.intensityKeyforms)
			}

			// Field-level for art meshes: dumping whole objects buries which value moved.
			val compactedMeshes = compacted.artMeshes.associateBy { it.id }
			for (mesh in uncompacted.artMeshes) {
				val other = compactedMeshes[mesh.id] ?: continue
				check("mesh ${mesh.id} keyform count", mesh.keyforms.size, other.keyforms.size)
				check("mesh ${mesh.id} binding", mesh.keyformBindingIndex, other.keyformBindingIndex)
				for (keyIndex in mesh.keyforms.indices) {
					val a = mesh.keyforms.getOrNull(keyIndex) ?: continue
					val b = other.keyforms.getOrNull(keyIndex) ?: continue
					if (!a.vertexPositions.contentEquals(b.vertexPositions)) {
						val worst =
							a.vertexPositions.indices.maxByOrNull { component ->
								kotlin.math.abs(a.vertexPositions[component] - (b.vertexPositions.getOrNull(component) ?: 0f))
							} ?: 0
						failures.add(
							"${file.name}: mesh ${mesh.id} keyform $keyIndex positions differ at $worst " +
								"uncompacted=${a.vertexPositions.getOrNull(worst)} compacted=${b.vertexPositions.getOrNull(worst)}",
						)
					}
					check("mesh ${mesh.id} keyform $keyIndex opacity", a.opacity, b.opacity)
					check("mesh ${mesh.id} keyform $keyIndex drawOrder", a.drawOrder, b.drawOrder)
				}
			}
			check("render-order groups", uncompacted.renderOrderGroups, compacted.renderOrderGroups)
			check("binding count", uncompacted.bindings.size, compacted.bindings.size)
			for (index in uncompacted.bindings.indices) {
				check("binding[$index]", uncompacted.bindings.getOrNull(index), compacted.bindings.getOrNull(index))
			}
		}
		assertTrue(covered > 0, "no v1-v6 corpus model to compare")
		println("[export] compaction-invariant across $covered v1-v6 models, ${failures.size} divergences")
		assertEquals(emptyList(), failures.take(15), "compaction changed the exported document")
	}
}
