package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The core Phase 5 gate: `moc3 -> PuppetModel -> moc3` preserves the rig.
 *
 * This is the test that says whether a reference-free export actually works.  It imports a corpus
 * MOC3, exports the resulting [org.umamo.runtime.model.PuppetModel] back to a document with NO
 * reference container, and compares the two documents structurally.
 *
 * Comparison is by ID, never by list position.  A MOC3 addresses everything by index, but the
 * export re-derives those indices from the runtime model's own ordering (parents before children,
 * which the source file also satisfies but need not have satisfied in the same order), so equal
 * indices are not part of the contract - equal CONTENT under the same id is.  Keyform-binding
 * numbering is likewise not compared: a MOC3's binding indices are the editor's internal creation
 * order, unrecoverable from a runtime model, so the pool is semantically equivalent rather than
 * identically numbered.
 *
 * Scoped to v1-v5; offscreens (v6) are still out of the export's
 * scope; the higher versions join as those land.
 */
class Moc3ExportRoundTripTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	/** The versions the export currently covers in full. */
	private val supportedVersions = setOf(1, 2, 3, 4, 5)

	private fun samples(): List<File> =
		samplesDir
			?.walkTopDown()
			// work/ holds our own bake outputs - same models, no new coverage.
			?.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
			?.sortedBy { it.name }
			?.toList()
			.orEmpty()

	@Test
	fun exportedDocumentMatchesTheImportedOne() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping export round trip")
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
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			val exported = Moc3Export.toMocDocument(puppet, source.version).document

			/**
			 * Records a mismatch against this file.
			 *
			 * @param String what     What differed.
			 * @param Any?   expected The source's value.
			 * @param Any?   actual   The exported value.
			 */
			fun check(what: String, expected: Any?, actual: Any?) {
				if (expected != actual) {
					failures.add("${file.name}: $what expected=$expected actual=$actual")
				}
			}

			check("parameter count", source.parameters.size, exported.parameters.size)
			check("part count", source.parts.size, exported.parts.size)
			check("deformer count", source.deformers.size, exported.deformers.size)
			check("art mesh count", source.artMeshes.size, exported.artMeshes.size)
			check("glue count", source.glues.size, exported.glues.size)

			// Parameters keep their file order (nothing reorders them), so compare positionally.
			for (index in source.parameters.indices) {
				val expected = source.parameters.getOrNull(index)
				val actual = exported.parameters.getOrNull(index)
				check("parameter[$index]", expected, actual)
			}

			// Parts and drawables are compared by id: the export re-derives file order from the runtime
			// tree, which need not reproduce the source's ordering.
			val sourcePartsById = source.parts.associateBy { it.id }
			for (part in exported.parts) {
				val expected = sourcePartsById[part.id]
				if (expected == null) {
					failures.add("${file.name}: exported an unknown part ${part.id}")
					continue
				}
				// A part has no geometry, so its only axes come from its draw-order track - and compaction
				// lifts a CONSTANT track into the static, collapsing the grid to one cell.  That is
				// value-preserving, so the invariant is that the collapse says the same thing, not that the
				// cell count survived.
				if (part.drawOrderKeyforms.size != expected.drawOrderKeyforms.size) {
					val collapsedToConstant =
						part.drawOrderKeyforms.size == 1 &&
							expected.drawOrderKeyforms.all { value -> value == part.drawOrderKeyforms[0] }
					if (!collapsedToConstant) {
						failures.add(
							"${file.name}: part ${part.id} draw order collapsed lossily " +
								"(source=${expected.drawOrderKeyforms.toList().take(4)} " +
								"exported=${part.drawOrderKeyforms.toList().take(4)})",
						)
					}
				}
				check("part ${part.id} visibility", expected.isVisible, part.isVisible)
			}

			val sourceMeshesById = source.artMeshes.associateBy { it.id }
			for (mesh in exported.artMeshes) {
				val expected = sourceMeshesById[mesh.id]
				if (expected == null) {
					failures.add("${file.name}: exported an unknown art mesh ${mesh.id}")
					continue
				}
				check("mesh ${mesh.id} vertex count", expected.vertexCount, mesh.vertexCount)
				check("mesh ${mesh.id} texture", expected.textureIndex, mesh.textureIndex)
				check("mesh ${mesh.id} constant flags", expected.constantFlags, mesh.constantFlags)
				check("mesh ${mesh.id} keyform count", expected.keyforms.size, mesh.keyforms.size)
				// A moc mask column can carry -1 placeholders alongside real references; the import drops
				// them and the export does not invent them back, so compare against the VALID count.
				check(
					"mesh ${mesh.id} mask count",
					expected.maskDrawableIndices.count { index -> index >= 0 },
					mesh.maskDrawableIndices.size,
				)
				check("mesh ${mesh.id} index count", expected.triangleIndices.size, mesh.triangleIndices.size)
			}

			val sourceDeformersById = source.deformers.associateBy { it.id }
			for (deformer in exported.deformers) {
				val expected = sourceDeformersById[deformer.id]
				if (expected == null) {
					failures.add("${file.name}: exported an unknown deformer ${deformer.id}")
					continue
				}
				check("deformer ${deformer.id} kind", expected::class.simpleName, deformer::class.simpleName)
				check("deformer ${deformer.id} visibility", expected.isVisible, deformer.isVisible)
			}
		}
		assertTrue(covered > 0, "no v1-v5 corpus model to round trip")
		println("[export] round-tripped $covered v1-v5 models, ${failures.size} mismatches")
		assertEquals(emptyList(), failures.take(25), "export round trip diverged")
	}
}
