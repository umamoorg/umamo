package org.umamo.interop.moc3

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.import.Moc3Import
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the two orderings a reference-free export gets from the sequence its lowering runs in.
 *
 * The keyform pool assigns binding indices by first intern, so the order parts, deformers, art meshes,
 * and glues are lowered in IS the file's binding numbering.  That numbering is a pure permutation -
 * nothing downstream reads it, and `Moc3ExportRoundTripTest` deliberately does not compare it - so a
 * reorder produces a different but equally correct file.  It is pinned anyway: a reorder is the visible
 * symptom of a producer moving relative to a data dependency it has, and that second thing is not
 * harmless.  Without this test, splitting the lowering into per-concern producers could reorder them
 * and every existing gate would stay green.
 *
 * The offscreen check is the other half.  An offscreen's keyform rows come from its owner part's
 * bundle, which the part lowering builds and hands on; on 14 of the 15 v6 corpus models an empty
 * hand-off is undetectable because they are fully static and the row count collapses to 1 either way.
 * Only `modelA.moc3` distinguishes the two.
 *
 * Skips gracefully without samples, so it covers nothing on CI - the corpus is gitignored.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class Moc3ExportBindingOrderTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir
			?.walkTopDown()
			// work/ holds our own bake outputs - same models, no new coverage.
			?.filter { it.isFile && it.extension == "moc3" && it.parentFile?.name != "work" }
			?.sortedBy { it.name }
			?.toList()
			.orEmpty()

	@Test
	fun bindingIndicesAreAssignedInLoweringOrder() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping export binding-order gate")
			return
		}
		val failures = ArrayList<String>()
		var covered = 0
		for (file in files) {
			val source = Moc3.decode(MocCodec.read(file.readBytes()))
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			val exported = Moc3Export.toMocDocument(puppet, source.version).document
			covered++

			// The pool reserves index 0 for the empty-axis record before anything can claim it, and every
			// static object stores 0 to name it.  A pool handed to a producer already primed, or built
			// fresh per producer, loses that reservation.
			val firstBinding = exported.bindings.firstOrNull()
			if (firstBinding == null || firstBinding.index != 0 || firstBinding.axes.isNotEmpty()) {
				failures.add("${file.name}: binding 0 is not the reserved empty-axis record (got $firstBinding)")
			}

			// Walking the document's own lists in the order the lowering produces them, each newly-seen
			// binding index must be larger than every index seen before.  Gaps are legal: a static part
			// interns a binding and then stores 0 instead, and a grid-less warp never interns at all.
			val seen = HashSet<Int>()
			var highestSoFar = -1
			val walk =
				exported.parts.map { "part ${it.id}" to it.keyformBindingIndex } +
					exported.deformers.map { "deformer ${it.id}" to it.keyformBindingIndex } +
					exported.artMeshes.map { "artMesh ${it.id}" to it.keyformBindingIndex } +
					exported.glues.map { "glue ${it.id}" to it.keyformBindingIndex }
			for ((subject, bindingIndex) in walk) {
				if (bindingIndex == 0 || !seen.add(bindingIndex)) {
					continue
				}
				if (bindingIndex < highestSoFar) {
					failures.add(
						"${file.name}: $subject introduces binding $bindingIndex after $highestSoFar - " +
							"the lowering ran out of order",
					)
				}
				highestSoFar = maxOf(highestSoFar, bindingIndex)
			}
		}
		assertTrue(covered > 0, "no corpus model to check binding order against")
		println("[export-order] $covered models, binding numbering follows lowering order")
		assertEquals(emptyList(), failures.take(25), "export binding order diverged")
	}

	@Test
	fun offscreenKeyformRowsComeFromTheOwnerPartsBundle() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping offscreen row gate")
			return
		}
		val failures = ArrayList<String>()
		var withOffscreens = 0
		var withMultiRowOffscreens = 0
		for (file in files) {
			val source = Moc3.decode(MocCodec.read(file.readBytes()))
			val puppet = Moc3Import.fromMocDocument(source, displayInfo = null)
			val exported = Moc3Export.toMocDocument(puppet, source.version).document
			if (exported.offscreens.isEmpty()) {
				continue
			}
			withOffscreens++
			for ((offscreenIndex, offscreen) in exported.offscreens.withIndex()) {
				val ownerPart = exported.parts.getOrNull(offscreen.ownerPartIndex)
				if (ownerPart == null) {
					failures.add("${file.name}: offscreen $offscreenIndex names part ${offscreen.ownerPartIndex}, which does not exist")
					continue
				}
				if (ownerPart.drawOrderKeyforms.size > 1) {
					withMultiRowOffscreens++
				}
				if (offscreen.keyforms.size != ownerPart.drawOrderKeyforms.size) {
					failures.add(
						"${file.name}: offscreen on ${ownerPart.id} has ${offscreen.keyforms.size} rows but its " +
							"owner part's grid has ${ownerPart.drawOrderKeyforms.size} - the part bundle did not reach it",
					)
				}
			}
		}
		assertTrue(withOffscreens > 0, "no corpus model carries an offscreen")
		// A fully-static owner part collapses to one row whether or not its bundle arrived, so a run that
		// saw only static owners proved nothing about the hand-off.
		assertTrue(
			withMultiRowOffscreens > 0,
			"every offscreen owner in this run was static - the part-bundle hand-off was not actually exercised",
		)
		println("[export-order] $withOffscreens models with offscreens, $withMultiRowOffscreens multi-row owners")
		assertEquals(emptyList(), failures.take(25), "offscreen keyform rows diverged from the owner part")
	}
}