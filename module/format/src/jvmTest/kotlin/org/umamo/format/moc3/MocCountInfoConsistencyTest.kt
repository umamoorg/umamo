package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocLowering
import org.umamo.format.moc3.io.LittleEndianReader
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections
import org.umamo.format.moc3.moc.Sizing
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CountInfo ↔ section-length self-consistency for the lowering's own output.
 *
 * Every CountInfo field sizes one or more sections, and the official runtime allocates from the field
 * and then indexes into the section.  A field that over-counts its section sends the runtime reading
 * past the end of the buffer it just allocated - inside the official core that is a segfault, not a
 * rejected file - and a field that under-counts silently truncates whatever addresses the tail.  Both
 * halves are produced by [MocLowering], from separate traversals of the same document, so nothing but
 * this check makes them agree.
 *
 * Asserted against the SYNTHESIZED bytes rather than the corpus file's raw slices: a raw slice runs to
 * the next section's offset and so includes alignment padding, which makes an exact length comparison
 * impossible.  The synthesized map holds element regions with no padding, and the lowering is
 * byte-exact against the corpus (`MocLoweringTest`), so an identity proven here holds of the real
 * editor-written files too.  Skips gracefully without samples.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
class MocCountInfoConsistencyTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
			?: emptyList()

	/**
	 * The CountInfo field sizing each per-object [Sizing] rule.  Mirrors `MocSections.count`, spelled
	 * out here on purpose: a test that derived its expectation from the code under test would agree
	 * with any change to it.
	 */
	private val countFieldBySizing: Map<Sizing, Int> =
		mapOf(
			Sizing.PER_PART to Sections.CI_PARTS,
			Sizing.PER_DEFORMER to Sections.CI_DEFORMERS,
			Sizing.PER_WARP to Sections.CI_WARPS,
			Sizing.PER_ROTATION to Sections.CI_ROTATIONS,
			Sizing.PER_DRAWABLE to Sections.CI_DRAWABLES,
			Sizing.PER_PARAMETER to Sections.CI_PARAMETERS,
			Sizing.PER_GLUE to Sections.CI_GLUES,
			Sizing.PER_RENDER_ORDER_GROUP to Sections.CI_RENDER_ORDER_GROUPS,
			Sizing.PER_RENDER_ORDER_CHILD to Sections.CI_RENDER_ORDER_CHILDREN,
			Sizing.PER_OFFSCREEN to Sections.CI_OFFSCREENS,
			Sizing.PER_BLENDSHAPE_WARP to Sections.CI_BLENDSHAPE_WARPS,
			Sizing.PER_BLENDSHAPE_MESH to Sections.CI_BLENDSHAPE_MESHES,
			Sizing.PER_BLENDSHAPE_ROTATION to Sections.CI_BLENDSHAPE_ROTATIONS,
			Sizing.PER_BLENDSHAPE_PART to Sections.CI_BLENDSHAPE_PARTS,
			Sizing.PER_BLENDSHAPE_GLUE to Sections.CI_BLENDSHAPE_GLUES,
			Sizing.PER_PART_FORM to Sections.CI_PART_FORMS,
			Sizing.PER_WARP_FORM to Sections.CI_WARP_FORMS,
			Sizing.PER_ROTATION_FORM to Sections.CI_ROTATION_FORMS,
			Sizing.PER_ARTMESH_FORM to Sections.CI_ARTMESH_FORMS,
			Sizing.PER_OFFSCREEN_FORM to Sections.CI_OFFSCREEN_KEYFORMS,
		)

	/**
	 * The CountInfo field sizing each [Sizing.TABLE] section.  A table section's element count comes
	 * from a CountInfo field rather than from its own sizing rule, so the pairing has to be written
	 * out.  Sections absent here are unchecked and reported as such.
	 */
	private val countFieldByTableSection: Map<Section, Int> =
		mapOf(
			Section.PART_DRAW_ORDER to Sections.CI_PART_FORMS,
			Section.WARP_OPACITY to Sections.CI_WARP_FORMS,
			Section.WARP_KEYFORM_INDEX to Sections.CI_WARP_FORMS,
			Section.ROTATION_OPACITY to Sections.CI_ROTATION_FORMS,
			Section.ROTATION_ANGLE to Sections.CI_ROTATION_FORMS,
			Section.ROTATION_ORIGIN_X to Sections.CI_ROTATION_FORMS,
			Section.ROTATION_ORIGIN_Y to Sections.CI_ROTATION_FORMS,
			Section.ROTATION_SCALE to Sections.CI_ROTATION_FORMS,
			Section.ROTATION_REFLECT_X to Sections.CI_ROTATION_FORMS,
			Section.ROTATION_REFLECT_Y to Sections.CI_ROTATION_FORMS,
			Section.ARTMESH_OPACITY to Sections.CI_ARTMESH_FORMS,
			Section.ARTMESH_DRAW_ORDER to Sections.CI_ARTMESH_FORMS,
			Section.KEYFORM_POSITION_INDEX to Sections.CI_ARTMESH_FORMS,
			Section.KEYFORM_POSITION_VALUES to CI_POSITION_FLOATS,
			Section.KEYFORM_BINDING_SLOT to CI_KEYFORM_BINDING_SLOTS,
			Section.KEYFORM_BINDING_START to Sections.CI_KEYFORM_BINDINGS,
			Section.KEYFORM_BINDING_COUNT to Sections.CI_KEYFORM_BINDINGS,
			Section.BINDING_KEY_OFFSET to CI_PARAMETER_BINDINGS,
			Section.BINDING_KEY_COUNT to CI_PARAMETER_BINDINGS,
			Section.KEY_POSITIONS to CI_KEY_POSITIONS,
			Section.ARTMESH_UV_DATA to CI_UV_FLOATS,
			Section.ARTMESH_INDEX_DATA to CI_TRIANGLE_INDICES,
			Section.MASK_INDEX_DATA to CI_MASK_INDICES,
			Section.RENDER_ORDER_CHILD_KIND to Sections.CI_RENDER_ORDER_CHILDREN,
			Section.RENDER_ORDER_CHILD_INDEX to Sections.CI_RENDER_ORDER_CHILDREN,
			Section.RENDER_ORDER_GROUP_INDEX to Sections.CI_RENDER_ORDER_CHILDREN,
			Section.GLUE_WEIGHTS to CI_GLUE_VERTICES,
			Section.GLUE_VERTEX_INDICES to CI_GLUE_VERTICES,
			Section.GLUE_INTENSITIES to CI_GLUE_INTENSITIES,
			Section.COLOR_MULTIPLY_R to CI_COLOR_MULTIPLY_ROWS,
			Section.COLOR_MULTIPLY_G to CI_COLOR_MULTIPLY_ROWS,
			Section.COLOR_MULTIPLY_B to CI_COLOR_MULTIPLY_ROWS,
			Section.COLOR_SCREEN_R to CI_COLOR_SCREEN_ROWS,
			Section.COLOR_SCREEN_G to CI_COLOR_SCREEN_ROWS,
			Section.COLOR_SCREEN_B to CI_COLOR_SCREEN_ROWS,
			Section.BLENDSHAPE_BINDING_KEY_OFFSET to CI_BLENDSHAPE_BINDINGS,
			Section.BLENDSHAPE_BINDING_KEY_COUNT to CI_BLENDSHAPE_BINDINGS,
			Section.BLENDSHAPE_BINDING_NEUTRAL to CI_BLENDSHAPE_BINDINGS,
			Section.BLENDSHAPE_RECORD_BINDING to CI_BLENDSHAPE_RECORDS,
			Section.BLENDSHAPE_RECORD_BASE to CI_BLENDSHAPE_RECORDS,
			Section.BLENDSHAPE_RECORD_SUBSTART to CI_BLENDSHAPE_RECORDS,
			Section.BLENDSHAPE_RECORD_CORNER_COUNT to CI_BLENDSHAPE_RECORDS,
			Section.BLENDSHAPE_RECORD_KEY_COUNT to CI_BLENDSHAPE_RECORDS,
			Section.BLENDSHAPE_SUB_INDEX to Sections.CI_BLENDSHAPE_SUB_CORNERS,
			Section.BLENDSHAPE_SUB_PARAMETER to Sections.CI_BLENDSHAPE_SUB_BINDINGS,
			Section.BLENDSHAPE_SUB_KEY_OFFSET to Sections.CI_BLENDSHAPE_SUB_BINDINGS,
			Section.BLENDSHAPE_SUB_KEY_COUNT to Sections.CI_BLENDSHAPE_SUB_BINDINGS,
			Section.BLENDSHAPE_SUB_KEYS to CI_BLENDSHAPE_SUB_KEYS,
			Section.BLENDSHAPE_SUB_WEIGHT_VALUES to CI_BLENDSHAPE_SUB_KEYS,
			Section.OFFSCREEN_OPACITY to Sections.CI_OFFSCREEN_KEYFORMS,
			Section.OFFSCREEN_KEYFORM_MULTIPLY_ROW to Sections.CI_OFFSCREEN_KEYFORMS,
			Section.OFFSCREEN_KEYFORM_SCREEN_ROW to Sections.CI_OFFSCREEN_KEYFORMS,
		)

	@Test
	fun countInfoFieldsMatchTheSectionsTheySize() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping CountInfo consistency test")
			return
		}
		val failures = ArrayList<String>()
		val uncheckedTableSections = sortedSetOf<String>()
		var checkedTotal = 0
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val doc = Moc3.decode(model)
			val version = doc.version
			val lowered = MocLowering.lower(doc)
			val countInfoBytes = lowered.getValue(Section.COUNT_INFO.indexIn(version))
			val countInfoReader = LittleEndianReader(countInfoBytes)
			val countInfo = IntArray(countInfoBytes.size / 4) { countInfoReader.readInt32() }

			for (section in Section.entries) {
				val index = section.indexIn(version)
				if (index < 0) {
					continue
				}
				val bytes = lowered[index] ?: continue
				val countField =
					if (section.sizing == Sizing.TABLE) {
						countFieldByTableSection[section]
					} else {
						countFieldBySizing[section.sizing]
					}
				if (countField == null) {
					uncheckedTableSections.add(section.name)
					continue
				}
				val declaredCount = countInfo.getOrElse(countField) { 0 }
				val expectedBytes = declaredCount * section.element.size
				checkedTotal++
				if (bytes.size != expectedBytes) {
					failures.add(
						"${file.name}: ${section.name}@$index is ${bytes.size} bytes but CountInfo[$countField]" +
							" declares $declaredCount × ${section.element.size} = $expectedBytes",
					)
				}
			}
		}
		if (uncheckedTableSections.isNotEmpty()) {
			println("[countinfo] table sections with no CountInfo pairing (unchecked): $uncheckedTableSections")
		}
		println("[countinfo] ${files.size} models, $checkedTotal section/field identities checked")
		failures.forEach { failureMessage -> println("[countinfo] FAIL $failureMessage") }
		assertTrue(failures.isEmpty(), "CountInfo disagrees with the sections it sizes:\n" + failures.joinToString("\n"))
		// A gate that checks nothing passes.  The corpus produces ~100 sized sections per model, so a
		// collapse to a handful means the lowering stopped producing, not that the identities improved.
		assertTrue(
			checkedTotal > 50 * files.size,
			"CountInfo consistency covered only $checkedTotal identities across ${files.size} models",
		)
	}

	private companion object {
		// CountInfo fields with no `Sections.CI_*` constant yet; see MOC3.md §5.6 section 0.
		const val CI_POSITION_FLOATS: Int = 10
		const val CI_KEYFORM_BINDING_SLOTS: Int = 11
		const val CI_PARAMETER_BINDINGS: Int = 13
		const val CI_KEY_POSITIONS: Int = 14
		const val CI_UV_FLOATS: Int = 15
		const val CI_TRIANGLE_INDICES: Int = 16
		const val CI_MASK_INDICES: Int = 17
		const val CI_GLUE_VERTICES: Int = 21
		const val CI_GLUE_INTENSITIES: Int = 22
		const val CI_COLOR_MULTIPLY_ROWS: Int = 23
		const val CI_COLOR_SCREEN_ROWS: Int = 24
		const val CI_BLENDSHAPE_BINDINGS: Int = 25
		const val CI_BLENDSHAPE_RECORDS: Int = 26
		const val CI_BLENDSHAPE_SUB_KEYS: Int = 31
	}
}