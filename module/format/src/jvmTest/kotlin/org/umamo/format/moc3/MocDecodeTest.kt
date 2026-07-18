package org.umamo.format.moc3

import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Sections
import org.umamo.format.moc3.model.BlendShapeKeyform
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

	/**
	 * Pins which blend-free v1/v3 samples carry the optional KEY_POSITIONS per-parameter union region
	 * (MOC3 §5.6 - an editor-version artifact the decoder records as `keyPositionsHasParameterUnion`).
	 * Catches a detector regression independently of MocLoweringTest's byte-exact reproduction.  A
	 * blend model always carries the region unconditionally, so the flag is left false there (gated).
	 */
	@Test
	fun keyPositionsUnionRegionFlagPinned() {
		val files = samples()
		if (files.isEmpty()) {
			println("moc3.samples not present; skipping union-region flag test")
			return
		}
		// The only corpus samples that append the union region; every other file (including all blend
		// models) omits it.  Probed across the corpus - byte-identical section tables split both ways.
		val carriesUnion = setOf("modelD.moc3")
		for (file in files) {
			val doc = Moc3.decode(MocCodec.read(file.readBytes()))
			assertEquals(
				file.name in carriesUnion,
				doc.keyPositionsHasParameterUnion,
				"${file.name}: keyPositionsHasParameterUnion",
			)
			if (doc.blendShapes.isNotEmpty()) {
				assertTrue(!doc.keyPositionsHasParameterUnion, "${file.name}: flag stays false on a blend model")
			}
		}
	}

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
				// Every glue's binding resolves through the shared cache - the decoder registers glue
				// bindings like every other object kind, so a glue-exclusive binding keeps its
				// parameter-driven intensity downstream and its table rows in a re-bake.
				assertTrue(
					doc.keyformBinding(glue.keyformBindingIndex) != null,
					"${file.name}: glue binding ${glue.keyformBindingIndex} registered",
				)
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
						org.umamo.format.moc3.model.BlendShapeTarget.PART -> bs.targetIndex in doc.parts.indices
						else -> bs.targetIndex in doc.deformers.indices
					}
				assertTrue(targetOk, "${file.name}: blendshape target ${bs.target} ${bs.targetIndex}")
				for (limit in bs.limits) {
					assertTrue(limit.parameterIndex in doc.parameters.indices, "${file.name}: limit parameter")
					assertTrue(
						limit.keyPositions.toList() == limit.keyPositions.toList().sorted(),
						"${file.name}: limit keys ascending",
					)
					assertEquals(limit.keyPositions.size, limit.weights.size, "${file.name}: limit weights parallel")
				}

				// Typed delta payloads: one keyform per key, kind matching the target, and the
				// neutral key's row all-zero (MOC3 §5.6: deltas are relative to the neutral).
				assertEquals(bs.keyPositions.size, bs.keyforms.size, "${file.name}: blendshape keyforms parallel keys")
				val kindMatches =
					bs.keyforms.all { keyform ->
						when (bs.target) {
							org.umamo.format.moc3.model.BlendShapeTarget.WARP -> keyform is BlendShapeKeyform.Warp
							org.umamo.format.moc3.model.BlendShapeTarget.ART_MESH -> keyform is BlendShapeKeyform.Mesh
							org.umamo.format.moc3.model.BlendShapeTarget.ROTATION -> keyform is BlendShapeKeyform.Rotation
							org.umamo.format.moc3.model.BlendShapeTarget.PART -> keyform is BlendShapeKeyform.Part
						}
					}
				assertTrue(kindMatches, "${file.name}: blendshape keyform kinds match ${bs.target}")
				when (val neutralKeyform = bs.keyforms[bs.neutralKeyIndex]) {
					is BlendShapeKeyform.Mesh -> {
						assertTrue(
							neutralKeyform.form.vertexPositions.all { it == 0f },
							"${file.name}: neutral mesh delta zero",
						)
						assertEquals(0f, neutralKeyform.form.opacity, "${file.name}: neutral mesh opacity delta")
						assertEquals(0f, neutralKeyform.form.drawOrder, "${file.name}: neutral mesh draw-order delta")
						// Color delta rows follow the same neutral-zero rule; this also pins the
						// delta-region anchoring in the shared color tables (MOC3 §5.6).
						neutralKeyform.form.multiplyColor?.let { neutralMultiply ->
							assertTrue(
								neutralMultiply.r == 0f && neutralMultiply.g == 0f && neutralMultiply.b == 0f,
								"${file.name}: neutral mesh multiply-color delta zero (got $neutralMultiply)",
							)
						}
						neutralKeyform.form.screenColor?.let { neutralScreen ->
							assertTrue(
								neutralScreen.r == 0f && neutralScreen.g == 0f && neutralScreen.b == 0f,
								"${file.name}: neutral mesh screen-color delta zero (got $neutralScreen)",
							)
						}
					}
					is BlendShapeKeyform.Warp -> {
						assertTrue(
							neutralKeyform.form.controlPoints.all { it == 0f },
							"${file.name}: neutral warp delta zero",
						)
						assertEquals(0f, neutralKeyform.form.opacity, "${file.name}: neutral warp opacity delta")
					}
					is BlendShapeKeyform.Rotation -> {
						assertEquals(0f, neutralKeyform.form.originX, "${file.name}: neutral rotation origin-x delta")
						assertEquals(0f, neutralKeyform.form.originY, "${file.name}: neutral rotation origin-y delta")
						assertEquals(0f, neutralKeyform.form.angle, "${file.name}: neutral rotation angle delta")
						assertEquals(0f, neutralKeyform.form.scale, "${file.name}: neutral rotation scale delta")
						assertEquals(0f, neutralKeyform.form.opacity, "${file.name}: neutral rotation opacity delta")
					}
					is BlendShapeKeyform.Part -> {
						assertEquals(0f, neutralKeyform.drawOrderDelta, "${file.name}: neutral part draw-order delta")
					}
				}
			}

			val limitAttachments = doc.blendShapes.sumOf { it.limits.size }
			val distinctLimits = doc.blendShapes.flatMap { it.limits }.distinct().size
			println(
				"${file.name}: v${model.versionByte} params=${doc.parameters.size} parts=${doc.parts.size} deformers=${doc.deformers.size} meshes=${doc.artMeshes.size} glues=${doc.glues.size} groups=${doc.renderOrderGroups.size} bindings=${doc.bindings.size} blendShapes=${doc.blendShapes.size} limits=$limitAttachments/$distinctLimits offscreens=${doc.offscreens.size}",
			)

			// Golden anchors for the blend-shape corpus models.
			val deltaKeyformTotal = doc.blendShapes.sumOf { it.keyforms.size }

			/**
			 * Sums the delta keyforms of the records targeting [target].
			 *
			 * @param org.umamo.format.moc3.model.BlendShapeTarget target The record kind to total.
			 * @return Int The delta-keyform count over that kind's records.
			 */
			fun deltaKeyformsOf(target: org.umamo.format.moc3.model.BlendShapeTarget): Int =
				doc.blendShapes.filter { it.target == target }.sumOf { it.keyforms.size }
			if (file.name.startsWith("modelA")) {
				assertEquals(60, doc.blendShapes.size, "Model A: blend-shape records")
				assertEquals(0, limitAttachments, "Model A: no blend-weight limits")
				assertEquals(178, deltaKeyformTotal, "Model A: delta keyforms")
				assertEquals(142, deltaKeyformsOf(org.umamo.format.moc3.model.BlendShapeTarget.WARP), "Model A: warp delta keyforms")
				assertEquals(24, deltaKeyformsOf(org.umamo.format.moc3.model.BlendShapeTarget.ART_MESH), "Model A: mesh delta keyforms")
				assertEquals(12, deltaKeyformsOf(org.umamo.format.moc3.model.BlendShapeTarget.ROTATION), "Model A: rotation delta keyforms")
			}
			if (file.name.startsWith("modelB")) {
				assertEquals(45, doc.blendShapes.size, "Model B: blend-shape records")
				assertEquals(44, limitAttachments, "Model B: limit attachments")
				assertEquals(1, distinctLimits, "Model B: one deduplicated limit curve")
				assertEquals(90, deltaKeyformTotal, "Model B: delta keyforms")
			}
			if (file.name.startsWith("modelC")) {
				assertEquals(124, doc.blendShapes.size, "Model C: blend-shape records (incl. 1 part-owned)")
				assertEquals(1, doc.blendShapes.count { it.target == org.umamo.format.moc3.model.BlendShapeTarget.PART }, "Model C: part records")
				assertEquals(234, limitAttachments, "Model C: limit attachments")
				assertEquals(7, distinctLimits, "Model C: deduplicated limit curves")
				assertEquals(270, deltaKeyformTotal, "Model C: delta keyforms")
				// The single part-owned record's typed payload (MOC3 §5.6 section 58 delta rows).
				val partRecord = doc.blendShapes.first { it.target == org.umamo.format.moc3.model.BlendShapeTarget.PART }
				assertEquals(
					listOf(BlendShapeKeyform.Part(-900f), BlendShapeKeyform.Part(0f)),
					partRecord.keyforms,
					"Model C: part record delta payload",
				)
			}
		}
	}
}
