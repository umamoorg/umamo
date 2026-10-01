package org.umamo.ui.document

import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.render.canvasToParentSpaceFor
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A deformer child's keyforms come back bit for bit through the editor's whole MOC3 path: the loader's import,
 * the rest pass that moves each base into canvas space (restMeshesToCanvasSpace), and the export.
 *
 * The rest pass re-expresses every delta against a canvas-scale base while the keyforms stay in the parent
 * deformer's space; in float that difference keeps only the canvas magnitude's step, so every exported keyform
 * moved onto a 1/4096 grid.  The deltas are double now, and warp-lattice and rotation-local values pass the moc
 * conversion untouched, so the export must write each one exactly as the file held it.  Root-space meshes go
 * through the canvas affine (ppu and origin), which no float round trip inverts exactly, so they are not compared.
 *
 * Gated on `-Dmoc3.sample`; self-skips without it.
 */
class Moc3KeyformExactnessTest {
	private val sample: File? = System.getProperty("moc3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun deformerChildKeyformsSurviveTheEditorsMoc3Path() {
		val sampleFile = sample ?: return
		val directory = sampleFile.parentFile
		val original = Moc3.read(sampleFile.readBytes())
		val loaded =
			assertIs<DocumentLoad.Loaded>(
				buildMoc3Document(sampleFile.path, sampleFile.name, sampleFile.readBytes()) { reference ->
					File(directory, reference).takeIf { it.isFile }?.readBytes()
				},
			).document as Moc3Document
		val exported = Moc3Export.toMocDocument(loaded.puppet, canvasToParentSpace = canvasToParentSpaceFor(loaded.puppet)).document
		val exportedById = exported.artMeshes.associateBy { mesh -> mesh.id }
		val tolerance = Math.scalb(1.0, -40)
		var comparedKeyforms = 0
		val mismatches = ArrayList<String>()
		for (mesh in original.artMeshes) {
			if (mesh.parentDeformerIndex < 0) {
				continue
			}
			val exportedMesh = exportedById[mesh.id] ?: continue
			if (exportedMesh.keyforms.size != mesh.keyforms.size) {
				continue
			}
			for ((keyformIndex, keyform) in mesh.keyforms.withIndex()) {
				val expected = keyform.vertexPositions
				val actual = exportedMesh.keyforms[keyformIndex].vertexPositions
				val differing =
					if (expected.size != actual.size) {
						0
					} else {
						expected.indices.firstOrNull { componentIndex -> abs(expected[componentIndex].toDouble() - actual[componentIndex].toDouble()) > tolerance }
					}
				if (differing != null) {
					mismatches += "${mesh.id} keyform $keyformIndex component $differing: ${actual.getOrNull(differing)}, not ${expected.getOrNull(differing)}"
				}
				comparedKeyforms++
			}
		}
		println("moc3 keyform exactness: ${sampleFile.name}, $comparedKeyforms deformer-child keyforms compared, ${mismatches.size} moved")
		assertTrue(comparedKeyforms > 0, "the sample has no deformer-child keyform to compare")
		assertTrue(mismatches.isEmpty(), "${mismatches.size} keyforms moved:\n${mismatches.take(10).joinToString("\n")}")
	}
}