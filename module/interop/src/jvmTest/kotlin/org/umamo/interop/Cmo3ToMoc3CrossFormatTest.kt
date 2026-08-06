package org.umamo.interop

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.moc3.Moc3Export
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable
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
 * STRUCTURE is compared as equality, and by id: object sets, kinds, parentage, mesh topology, mask
 * lists, parameter ranges.  Those are decisions, not arithmetic - if the editor put a drawable under a
 * warp, so must we.
 *
 * The TEXTURE PAGE is not compared, and cannot be: a CMO3 has no atlas.  Its pixels are
 * embedded per drawable, and the packed pages a moc indexes into are produced BY the bake - so the
 * editor's page numbers describe a packing this file does not contain.  What the export writes is its
 * own page set (one per embedded image, or the retained atlas for a MOC3-origin document), and the
 * family it writes is self-consistent with it.
 *
 * GEOMETRY is not compared here at all.  The two files are separate authoring artifacts: the CMO3
 * holds the editor's source-space values and the moc holds its baked ones, and the transform between
 * them is the editor's own, not a spec.  `Cmo3ToMoc3OracleTest` compares the EVALUATED result through
 * the official core instead, which is the question that actually matters and the only one with a
 * defensible tolerance.
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
	 * Exports [cmo3File]'s rig at [baked]'s own version, so the comparison is version-for-version.
	 *
	 * @param File        cmo3File The source CMO3.
	 * @param MocDocument baked    The editor's bake of the same model.
	 * @return MocDocument Our lowering of the CMO3.
	 */
	private fun exportedFrom(cmo3File: File, baked: MocDocument): Pair<PuppetModel, MocDocument> {
		val model = Cmo3.read(cmo3File)
		val root = model.root as? CModelSource ?: error("${cmo3File.name}: root is not a CModelSource")
		val imported = Cmo3Import.fromModelSource(root)
		return imported to Moc3Export.toMocDocument(imported, baked.version).document
	}

	@Test
	fun everyTwinLowersToTheSameStructure() {
		val twins = twins()
		if (twins.isEmpty()) {
			println("no cmo3/moc3 twins in the corpus; skipping cross-format comparison")
			return
		}
		val failures = ArrayList<String>()
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
			// unless "export invisible ArtMesh" is ticked; Umamo has no such option yet and deleting
			// something the rigger can still see in the outliner is a silent destructive edit, so the
			// documented behaviour is to keep them (with the visibility flag clear).  That makes an
			// unexpected id acceptable ONLY when the source says it is hidden - anything else is a bug.
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

			/**
			 * Records a divergence against this twin.
			 *
			 * @param String what     What differed.
			 * @param Any?   expected The editor's bake.
			 * @param Any?   actual   Our lowering.
			 */
			fun check(what: String, expected: Any?, actual: Any?) {
				if (expected != actual) {
					failures.add("$name: $what editor=$expected umamo=$actual")
				}
			}

			/**
			 * Compares two id sets, reporting only what differs.
			 *
			 * Dumping both sets whole is what a plain equality assert does, and on a real model that is a
			 * thousand ids of which two matter.
			 *
			 * @param String what           The object category.
			 * @param Set    expected       The editor's ids.
			 * @param Set    actual         Ours.
			 * @param Set    keptWhenHidden Ids ours may carry that the bake drops for being hidden.
			 */
			fun checkIds(what: String, expected: Set<String>, actual: Set<String>, keptWhenHidden: Set<String>) {
				val missing = (expected - actual).sorted()
				val extra = (actual - expected - keptWhenHidden).sorted()
				if (missing.isNotEmpty() || extra.isNotEmpty()) {
					failures.add(
						"$name: $what differ - ${missing.size} missing ${missing.take(4)}, " +
							"${extra.size} unexpected ${extra.take(4)}",
					)
				}
			}

			checkIds(
				"art mesh ids",
				baked.artMeshes.map { it.id }.toSet(),
				ours.artMeshes.map { it.id }.toSet(),
				hiddenDrawables + hiddenByPart,
			)
			checkIds("part ids", baked.parts.map { it.id }.toSet(), ours.parts.map { it.id }.toSet(), hiddenParts)
			checkIds("parameter ids", baked.parameters.map { it.id }.toSet(), ours.parameters.map { it.id }.toSet(), emptySet())
			checkIds("deformer ids", baked.deformers.map { it.id }.toSet(), ours.deformers.map { it.id }.toSet(), emptySet())

			val ourMeshes = ours.artMeshes.associateBy { it.id }
			val ourParts = ours.parts.associateBy { it.id }
			val ourDeformers = ours.deformers.associateBy { it.id }
			val ourParameters = ours.parameters.associateBy { it.id }

			for (mesh in baked.artMeshes) {
				val mine = ourMeshes[mesh.id] ?: continue
				check("${mesh.id} vertex count", mesh.vertexCount, mine.vertexCount)
				check("${mesh.id} triangle indices", mesh.triangleIndices.size, mine.triangleIndices.size)
				// Resolved to IDS: the two files number their objects independently, so an index compare
				// would report every model with a different authoring order as wrong.
				check(
					"${mesh.id} parent deformer",
					baked.deformers.getOrNull(mesh.parentDeformerIndex)?.id,
					ours.deformers.getOrNull(mine.parentDeformerIndex)?.id,
				)
				check(
					"${mesh.id} owning part",
					baked.parts.getOrNull(mesh.parentPartIndex)?.id,
					ours.parts.getOrNull(mine.parentPartIndex)?.id,
				)
				check(
					"${mesh.id} clip masks",
					mesh.maskDrawableIndices.filter { it >= 0 }.mapNotNull { baked.artMeshes.getOrNull(it)?.id }.sorted(),
					mine.maskDrawableIndices.filter { it >= 0 }.mapNotNull { ours.artMeshes.getOrNull(it)?.id }.sorted(),
				)
			}

			for (part in baked.parts) {
				val mine = ourParts[part.id] ?: continue
				check(
					"part ${part.id} parent",
					baked.parts.getOrNull(part.parentPartIndex)?.id,
					ours.parts.getOrNull(mine.parentPartIndex)?.id,
				)
			}

			for (deformer in baked.deformers) {
				val mine = ourDeformers[deformer.id] ?: continue
				check("deformer ${deformer.id} kind", deformer::class.simpleName, mine::class.simpleName)
				check(
					"deformer ${deformer.id} parent",
					baked.deformers.getOrNull(deformer.parentDeformerIndex)?.id,
					ours.deformers.getOrNull(mine.parentDeformerIndex)?.id,
				)
				if (deformer is WarpDeformer && mine is WarpDeformer) {
					check("deformer ${deformer.id} lattice", deformer.rows to deformer.columns, mine.rows to mine.columns)
				}
				if (deformer is RotationDeformer && mine is RotationDeformer) {
					check("deformer ${deformer.id} base angle", deformer.baseAngle, mine.baseAngle)
				}
			}

			for (parameter in baked.parameters) {
				val mine = ourParameters[parameter.id] ?: continue
				check("parameter ${parameter.id} minimum", parameter.minimumValue, mine.minimumValue)
				check("parameter ${parameter.id} maximum", parameter.maximumValue, mine.maximumValue)
				check("parameter ${parameter.id} default", parameter.defaultValue, mine.defaultValue)
				check("parameter ${parameter.id} type", parameter.type, mine.type)
			}
		}
		assertTrue(compared > 0, "no twin could be lowered")
		assertTrue(
			differentRevisions.size < twins.size / 2,
			"most corpus twins are no longer the same revision, so this gate has stopped testing anything: " +
				differentRevisions,
		)
		println(
			"[cross-format] $compared cmo3/moc3 twins compared, ${failures.size} structural divergences" +
				(if (differentRevisions.isEmpty()) "" else "; skipped as different revisions: $differentRevisions"),
		)
		assertEquals(emptyList(), failures.take(25), "our lowering disagrees with the editor's bake")
	}
}
