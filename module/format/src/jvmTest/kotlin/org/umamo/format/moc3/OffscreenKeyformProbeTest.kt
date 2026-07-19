package org.umamo.format.moc3

import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the v6 offscreen keyform layout on the Model A corpus sample (the only offscreen-carrying
 * model). Confirmed structure (see MOC3.md §5.6):
 *
 *  - An offscreen's keyforms ride its OWNER PART's keyform grid - there is no offscreen
 *    keyform-binding section; Σ owner-part grid sizes == CountInfo 36.
 *  - Section 161 (OFFSCREEN_OPACITY) holds one row per offscreen keyform, offscreen order.
 *  - The shared color tables (108-113) are PREFIXED by those same keyform rows; sections 162/163
 *    map keyform → color row and read as identity.
 *  - Section 158 (OFFSCREEN_MASK_BASE) is the cumulative scan of 159 (OFFSCREEN_MASK_COUNT),
 *    offset from the START of MASK_INDEX_DATA (80): the offscreen mask indices are the block's
 *    PREFIX and the drawables' masks follow.  (Originally misread as a suffix after the drawables'
 *    masks - refuted against the CMO3 clip lists and the runtime's s158 addressing: Model A's
 *    pupil offscreens clip the Whites masks, which live at block offsets 0-3.)
 *  - Section 152 (OFFSCREEN_BY_PART) is the inverse of 155 (OFFSCREEN_OWNER_PART).
 *
 * Refuted along the way: 158 is NOT a shared-binding index (Σ gridSize over its values far
 * exceeds CountInfo 36). Sections 154/160 remain unmodeled (mostly-zero on the corpus).
 */
class OffscreenKeyformProbeTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	@Test
	fun offscreenKeyformLayoutIsPinned() {
		val sample =
			samplesDir?.walkTopDown()?.firstOrNull { it.isFile && it.name.startsWith("modelA") && it.extension == "moc3" }
		if (sample == null) {
			println("moc3.samples/modelA not present; skipping offscreen probe")
			return
		}
		val model = MocCodec.read(sample.readBytes())
		val document = Moc3.decode(model)
		val sections = model.sections
		val offscreenCount = model.countInfo.getOrElse(Sections.CI_OFFSCREENS) { 0 }
		val offscreenKeyformTotal = model.countInfo.getOrElse(Sections.CI_OFFSCREEN_KEYFORMS) { 0 }
		assertEquals(offscreenCount, document.offscreens.size, "offscreen count")
		assertTrue(offscreenCount > 0, "Model A carries offscreens")

		// Keyforms ride the owner part's grid; the total matches CountInfo 36.
		val ownerGridSizes = document.offscreens.map { document.parts[it.ownerPartIndex].drawOrderKeyforms.size }
		assertEquals(offscreenKeyformTotal, ownerGridSizes.sum(), "Σ owner-part grid sizes == CI36")
		assertEquals(
			ownerGridSizes,
			document.offscreens.map { it.keyforms.size },
			"typed keyform runs follow the owner grids",
		)

		// Section 161 rows equal the typed opacities, in offscreen order.
		val opacityTable = sections.floatArray(Section.OFFSCREEN_OPACITY)
		val typedOpacities = document.offscreens.flatMap { offscreen -> offscreen.keyforms.map { it.opacity } }
		assertEquals(
			typedOpacities,
			opacityTable.toList().subList(0, offscreenKeyformTotal),
			"typed opacities equal section 161 rows",
		)

		// The color tables' prefix rows equal the typed keyform colors (multiply channel spot).
		val multiplyRed = sections.floatArray(Section.COLOR_MULTIPLY_R)
		val typedMultiplyRed = document.offscreens.flatMap { offscreen -> offscreen.keyforms.map { it.multiplyColor!!.r } }
		assertEquals(
			typedMultiplyRed,
			multiplyRed.toList().subList(0, offscreenKeyformTotal),
			"typed multiply-color reds equal the color-table prefix",
		)

		// Sections 162/163: keyform → color-row maps, identity on the corpus.
		for (rowMapSection in listOf(Section.OFFSCREEN_KEYFORM_MULTIPLY_ROW, Section.OFFSCREEN_KEYFORM_SCREEN_ROW)) {
			val rowMap = sections.intArray(rowMapSection)
			assertEquals(
				(0 until offscreenKeyformTotal).toList(),
				rowMap.toList().subList(0, offscreenKeyformTotal),
				"$rowMapSection is the identity keyform → color-row map",
			)
		}

		// Section 158 is the cumulative scan of 159, offset from the block start (the offscreen
		// masks are the PREFIX of MASK_INDEX_DATA; the drawables' masks follow).
		val maskBase = sections.intArray(Section.OFFSCREEN_MASK_BASE)
		val maskCounts = sections.intArray(Section.OFFSCREEN_MASK_COUNT)
		var runningMaskBase = 0
		for (offscreenIndex in 0 until offscreenCount) {
			assertEquals(runningMaskBase, maskBase[offscreenIndex], "158 cumulative at offscreen $offscreenIndex")
			runningMaskBase += maskCounts[offscreenIndex]
		}
		val drawableMaskTotal = model.drawables().sumOf { it.maskCount }
		val maskBytes = model.section(Sections.MASK_INDEX_DATA)!!
		val maskReader = org.umamo.format.moc3.io.LittleEndianReader(maskBytes)
		val maskTable = IntArray(maskBytes.size / 4) { maskReader.readInt32() }
		// The raw element region is 64-byte padded, so compare the meaningful rows and require
		// anything beyond them to be zero padding.
		assertTrue(
			maskTable.size >= runningMaskBase + drawableMaskTotal,
			"MASK_INDEX_DATA holds the offscreen prefix + drawable masks",
		)
		val typedMaskIndices = document.offscreens.flatMap { it.maskIndices.toList() }
		assertEquals(
			maskTable.toList().subList(0, runningMaskBase),
			typedMaskIndices,
			"typed offscreen mask indices equal the section 80 prefix",
		)
		val typedDrawableMasks = document.artMeshes.flatMap { it.maskDrawableIndices.toList() }
		assertEquals(
			maskTable.toList().subList(runningMaskBase, runningMaskBase + drawableMaskTotal),
			typedDrawableMasks,
			"typed drawable mask indices follow the offscreen prefix",
		)
		assertTrue(
			(runningMaskBase + drawableMaskTotal until maskTable.size).all { maskTable[it] == 0 },
			"nothing but zero padding follows the drawable masks",
		)

		// Section 152 is the inverse of 155.
		val byPart = sections.intArray(Section.OFFSCREEN_BY_PART)
		for (partIndex in document.parts.indices) {
			val expected = document.offscreens.indexOfFirst { it.ownerPartIndex == partIndex }
			assertEquals(expected, byPart[partIndex], "152 inverse-map at part $partIndex")
		}

		println(
			"[offscreen-probe] ${sample.name}: pinned - offscreens=$offscreenCount keyforms=$offscreenKeyformTotal " +
				"masks=$runningMaskBase ownerGrids=$ownerGridSizes",
		)
	}
}
