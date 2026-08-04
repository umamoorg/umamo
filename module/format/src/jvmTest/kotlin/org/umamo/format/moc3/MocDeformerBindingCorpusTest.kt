package org.umamo.format.moc3

import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.Section
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Corpus gates for the duplicated deformer keyform binding: MOC3 §5.6 s12 (unified) against s19/s25
 * (per type).  The official runtime might raise a MOC3 validation error when the two disagree, so this
 * covers both directions - what the decoder takes on the way in, and what a bake emits on the way out.
 *
 * MocSectionsTest already pins the two RAW sections against each other in the source file.  These
 * two go further: the first pins the DECODER'S CHOICE (it takes the per-type value from s19/s25,
 * leaving s12 read but unused) against s12, and the second pins the emitted bytes after a bake,
 * which is the direction that can actually produce an unloadable file.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6 s12</a>
 */
class MocDeformerBindingCorpusTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
			?: emptyList()

	/**
	 * The value the decoder puts on each [org.umamo.format.moc3.model.Deformer] comes from the per-type
	 * section, so this is the assertion the unused s12 read in `MocDecoder` is positioned to make: the
	 * column it reads and the column it keeps carry the same binding for every deformer in the corpus.
	 */
	@Test
	fun decodedKeyformBindingMatchesTheUnifiedSection() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping decoded deformer binding test")
			return
		}
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val document = Moc3.decode(model)
			val unified = model.sections.intArray(Section.DEFORMER_KEYFORM_BINDING)
			assertEquals(document.deformers.size, unified.size, "${file.name}: s12 covers every deformer")
			for ((deformerIndex, deformer) in document.deformers.withIndex()) {
				assertEquals(
					unified[deformerIndex],
					deformer.keyformBindingIndex,
					"${file.name}: deformer $deformerIndex decoded binding matches s12",
				)
			}
		}
	}

	/**
	 * A baked file must keep the two columns in agreement, because that is what makes it loadable.
	 *
	 * Note this is NOT implied by the decode-side gate: it would still hold if the lowering wrote the
	 * unified and per-type columns from separate walks, right up until one of those walks changed.  A
	 * structurally edited document is the case that would break first, which is why the deformer list
	 * is reversed here - it permutes both the file order and every type-local index without inventing
	 * geometry, so the two columns can only stay aligned if they share one source.
	 */
	@Test
	fun bakedKeyformBindingColumnsAgree() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping baked deformer binding test")
			return
		}
		for (file in files) {
			val original = MocCodec.read(file.readBytes())
			val document = Moc3.decode(original)
			if (document.deformers.isEmpty()) {
				continue
			}
			val reordered =
				MocDocument(
					version = document.version,
					canvas = document.canvas,
					parameters = document.parameters,
					keyformBindings = document.bindings.associateBy { binding -> binding.index },
					parts = document.parts,
					deformers = document.deformers.reversed(),
					artMeshes = document.artMeshes,
					glues = document.glues,
					renderOrderGroups = document.renderOrderGroups,
					blendShapes = document.blendShapes,
					offscreens = document.offscreens,
					keyPositionsHasParameterUnion = document.keyPositionsHasParameterUnion,
				)
			val baked = MocCodec.read(Moc3.bake(original, reordered))
			val unified = baked.sections.intArray(Section.DEFORMER_KEYFORM_BINDING)
			val warpBindings = baked.sections.intArray(Section.WARP_KEYFORM_BINDING)
			val rotationBindings = baked.sections.intArray(Section.ROTATION_KEYFORM_BINDING)
			val types = baked.sections.intArray(Section.DEFORMER_TYPE)
			val localIndices = baked.sections.intArray(Section.DEFORMER_LOCAL_INDEX)
			for (deformerIndex in reordered.deformers.indices) {
				// Type 0 is a warp, anything else a rotation; the per-type column is addressed by s18.
				val perType =
					if (types[deformerIndex] == 0) {
						warpBindings[localIndices[deformerIndex]]
					} else {
						rotationBindings[localIndices[deformerIndex]]
					}
				assertEquals(
					reordered.deformers[deformerIndex].keyformBindingIndex,
					unified[deformerIndex],
					"${file.name}: baked deformer $deformerIndex s12 carries the document's binding",
				)
				assertEquals(
					unified[deformerIndex],
					perType,
					"${file.name}: baked deformer $deformerIndex s12 disagrees with its per-type column",
				)
			}
		}
	}
}
