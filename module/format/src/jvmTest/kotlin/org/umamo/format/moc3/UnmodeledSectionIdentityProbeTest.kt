package org.umamo.format.moc3

import org.umamo.format.moc3.io.LittleEndianReader
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocModel
import org.umamo.format.moc3.moc.ParameterType
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the on-disk identity of the section indices the codec does not model yet, so they can be
 * folded into the typed `Section` enum knowing what they hold rather than guessing.
 *
 * These indices survive a bake today only because `MocEncoder.bake` carries them verbatim from
 * the reference container, which is exactly why a fresh synthesis is impossible while they stay
 * unidentified.
 *
 * Sections 137-142 are per-FORM color * row references, not blend-shape sections as that table says - and their multiply and screen
 * arrays are bit-identical, the same shape the offscreen keyform rows (162/163) already have.
 * Section 56's empty-slot filler is not arbitrary either: it is -1 for a NORMAL parameter and 0
 * for a BLEND_SHAPE one, which is what makes the column reproducible.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class UnmodeledSectionIdentityProbeTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
			?: emptyList()

	/**
	 * Reads a raw section as [count] little-endian `i32`s, or null when absent or too short.
	 *
	 * @param MocModel model The parsed container.
	 * @param Int      index The section-table index.
	 * @param Int      count How many entries to read.
	 * @return IntArray? The values, or null.
	 */
	private fun rawInts(model: MocModel, index: Int, count: Int): IntArray? {
		val bytes = model.section(index) ?: return null
		if (count == 0 || bytes.size < count * 4) {
			return null
		}
		val reader = LittleEndianReader(bytes)
		return IntArray(count) { reader.readInt32() }
	}

	/**
	 * The running prefix sum of [counts], each entry scaled by [scale] and shifted by [base].
	 *
	 * @param IntArray counts The per-object counts.
	 * @param Int      base   The value of entry 0.
	 * @param Int      scale  Multiplier applied to each count.
	 * @return IntArray The expected start column.
	 */
	private fun prefixSum(counts: IntArray, base: Int = 0, scale: Int = 1): IntArray {
		var running = base
		return IntArray(counts.size) { index ->
			val start = running
			running += counts[index] * scale
			start
		}
	}

	/**
	 * Sections 2, 29-32, 49, 89, 102, and 154 are per-object `u64` runtime slots: the runtime fills
	 * them after the memory-cast, so they are all-zero on disk and only need to be SIZED on write.
	 */
	@Test
	fun runtimeSlotSectionsAreAllZeroOnDisk() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping runtime slot probe")
			return
		}
		// Section index → the CountInfo field sizing it, and how many u64 slots each object gets.
		val slotSections =
			listOf(
				Triple(2, Sections.CI_PARTS, 1),
				Triple(29, Sections.CI_DRAWABLES, 1),
				Triple(30, Sections.CI_DRAWABLES, 1),
				Triple(31, Sections.CI_DRAWABLES, 1),
				Triple(32, Sections.CI_DRAWABLES, 1),
				Triple(49, Sections.CI_PARAMETERS, 1),
				Triple(89, Sections.CI_GLUES, 1),
				Triple(102, Sections.CI_PARAMETERS, 1),
				Triple(154, Sections.CI_OFFSCREENS, 1),
			)
		var checked = 0
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			for ((sectionIndex, countField, slotsPerObject) in slotSections) {
				val count = model.countInfo.getOrElse(countField) { 0 } * slotsPerObject
				val bytes = model.section(sectionIndex) ?: continue
				if (count == 0) {
					continue
				}
				val region = bytes.copyOf(minOf(bytes.size, count * 8))
				assertTrue(region.all { it.toInt() == 0 }, "${file.name}: s$sectionIndex is not all-zero")
				checked++
			}
		}
		assertTrue(checked > 0, "no runtime slot sections were examined")
		println("[section-probe] runtime slots: $checked all-zero regions across ${files.size} samples")
	}

	/**
	 * Sections 44, 45, 47, 5, and 81 are start columns: the running prefix sum of a count column that
	 * IS modeled.  Section 47's base is the offscreen mask block that precedes the drawable masks in
	 * section 80, so it is taken from entry 0 rather than assumed zero.
	 */
	@Test
	fun startColumnsArePrefixSumsOfTheirCountColumns() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping start column probe")
			return
		}
		var checked = 0
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val drawables = model.countInfo.getOrElse(Sections.CI_DRAWABLES) { 0 }
			val parts = model.countInfo.getOrElse(Sections.CI_PARTS) { 0 }
			val groups = model.countInfo.getOrElse(Sections.CI_RENDER_ORDER_GROUPS) { 0 }

			// s44 indexes section 78 (UV floats), so it advances by TWO per vertex.
			rawInts(model, 43, drawables)?.let { vertexCounts ->
				rawInts(model, 44, drawables)?.let { starts ->
					assertEquals(prefixSum(vertexCounts, scale = 2).toList(), starts.toList(), "${file.name}: s44")
					checked++
				}
			}
			rawInts(model, 46, drawables)?.let { indexCounts ->
				rawInts(model, 45, drawables)?.let { starts ->
					assertEquals(prefixSum(indexCounts).toList(), starts.toList(), "${file.name}: s45")
					checked++
				}
			}
			rawInts(model, 48, drawables)?.let { maskCounts ->
				rawInts(model, 47, drawables)?.let { starts ->
					assertEquals(
						prefixSum(maskCounts, base = starts.first()).toList(),
						starts.toList(),
						"${file.name}: s47 (base = the offscreen mask prefix)",
					)
					checked++
				}
			}
			rawInts(model, 6, parts)?.let { formCounts ->
				rawInts(model, 5, parts)?.let { starts ->
					assertEquals(prefixSum(formCounts).toList(), starts.toList(), "${file.name}: s5")
					checked++
				}
			}
			rawInts(model, 82, groups)?.let { childCounts ->
				rawInts(model, 81, groups)?.let { starts ->
					assertEquals(prefixSum(childCounts).toList(), starts.toList(), "${file.name}: s81")
					checked++
				}
			}
		}
		assertTrue(checked > 0, "no start columns were examined")
		println("[section-probe] start columns: $checked prefix-sum identities held")
	}

	/**
	 * Sections 137-142 are per-FORM color row references, `colorBase[object] + gridIndex`, and each
	 * pair's multiply and screen arrays are bit-identical.
	 *
	 * Our `MOC3.md` §5.6 currently lumps 115-148 together as blend-shape sections, which is wrong for
	 * these six: they belong to WarpForm / RotForm / ArtMeshForm and arrived in moc 5.  Both halves of
	 * the claim are checked here, per form of every object of every v5+ sample.
	 */
	@Test
	fun perFormColorReferencesAreBasePlusGridIndex() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping per-form color reference probe")
			return
		}
		// (object count field, keyform-base section, form-count section, colorBase section,
		//  multiply-ref section, screen-ref section, form count field)
		val groups =
			listOf(
				Triple(Sections.CI_WARPS, intArrayOf(20, 21, 105, 137, 138), 7),
				Triple(Sections.CI_ROTATIONS, intArrayOf(26, 27, 106, 139, 140), 8),
				Triple(Sections.CI_DRAWABLES, intArrayOf(35, 36, 107, 141, 142), 9),
			)
		var checkedForms = 0
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			if (model.version.byteValue < 5) {
				continue
			}
			for ((objectCountField, sections, formCountField) in groups) {
				val objectCount = model.countInfo.getOrElse(objectCountField) { 0 }
				val formCount = model.countInfo.getOrElse(formCountField) { 0 }
				val keyformBase = rawInts(model, sections[0], objectCount) ?: continue
				val formCounts = rawInts(model, sections[1], objectCount) ?: continue
				val colorBase = rawInts(model, sections[2], objectCount) ?: continue
				val multiplyRefs = rawInts(model, sections[3], formCount) ?: continue
				val screenRefs = rawInts(model, sections[4], formCount) ?: continue

				assertEquals(
					multiplyRefs.toList(),
					screenRefs.toList(),
					"${file.name}: s${sections[3]} and s${sections[4]} are not identical",
				)
				for (objectIndex in 0 until objectCount) {
					for (gridIndex in 0 until formCounts[objectIndex]) {
						val slot = keyformBase[objectIndex] + gridIndex
						assertTrue(slot < formCount, "${file.name}: form slot $slot past the color ref table")
						assertEquals(
							colorBase[objectIndex] + gridIndex,
							multiplyRefs[slot],
							"${file.name}: s${sections[3]}[$slot] != colorBase[$objectIndex] + $gridIndex",
						)
						checkedForms++
					}
				}
			}
		}
		assertTrue(checkedForms > 0, "no per-form color references were examined")
		println("[section-probe] per-form color refs: $checkedForms forms matched base + gridIndex")
	}

	/**
	 * Section 56 is the parameter's binding-set start: the prefix sum of section 57 over parameters
	 * that HAVE bindings, with the empty slots filled by -1 for a NORMAL parameter and 0 for a
	 * BLEND_SHAPE one.  Section 103 is the same shape over section 104, based at the end of the
	 * preceding key region rather than at zero.
	 *
	 * The empty-slot filler is what made this column look arbitrary: both values occur in one file
	 * (LimeBirb, modelF), and only the parameter type separates them.
	 */
	@Test
	fun parameterStartColumnsFollowTheTypedEmptySlotConvention() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping parameter start column probe")
			return
		}
		var checkedEmpty = 0
		var checkedFilled = 0
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val parameters = model.countInfo.getOrElse(Sections.CI_PARAMETERS) { 0 }
			val bindingCounts = rawInts(model, 57, parameters) ?: continue
			val starts = rawInts(model, 56, parameters) ?: continue
			// s114 (Parameter.Types) only exists from moc 4; before that every parameter is NORMAL.
			val types = rawInts(model, Section.PARAM_TYPE.indexIn(model.version), parameters)

			var running = 0
			for (parameterIndex in 0 until parameters) {
				if (bindingCounts[parameterIndex] > 0) {
					assertEquals(
						running,
						starts[parameterIndex],
						"${file.name}: s56[$parameterIndex] is not the running binding-set cursor",
					)
					running += bindingCounts[parameterIndex]
					checkedFilled++
				} else {
					val isBlendShape = types?.getOrNull(parameterIndex) == ParameterType.BLEND_SHAPE.ordinal
					assertEquals(
						if (isBlendShape) 0 else -1,
						starts[parameterIndex],
						"${file.name}: s56[$parameterIndex] empty slot does not follow the parameter type",
					)
					checkedEmpty++
				}
			}

			if (model.version.byteValue >= 4) {
				rawInts(model, 104, parameters)?.let { keyCounts ->
					rawInts(model, 103, parameters)?.let { keyStarts ->
						assertEquals(
							prefixSum(keyCounts, base = keyStarts.first()).toList(),
							keyStarts.toList(),
							"${file.name}: s103 is not a based prefix sum of s104",
						)
					}
				}
			}
		}
		assertTrue(checkedFilled > 0 && checkedEmpty > 0, "both filled and empty s56 slots must be exercised")
		println("[section-probe] s56: $checkedFilled cursor slots, $checkedEmpty typed empty slots")
	}
}
