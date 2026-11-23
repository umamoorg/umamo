package org.umamo.interop

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.MocDocument
import org.umamo.interop.cmo3.import.Cmo3Import
import org.umamo.interop.fidelity.DivergenceAspect
import org.umamo.interop.fidelity.FidelityGate
import org.umamo.interop.fidelity.FidelityLedger
import org.umamo.interop.fidelity.MocAttribute
import org.umamo.interop.fidelity.MocObjectKind
import org.umamo.interop.fidelity.compareMocDocuments
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A CMO3 exported to MOC3 describes the same rig as the editor's own bake of that model.
 *
 * The corpus holds TWINS - the same model saved as `.cmo3` and baked to `.moc3` by the official
 * editor - which makes this the only test that can check the export against something other than our
 * own reading of it.  Every other MOC3 gate starts from a moc: import it, write it back, compare.
 * That proves self-consistency and nothing about whether our lowering agrees with Cubism's.
 *
 * The export is written, read back, and compared with the bake through the fidelity comparator the
 * export gates share, every object matched by id.  Structure compares as equality: object sets,
 * kinds, parentage, mesh topology, mask lists, parameter ranges.  Those are decisions, not arithmetic -
 * if the editor put a drawable under a warp, so must we.  Keyform grids compare by what they evaluate
 * to, within the comparator's tolerance.  The CMO3 holds the editor's source-space values and the moc
 * its baked ones, and on every same-revision twin the export reproduces that transform to float
 * precision, so a value past the tolerance is a lowering that disagrees with the editor's.
 * `Cmo3ToMoc3OracleTest` compares the evaluated result through the runtime as well.  Object order is
 * not compared.
 *
 * The TEXTURE PAGE is not compared, and cannot be: a CMO3 has no per-drawable page field (it binds
 * through the layered-art web), so a CMO3-origin drawable carries no page index, and the packed pages a
 * moc indexes into are produced BY the bake - so the editor's page numbers describe a packing the
 * imported rig does not record.  What the export writes is its own page set (one per embedded image, or
 * the retained atlas for a MOC3-origin document), and the family it writes is self-consistent with it.
 * The UVs are compared.
 *
 * A divergence fails unless it is a known loss of this path.
 *
 * Gated on `cmo3.probe` + `moc3.samples`, joined by base name; self-skips whenever the two sets share
 * no base name, which includes either property being absent.
 */
class Cmo3ToMoc3CrossFormatTest {
	/**
	 * The corpus twins, keyed by base name.
	 *
	 * @return List Each (base name, cmo3, moc3) triple the corpus can pair.
	 */
	private fun twins(): List<Triple<String, File, File>> {
		val cmo3Files =
			System.getProperty("cmo3.probe")
				?.split(',')
				?.map { path -> File(path.trim()) }
				?.filter { file -> file.isFile }
				?.associateBy { file -> file.nameWithoutExtension }
				.orEmpty()
		val moc3Files =
			System.getProperty("moc3.samples")
				?.let(::File)
				?.takeIf { directory -> directory.isDirectory }
				?.walkTopDown()
				?.filter { file -> file.isFile && file.extension == "moc3" && file.parentFile?.name != "work" }
				?.associateBy { file -> file.nameWithoutExtension }
				.orEmpty()

		return cmo3Files.keys
			.intersect(moc3Files.keys)
			.sorted()
			.map { name -> Triple(name, cmo3Files.getValue(name), moc3Files.getValue(name)) }
	}

	/**
	 * Exports [cmo3File]'s rig at [baked]'s own version, so the comparison is version-for-version, and
	 * reads the written file back.
	 *
	 * @param File        cmo3File The source CMO3.
	 * @param MocDocument baked    The editor's bake of the same model.
	 * @return Pair The imported rig, and our lowering of it as written.
	 */
	private fun exportedFrom(cmo3File: File, baked: MocDocument): Pair<PuppetModel, MocDocument> {
		val model = Cmo3.read(cmo3File)
		val root = model.root as? CModelSource ?: error("${cmo3File.name}: root is not a CModelSource")
		val imported = Cmo3Import.fromModelSource(root)

		return imported to Moc3.read(Moc3.write(Moc3Export.toMocDocument(imported, baked.version).document))
	}

	@Test
	fun everyTwinLowersToTheSameStructure() {
		val twins = twins()

		if (twins.isEmpty()) {
			skipTest("no cmo3/moc3 twins in the corpus; skipping cross-format comparison")
		}

		val failures = ArrayList<String>()
		val ledger = FidelityLedger(FidelityGate.CMO3_TO_MOC3_VS_EDITOR)
		val differentRevisions = ArrayList<String>()
		var compared = 0

		for ((name, cmo3File, moc3File) in twins) {
			val baked = Moc3.read(moc3File.readBytes())
			val (source, ours) =
				runCatching { exportedFrom(cmo3File, baked) }.getOrElse { failure ->
					failures.add("$name: the CMO3 would not lower to a moc ($failure)")

					continue
				}
			// A twin pair is only an oracle when the two files are the same REVISION of the model.  The
			// corpus has one pair that is not - the moc was baked from a different edit of the project, and
			// it disagrees about meshes, parameters, and the deformer tree alike - so comparing them would
			// report dozens of "divergences" that say nothing about the lowering.  Detected rather than
			// named, so the rule survives a corpus change, and counted in the summary so a pair that
			// silently stops comparing cannot hide.
			val bakedMeshIds = baked.artMeshes.map { it.id }.toSet()
			val missingMeshes = bakedMeshIds - ours.artMeshes.map { it.id }.toSet()

			if (missingMeshes.size > bakedMeshIds.size / 10) {
				differentRevisions.add("$name (${missingMeshes.size}/${bakedMeshIds.size} art meshes absent)")

				continue
			}

			compared++
			// What the editor's bake omits and ours keeps: HIDDEN objects.  The official bake deletes them
			// unless "export invisible drawable" is ticked; Umamo asks the same through Moc3ExportOptions,
			// but this test lowers with the options-less API default, which keeps them (flag clear) -
			// deleting something the rigger can still see in the outliner is a silent destructive edit.
			// That makes an unexpected id acceptable ONLY when the source says it is hidden - anything
			// else is a bug.
			val hiddenParts = source.parts.filterNot { part -> part.isVisible }.mapTo(HashSet()) { it.id.raw }
			val hiddenDrawables =
				source.drawables
					.filterNot { drawable -> drawable.isVisible }
					.mapTo(HashSet()) { drawable -> drawable.id.raw }
			val partByDrawable = source.partByDrawable()
			val hiddenByPart =
				source.drawables
					.filter { drawable -> partByDrawable[drawable.id]?.raw in hiddenParts }
					.mapTo(HashSet()) { drawable -> drawable.id.raw }
			val divergences =
				compareMocDocuments(baked, ours, ignoredAttributes = setOf(MocAttribute.TEXTURE)).filterNot { divergence ->
					divergence.aspect == DivergenceAspect.Extra &&
						(
							(divergence.kind == MocObjectKind.ART_MESH && (divergence.id in hiddenDrawables || divergence.id in hiddenByPart)) ||
								(divergence.kind == MocObjectKind.PART && divergence.id in hiddenParts)
						)
				}
			ledger.record(name, divergences)
		}

		assertTrue(compared > 0, "no twin could be lowered")
		assertTrue(
			differentRevisions.size < twins.size / 2,
			"most corpus twins are no longer the same revision, so this gate has stopped testing anything: " +
				differentRevisions,
		)
		ledger.tallyLines().forEach(::println)
		failures.addAll(ledger.problems())
		println(
			"[cross-format] $compared cmo3/moc3 twins compared, ${failures.size} divergences" +
				(if (differentRevisions.isEmpty()) "" else "; skipped as different revisions: $differentRevisions"),
		)
		assertEquals(emptyList(), failures.take(25), "our lowering disagrees with the editor's bake")
	}
}