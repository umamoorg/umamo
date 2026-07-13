package org.umamo.format.moc3

import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Sections
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decode (Layer-2) internal-consistency checks: decoding exercises every base/grid index path, so a
 * clean run already proves the keyform-binding grid + value-table arithmetic stays in bounds; the
 * assertions then tie object/keyform shapes together. Skips gracefully without samples.
 */
class MocDecodeTest {
	private val samplesDir: File? = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }

	private fun samples(): List<File> =
		samplesDir?.walkTopDown()?.filter { it.isFile && it.extension == "moc3" }?.sortedBy { it.name }?.toList()
			?: emptyList()

	@Test
	fun decodesSamplesConsistently() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping decode test")
			return
		}
		for (file in files) {
			val model = MocCodec.read(file.readBytes())
			val doc = Moc3.decode(model)
			val v4plus = model.versionByte >= MocVersion.V42.byteValue

			// Counts line up with CountInfo / the structural layer.
			assertEquals(model.parameterCount, doc.parameters.size, "${file.name}: parameters")
			assertEquals(model.partCount, doc.parts.size, "${file.name}: parts")
			assertEquals(model.deformerCount, doc.deformers.size, "${file.name}: deformers")
			assertEquals(model.drawableCount, doc.artMeshes.size, "${file.name}: art meshes")
			assertEquals(
				model.countInfo[Sections.CI_WARPS],
				doc.deformers.count { it is WarpDeformer },
				"${file.name}: warps",
			)
			assertEquals(
				model.countInfo[Sections.CI_ROTATIONS],
				doc.deformers.count { it is RotationDeformer },
				"${file.name}: rotations",
			)

			// Keyform-binding axes are well-formed: parameters in range, keys ascending.
			for (binding in doc.bindings) {
				for (axis in binding.axes) {
					assertTrue(
						axis.parameterIndex in doc.parameters.indices,
						"${file.name}: binding ${binding.index} param index",
					)
					assertTrue(axis.keyCount > 0, "${file.name}: binding ${binding.index} has keys")
					assertTrue(
						(1 until axis.keyCount).all { axis.keyPositions[it - 1] <= axis.keyPositions[it] },
						"${file.name}: binding ${binding.index} keys ascending",
					)
				}
			}

			// Art meshes: keyform count == binding grid; each keyform's geometry matches the topology.
			for (mesh in doc.artMeshes) {
				val grid = doc.keyformBinding(mesh.keyformBindingIndex)?.gridSize ?: 1
				assertEquals(grid, mesh.keyforms.size, "${file.name}: ${mesh.id} keyform count == grid")
				assertEquals(mesh.vertexCount * 2, mesh.vertexUvs.size, "${file.name}: ${mesh.id} uv size")
				assertTrue(
					mesh.triangleIndices.all { (it.toInt() and 0xFFFF) < mesh.vertexCount },
					"${file.name}: ${mesh.id} triangle indices in range",
				)
				assertTrue(
					mesh.parentDeformerIndex == -1 || mesh.parentDeformerIndex in doc.deformers.indices,
					"${file.name}: ${mesh.id} parent deformer",
				)
				for (keyform in mesh.keyforms) {
					assertEquals(
						mesh.vertexCount * 2,
						keyform.vertexPositions.size,
						"${file.name}: ${mesh.id} keyform vertex count",
					)
					assertEquals(
						v4plus,
						keyform.multiplyColor != null,
						"${file.name}: ${mesh.id} color present iff v4+",
					)
				}
			}

			// Warp keyforms have the right control-point lattice size.
			for (deformer in doc.deformers) {
				if (deformer is WarpDeformer) {
					val cp = (deformer.rows + 1) * (deformer.columns + 1) * 2
					deformer.keyforms.forEach { assertEquals(cp, it.controlPoints.size, "${file.name}: warp cp size") }
				}
				assertTrue(
					deformer.parentDeformerIndex == -1 || deformer.parentDeformerIndex in doc.deformers.indices,
					"${file.name}: deformer parent",
				)
			}

			// Glue pairs reference valid vertices of their meshes.
			for (glue in doc.glues) {
				val meshA = doc.artMeshes[glue.meshAIndex]
				val meshB = doc.artMeshes[glue.meshBIndex]
				for (pair in glue.pairs) {
					assertTrue(
						pair.vertexA < meshA.vertexCount && pair.vertexB < meshB.vertexCount,
						"${file.name}: glue pair in range",
					)
				}
			}

			val totalChildren = doc.renderOrderGroups.sumOf { it.children.size }
			assertEquals(
				model.countInfo[Sections.CI_RENDER_ORDER_CHILDREN],
				totalChildren,
				"${file.name}: render-order children",
			)

			// Offscreens (moc 6): owner part in range.
			assertEquals(
				model.countInfo.getOrElse(Sections.CI_OFFSCREENS) { 0 },
				doc.offscreens.size,
				"${file.name}: offscreen count",
			)
			doc.offscreens.forEach {
				assertTrue(
					it.ownerPartIndex in doc.parts.indices,
					"${file.name}: offscreen owner part",
				)
			}

			// Blend shapes (moc 4+): drivers and targets in range, keys ascending, neutral valid.
			for (bs in doc.blendShapes) {
				assertTrue(bs.parameterIndex in doc.parameters.indices, "${file.name}: blendshape parameter")
				assertTrue(
					bs.keyPositions.isNotEmpty() && bs.neutralKeyIndex in bs.keyPositions.indices,
					"${file.name}: blendshape neutral",
				)
				assertTrue(
					(1 until bs.keyPositions.size).all { bs.keyPositions[it - 1] <= bs.keyPositions[it] },
					"${file.name}: blendshape keys ascending",
				)
				val targetOk =
					when (bs.target) {
						org.umamo.format.moc3.model.BlendShapeTarget.ART_MESH -> bs.targetIndex in doc.artMeshes.indices
						else -> bs.targetIndex in doc.deformers.indices
					}
				assertTrue(targetOk, "${file.name}: blendshape target ${bs.target} ${bs.targetIndex}")
			}

			println("${file.name}: v${model.versionByte} params=${doc.parameters.size} parts=${doc.parts.size} deformers=${doc.deformers.size} meshes=${doc.artMeshes.size} glues=${doc.glues.size} groups=${doc.renderOrderGroups.size} bindings=${doc.bindings.size} blendShapes=${doc.blendShapes.size} offscreens=${doc.offscreens.size}")
		}
	}
}
