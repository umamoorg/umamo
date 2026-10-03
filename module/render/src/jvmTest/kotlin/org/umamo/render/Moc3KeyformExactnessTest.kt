package org.umamo.render

import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.interop.moc3.import.Moc3Import
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A deformer child's keyforms come back through the editor's MOC3 path - the import, the rest pass that sets
 * each canvas mesh (restMeshesToCanvasSpace), and the export - within the precision of the keyforms' own space,
 * with no triangle turned over.  The import measures every delta from the default cell, in the keyforms' own
 * space, so nearly every component comes back bit for bit; a component far smaller than that base has no float
 * delta that reaches it, and comes back within one ulp of the base, the precision the space itself has.
 * Root-space meshes go through the canvas affine (ppu and origin), which no float round trip inverts exactly,
 * so they are not compared.
 *
 * Gated on `moc3.samples`; self-skips when it names no directory.
 */
class Moc3KeyformExactnessTest {
	private val samples: List<File> =
		System.getProperty("moc3.samples")
			?.let(::File)
			?.takeIf { directory -> directory.isDirectory }
			?.walkTopDown()
			?.filter { file -> file.isFile && file.extension == "moc3" }
			?.sortedBy { file -> file.name }
			?.toList()
			.orEmpty()

	@Test
	fun deformerChildKeyformsSurviveTheMoc3Path() {
		if (samples.isEmpty()) {
			println("moc3.samples not present; skipping the MOC3 keyform exactness check")
			return
		}
		val mismatches = ArrayList<String>()
		var comparedKeyforms = 0
		var exactComponents = 0L
		var comparedComponents = 0L
		var flippedTriangles = 0
		for (sample in samples) {
			val original = Moc3.read(sample.readBytes())
			val puppet = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(original, null))
			val exported = Moc3Export.toMocDocument(puppet).document
			val exportedById = exported.artMeshes.associateBy { mesh -> mesh.id }
			for (mesh in original.artMeshes) {
				if (mesh.parentDeformerIndex < 0) {
					continue
				}
				val exportedMesh = exportedById[mesh.id] ?: continue
				if (exportedMesh.keyforms.size != mesh.keyforms.size) {
					continue
				}
				val componentCount = mesh.keyforms.firstOrNull()?.vertexPositions?.size ?: continue
				// The base is one of the keyforms (the default cell), so the largest magnitude any keyform holds
				// at a component bounds the base's.
				val largestAt = FloatArray(componentCount) { componentIndex -> mesh.keyforms.maxOf { keyform -> abs(keyform.vertexPositions.getOrElse(componentIndex) { 0f }) } }
				val indices = IntArray(mesh.triangleIndices.size) { indexIndex -> mesh.triangleIndices[indexIndex].toInt() and 0xFFFF }
				for ((keyformIndex, keyform) in mesh.keyforms.withIndex()) {
					val expected = keyform.vertexPositions
					val actual = exportedMesh.keyforms[keyformIndex].vertexPositions
					if (expected.size != actual.size) {
						mismatches += "${sample.name} ${mesh.id} keyform $keyformIndex holds ${actual.size} components, not ${expected.size}"
						continue
					}
					for (componentIndex in expected.indices) {
						comparedComponents++
						if (expected[componentIndex].toRawBits() == actual[componentIndex].toRawBits()) {
							exactComponents++
							continue
						}
						val bound = Math.ulp(maxOf(abs(expected[componentIndex]), largestAt[componentIndex]))
						if (abs(expected[componentIndex] - actual[componentIndex]) > bound) {
							mismatches += "${sample.name} ${mesh.id} keyform $keyformIndex component $componentIndex: ${actual[componentIndex]}, not ${expected[componentIndex]}"
						}
					}
					flippedTriangles += flippedTriangleCount(expected, actual, indices)
					comparedKeyforms++
				}
			}
		}
		println("moc3 keyform exactness: ${samples.size} samples, $comparedKeyforms deformer-child keyforms, $exactComponents of $comparedComponents components exact, $flippedTriangles triangles flipped")
		assertTrue(comparedKeyforms > 0, "no sample has a deformer-child keyform to compare")
		assertTrue(mismatches.isEmpty(), "${mismatches.size} components moved past their space's precision:\n${mismatches.take(10).joinToString("\n")}")
		assertTrue(flippedTriangles == 0, "$flippedTriangles triangles turned over")
	}

	/**
	 * How many of a mesh's triangles wind the other way in [actual] than in [expected]; a triangle with no area
	 * in [expected] has no winding to keep and is not counted.
	 *
	 * @param FloatArray expected The original keyform's positions.
	 * @param FloatArray actual   The exported keyform's positions.
	 * @param IntArray   indices  The triangle indices.
	 * @return Int The count.
	 */
	private fun flippedTriangleCount(expected: FloatArray, actual: FloatArray, indices: IntArray): Int {
		var flipped = 0
		for (triangleIndex in 0 until indices.size / 3) {
			val expectedArea = signedArea(expected, indices, triangleIndex)
			if (expectedArea != 0.0 && expectedArea * signedArea(actual, indices, triangleIndex) < 0.0) {
				flipped++
			}
		}
		return flipped
	}

	/**
	 * Twice the signed area of one triangle, in double so a sliver keeps its sign.
	 *
	 * @param FloatArray positions     The interleaved positions.
	 * @param IntArray   indices       The triangle indices.
	 * @param Int        triangleIndex The triangle.
	 * @return Double The doubled signed area.
	 */
	private fun signedArea(positions: FloatArray, indices: IntArray, triangleIndex: Int): Double {
		val first = indices[triangleIndex * 3] * 2
		val second = indices[triangleIndex * 3 + 1] * 2
		val third = indices[triangleIndex * 3 + 2] * 2
		val firstX = positions[first].toDouble()
		val firstY = positions[first + 1].toDouble()
		return (positions[second] - firstX) * (positions[third + 1] - firstY) - (positions[second + 1] - firstY) * (positions[third] - firstX)
	}
}