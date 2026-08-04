package org.umamo.format.moc3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACDeformerSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CPartSource
import org.umamo.format.cmo3.model.gen.CPartSourceSet
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.moc3.io.LittleEndianReader
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocModel
import org.umamo.format.moc3.moc.Sections
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins which half of each paired MOC3 boolean flag column carries the editor's visibility toggle,
 * by joining every corpus CMO3 against its own baked MOC3 twin.
 *
 * Three object classes store two adjacent `Bool32` columns whose split has never been pinned:
 * parts (s7, s8), deformers (s13, s14), and art meshes (s37, s38).
 *
 * The corpus says the opposite of their guess.  Only the FIRST column of each pair ever deviates
 * from 1 - Azxiana hides 7 of 623 deformers in s13, miku_verycursed hides 3 of 100 art meshes in
 * s37 - while s14 and s38 are 1 on every object of every sample.  A column that is never anything
 * but 1 cannot be the toggle that hides things.
 *
 * The join is one-directional on purpose, because a bake normally DELETES what the editor hides:
 * across the twins, a CMO3-hidden object is simply absent from the moc (EricaTamamo drops 3 art
 * meshes, modelD drops 4 plus 2 parts, miku drops 5 plus a part).  Only miku_verycursed keeps
 * them - the editor's "export invisible ArtMesh" option - and there all 3 hidden meshes are
 * present with s37 = 0 and nothing else is.  So the provable direction is `s37 == 0` implies
 * hidden, and that is what this asserts; "hidden implies s37 == 0" is NOT assertable, both
 * because the object is usually gone and because the twins can drift (modelE hides ArtMesh153 in
 * a CMO3 saved after its bake, so the moc still has it visible).
 *
 * Two things stay deliberately unpinned.  Deformers: Azxiana is the only sample with an s13
 * deviation and it has no CMO3 twin, so s13 = visible is an inference from the art-mesh pair's
 * proven shape, not a join result.  Parts: no twin keeps a hidden part, so s7 and s8 are both
 * all-1 and nothing distinguishes which is `visible_artmeshes` and which is `visible_deformers`.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class PairedVisibilityFlagProbeTest {
	private val cmo3Probes: List<File> =
		System.getProperty("cmo3.probe")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()
	private val moc3SamplesDir: File? =
		System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	/** One paired-flag column group: the two section indices and the CountInfo field sizing them. */
	private data class FlagPair(
		val label: String,
		val countInfoField: Int,
		val idSection: Int,
		val firstFlagSection: Int,
		val secondFlagSection: Int,
	)

	private val flagPairs =
		listOf(
			FlagPair("part", Sections.CI_PARTS, Sections.PART_ID, 7, 8),
			FlagPair("deformer", Sections.CI_DEFORMERS, 11, 13, 14),
			FlagPair("artMesh", Sections.CI_DRAWABLES, Sections.DRAW_ID, 37, 38),
		)

	/**
	 * Reads a raw section as an [IntArray] of [count] entries, or null when the section is absent.
	 *
	 * These indices are not all in the typed `Section` enum yet, so the probe reads them positionally.
	 *
	 * @param MocModel model   The parsed container.
	 * @param Int      index   The section-table index.
	 * @param Int      count   How many `i32` entries to read.
	 * @return IntArray? The values, or null when the section is absent or short.
	 */
	private fun rawInts(model: MocModel, index: Int, count: Int): IntArray? {
		val bytes = model.section(index) ?: return null
		if (bytes.size < count * 4) {
			return null
		}
		val reader = LittleEndianReader(bytes)
		return IntArray(count) { reader.readInt32() }
	}

	/**
	 * Reads a raw ID section as fixed 64-byte records.
	 *
	 * @param MocModel model The parsed container.
	 * @param Int      index The section-table index.
	 * @param Int      count How many records to read.
	 * @return List<String>? The identifiers, or null when the section is absent or short.
	 */
	private fun rawIds(model: MocModel, index: Int, count: Int): List<String>? {
		val bytes = model.section(index) ?: return null
		if (bytes.size < count * Sections.ID_STRIDE) {
			return null
		}
		val reader = LittleEndianReader(bytes)
		return List(count) { reader.readFixedString(Sections.ID_STRIDE) }
	}

	/**
	 * Flattens a serializer collection payload into its elements.
	 *
	 * @param Any? collection The raw collection field value.
	 * @return List<Any?> The contained elements, empty when absent.
	 */
	private fun elements(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	@Test
	fun firstFlagOfEachPairCarriesVisibility() {
		if (cmo3Probes.isEmpty() || moc3SamplesDir == null) {
			println("cmo3.probe / moc3.samples not both present; skipping paired visibility flag probe")
			return
		}
		val mocByName =
			moc3SamplesDir.walkTopDown().filter { it.isFile && it.extension == "moc3" }
				.associateBy { it.nameWithoutExtension }
		val twins = cmo3Probes.mapNotNull { cmo3 -> mocByName[cmo3.nameWithoutExtension]?.let { cmo3 to it } }
		if (twins.isEmpty()) {
			println("no CMO3/MOC3 twins in the corpus; skipping paired visibility flag probe")
			return
		}

		var joinedDeviations = 0
		for ((cmo3File, moc3File) in twins.sortedBy { it.first.name }) {
			val root = Cmo3.read(cmo3File).root as? CModelSource ?: continue
			val model = MocCodec.read(moc3File.readBytes())
			val counts = model.countInfo

			// Each CMO3 source kind keyed by its authored id, which is also what the moc's id section holds.
			val hiddenIdsByKind =
				mapOf(
					"part" to
						elements((root.partSourceSet as? CPartSourceSet)?._sources)
							.filterIsInstance<CPartSource>()
							.filter { !it.isVisible }
							.mapNotNull { (it.id as? Id)?.idstr }
							.toSet(),
					"deformer" to
						elements((root.deformerSourceSet as? CDeformerSourceSet)?._sources)
							.filterIsInstance<ACDeformerSource>()
							.filter { !it.isVisible }
							.mapNotNull { (it.id as? Id)?.idstr }
							.toSet(),
					"artMesh" to
						elements((root.drawableSourceSet as? CDrawableSourceSet)?._sources)
							.filterIsInstance<CArtMeshSource>()
							.filter { !it.isVisible }
							.mapNotNull { (it.id as? Id)?.idstr }
							.toSet(),
				)

			for (pair in flagPairs) {
				val count = counts.getOrElse(pair.countInfoField) { 0 }
				if (count == 0) {
					continue
				}
				val ids = rawIds(model, pair.idSection, count) ?: continue
				val firstFlag = rawInts(model, pair.firstFlagSection, count) ?: continue
				val secondFlag = rawInts(model, pair.secondFlagSection, count) ?: continue

				// The second column has never been observed carrying anything but 1, on any object of any
				// sample.  If a future sample breaks this, the split is genuinely more complex than
				// "first = visible" and the docs must stop claiming otherwise.
				val secondDeviations = secondFlag.count { it != 1 }
				assertEquals(
					0,
					secondDeviations,
					"${moc3File.name}: ${pair.label} s${pair.secondFlagSection} is not constant 1",
				)

				val flaggedHidden = ids.filterIndexed { index, _ -> firstFlag[index] == 0 }.toSet()
				val cmo3Hidden = hiddenIdsByKind.getValue(pair.label)
				val mocIds = ids.toSet()

				// The provable direction: anything the moc flags zero is hidden in the editor.  The
				// converse does not hold - see the class docblock.
				assertEquals(
					emptySet(),
					flaggedHidden - cmo3Hidden,
					"${moc3File.name}: ${pair.label} s${pair.firstFlagSection} flags an object the CMO3 shows",
				)
				joinedDeviations += flaggedHidden.size
				if (flaggedHidden.isNotEmpty() || cmo3Hidden.isNotEmpty()) {
					println(
						"[flag-probe] ${moc3File.name} ${pair.label}: mocCount=$count " +
							"s${pair.firstFlagSection}Zero=${flaggedHidden.size} " +
							"cmo3Hidden=${cmo3Hidden.size} " +
							"keptInMoc=${cmo3Hidden.intersect(mocIds).size} " +
							"droppedByBake=${(cmo3Hidden - mocIds).size}",
					)
				}
			}
		}

		// A join that never saw a flagged object would pass vacuously and prove nothing about the split,
		// which is the exact failure mode a corpus gate is prone to.  miku_verycursed is the one sample
		// that exports its hidden art meshes instead of dropping them, so the twins must reach 3 here.
		assertEquals(
			3,
			joinedDeviations,
			"the paired-flag split was not exercised (expected miku_verycursed's 3 exported hidden meshes)",
		)
	}

	/**
	 * Census over EVERY corpus moc, twin or not: the second column of each pair is constant 1, and the
	 * only deviations anywhere are in the first columns.
	 *
	 * This is what carries the deformer half of the claim.  Azxiana is the sole sample with an s13
	 * deviation and it has no CMO3 twin, so the join above cannot reach it; pinning the census keeps
	 * the observation from silently evaporating, and a future sample that puts a zero in s8, s14, or
	 * s38 fails here rather than quietly invalidating the docs.
	 */
	@Test
	fun onlySecondFlagsAreConstantAcrossTheWholeCorpus() {
		val samplesDir = moc3SamplesDir
		if (samplesDir == null) {
			println("moc3.samples not present; skipping paired flag census")
			return
		}
		val files =
			samplesDir.walkTopDown().filter { it.isFile && it.extension == "moc3" }.sortedBy { it.name }.toList()
		if (files.isEmpty()) {
			println("no corpus moc3 files; skipping paired flag census")
			return
		}
		val firstColumnDeviations = HashMap<String, Int>()
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val counts = model.countInfo
			for (pair in flagPairs) {
				val count = counts.getOrElse(pair.countInfoField) { 0 }
				if (count == 0) {
					continue
				}
				val firstFlag = rawInts(model, pair.firstFlagSection, count) ?: continue
				val secondFlag = rawInts(model, pair.secondFlagSection, count) ?: continue
				assertEquals(
					0,
					secondFlag.count { it != 1 },
					"${file.name}: ${pair.label} s${pair.secondFlagSection} is not constant 1",
				)
				val zeros = firstFlag.count { it == 0 }
				if (zeros > 0) {
					firstColumnDeviations["${file.name} ${pair.label} s${pair.firstFlagSection}"] = zeros
					println("[flag-census] ${file.name} ${pair.label}: s${pair.firstFlagSection} zero on $zeros/$count")
				}
			}
		}
		// Pinned: the two samples that carry a deviation at all, and how many.  Azxiana's is the only
		// evidence that the deformer pair splits the same way the art-mesh pair provably does.
		assertEquals(
			mapOf(
				"Azxiana.moc3 deformer s13" to 7,
				"miku_verycursed.moc3 artMesh s37" to 3,
			),
			firstColumnDeviations,
			"corpus flag deviations changed",
		)
	}
}
