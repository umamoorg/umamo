package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.interop.fidelity.FidelityGate
import org.umamo.interop.fidelity.FidelityLedger
import org.umamo.interop.fidelity.compareMocDocuments
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The core reference-free export gate: `moc3 -> PuppetModel -> moc3` preserves the rig.
 *
 * This is the test that says whether a reference-free export actually works.  It imports a corpus
 * MOC3, exports the resulting [org.umamo.runtime.model.PuppetModel] back to a document with NO
 * reference container, writes it, and compares the file as read back with the source, object by object
 * and keyform by keyform, through the fidelity comparator the export gates share.  A divergence fails
 * unless it is a known loss of this path.
 *
 * Comparison is by ID, never by list position.  A MOC3 addresses everything by index, but the
 * export re-derives those indices from the runtime model's own ordering (parents before children,
 * which the source file also satisfies but need not have satisfied in the same order), so equal
 * indices are not part of the contract - equal CONTENT under the same id is.  Keyform-binding
 * numbering is likewise not compared: a MOC3's binding indices are the editor's internal creation
 * order, unrecoverable from a runtime model, so the pool is semantically equivalent rather than
 * identically numbered.  Parameters are the exception, since nothing reorders them.
 *
 * A file Umamo wrote must also survive a second trip byte for byte: whatever the first trip changes
 * about a source, the second has nothing left to change.
 *
 * Scoped to v1-v6 - every version the format models.
 */
class Moc3ExportRoundTripTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	/** The versions the export currently covers in full. */
	private val supportedVersions = setOf(1, 2, 3, 4, 5, 6)

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
			skipTest("moc3.samples not present; skipping export round trip")
		}

		val failures = ArrayList<String>()
		val ledger = FidelityLedger(FidelityGate.MOC3_ROUND_TRIP)
		var covered = 0

		for (file in files) {
			val source = Moc3.read(file.readBytes())

			if (source.version.byteValue !in supportedVersions) {
				continue
			}

			covered++
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			val writtenBytes = Moc3.write(Moc3Export.toMocDocument(puppet, source.version).document)
			val exported = Moc3.read(writtenBytes)
			ledger.record(file.name, compareMocDocuments(source, exported))

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

			// Parameters keep their file order (nothing reorders them), so compare positionally.
			for (index in source.parameters.indices) {
				check("parameter[$index]", source.parameters.getOrNull(index), exported.parameters.getOrNull(index))
			}

			// A mesh's grid comes from its geometry, which keeps its axes whatever compaction does to its
			// channels, so a mesh keeps its keyform count exactly; the comparator allows a value-preserving
			// change of shape, which only a geometry-less owner may make.
			val sourceMeshesById = source.artMeshes.associateBy { it.id }

			for (mesh in exported.artMeshes) {
				val expected = sourceMeshesById[mesh.id] ?: continue
				check("mesh ${mesh.id} keyform count", expected.keyforms.size, mesh.keyforms.size)
			}

			// Offscreens (moc 6) carry one keyform row per cell of their OWNER PART's grid, and a row count
			// that disagrees with it desynchronizes the runtime's per-offscreen base for every later offscreen.
			for (offscreen in exported.offscreens) {
				val owner = exported.parts.getOrNull(offscreen.ownerPartIndex) ?: continue
				check("offscreen(${owner.id}) keyform rows", owner.drawOrderKeyforms.size, offscreen.keyforms.size)
			}

			val secondBytes = Moc3.write(Moc3Export.toMocDocument(Moc3Import.fromMocDocument(exported, displayInfo = null), source.version).document)

			if (!secondBytes.contentEquals(writtenBytes)) {
				val limit = minOf(writtenBytes.size, secondBytes.size)
				val firstDifference = (0 until limit).firstOrNull { offset -> writtenBytes[offset] != secondBytes[offset] } ?: limit
				failures.add(
					"${file.name}: a second trip changed the file Umamo wrote (sizes ${writtenBytes.size} and ${secondBytes.size}, first difference at byte $firstDifference)",
				)
			}
		}

		assertTrue(covered > 0, "no v1-v6 corpus model to round trip")
		ledger.tallyLines().forEach(::println)
		failures.addAll(0, ledger.problems())
		println("[export] round-tripped $covered v1-v6 models, ${failures.size} mismatches")
		assertEquals(emptyList(), failures.take(25), "export round trip diverged")
	}
}