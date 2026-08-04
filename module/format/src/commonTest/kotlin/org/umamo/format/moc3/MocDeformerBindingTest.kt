package org.umamo.format.moc3

import org.umamo.format.moc3.encode.MocLowering
import org.umamo.format.moc3.io.LittleEndianReader
import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Part
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.RotationKeyform
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.format.moc3.model.WarpKeyform
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins that a lowered deformer's keyform binding is written identically to the unified column
 * (MOC3 §5.6 s12) and to its per-type column (s19 for a warp, s25 for a rotation).
 *
 * The two columns are the same datum stored twice and the official runtime may raise a MOC3
 * validation error if they disagree.  [org.umamo.format.moc3.model.Deformer] therefore carries ONE
 * `keyformBindingIndex` and the lowering projects both columns from it, which makes divergence
 * unrepresentable rather than merely unlikely.  This test is what keeps it that way: a refactor that
 * walked the unified list and the per-type lists separately would still compile and still pass every
 * corpus gate, because the corpus never diverges - it would only break on a file we emitted.
 *
 * The deformers below deliberately interleave warps and rotations with distinct binding indices,
 * so a lowering that confused a type-local index for a unified one cannot pass by coincidence.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6 s12</a>
 */
class MocDeformerBindingTest {
	/**
	 * Reads a lowered section back as an [IntArray].
	 *
	 * @param Map     sections The lowered section map, keyed by table index.
	 * @param Section section  The section to read.
	 * @param MocVersion version The version whose table index applies.
	 * @return IntArray The decoded values.
	 */
	private fun intsOf(sections: Map<Int, ByteArray>, section: Section, version: MocVersion): IntArray {
		val bytes = sections.getValue(section.indexIn(version))
		val reader = LittleEndianReader(bytes)
		return IntArray(bytes.size / 4) { reader.readInt32() }
	}

	@Test
	fun unifiedAndPerTypeKeyformBindingColumnsAgree() {
		val version = MocVersion.V50
		val warpKeyform = WarpKeyform(FloatArray(8), opacity = 1f, multiplyColor = null, screenColor = null)
		val rotationKeyform =
			RotationKeyform(
				originX = 0f,
				originY = 0f,
				angle = 0f,
				scale = 1f,
				reflectX = false,
				reflectY = false,
				opacity = 1f,
				multiplyColor = null,
				screenColor = null,
			)

		/**
		 * Builds a static warp on the given binding.
		 *
		 * @param String id      The deformer id.
		 * @param Int    binding The keyform-binding index.
		 * @return WarpDeformer The deformer.
		 */
		fun warp(id: String, binding: Int) =
			WarpDeformer(
				id = id,
				keyformBindingIndex = binding,
				isVisible = true,
				isEnabled = true,
				parentPartIndex = -1,
				parentDeformerIndex = -1,
				rows = 1,
				columns = 1,
				mode = 0,
				keyforms = listOf(warpKeyform),
			)

		/**
		 * Builds a static rotation on the given binding.
		 *
		 * @param String id      The deformer id.
		 * @param Int    binding The keyform-binding index.
		 * @return RotationDeformer The deformer.
		 */
		fun rotation(id: String, binding: Int) =
			RotationDeformer(
				id = id,
				keyformBindingIndex = binding,
				isVisible = true,
				isEnabled = true,
				parentPartIndex = -1,
				parentDeformerIndex = -1,
				baseAngle = 0f,
				keyforms = listOf(rotationKeyform),
			)

		val document =
			MocDocument(
				version = version,
				canvas = CanvasInfo(pixelsPerUnit = 1f, originX = 0f, originY = 0f, width = 10f, height = 10f),
				parameters = emptyList(),
				keyformBindings = mapOf(0 to KeyformBinding(index = 0, axes = emptyList())),
				parts = listOf(Part("Part0", -1, 0, floatArrayOf(0f))),
				// Interleaved on purpose: type-local indices (0,0,1,1,2) diverge from file indices here.
				deformers =
					listOf(
						warp("Warp0", binding = 0),
						rotation("Rotation0", binding = 0),
						warp("Warp1", binding = 0),
						rotation("Rotation1", binding = 0),
						warp("Warp2", binding = 0),
					),
				artMeshes = emptyList(),
				glues = emptyList(),
				renderOrderGroups = emptyList(),
			)

		val sections = MocLowering.structuralSections(document)
		val unified = intsOf(sections, Section.DEFORMER_KEYFORM_BINDING, version)
		val warpBindings = intsOf(sections, Section.WARP_KEYFORM_BINDING, version)
		val rotationBindings = intsOf(sections, Section.ROTATION_KEYFORM_BINDING, version)
		val types = intsOf(sections, Section.DEFORMER_TYPE, version)
		val localIndices = intsOf(sections, Section.DEFORMER_LOCAL_INDEX, version)

		assertEquals(document.deformers.size, unified.size, "unified binding column covers every deformer")
		for (deformerIndex in document.deformers.indices) {
			// Type 0 is a warp, anything else a rotation; the per-type column is addressed by s18.
			val perType =
				if (types[deformerIndex] == 0) {
					warpBindings[localIndices[deformerIndex]]
				} else {
					rotationBindings[localIndices[deformerIndex]]
				}
			assertEquals(
				document.deformers[deformerIndex].keyformBindingIndex,
				unified[deformerIndex],
				"deformer $deformerIndex: s12 carries the document's binding",
			)
			assertEquals(
				unified[deformerIndex],
				perType,
				"deformer $deformerIndex: s12 agrees with its per-type column (runtime rejects a mismatch)",
			)
		}
	}
}
