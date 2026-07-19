package org.umamo.render

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.runtime.ingest.Cmo3Import
import org.umamo.runtime.ingest.Moc3Import
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.Deformer
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-import blend-binding parity: the same corpus model imported from `.cmo3` (editor forms)
 * and from its baked `.moc3` (delta rows + the grid-at-default reference re-added) must produce
 * structurally IDENTICAL BlendShapeBindings - same objects, driving parameters, keys, neutral
 * placement, and limit curves.
 *
 * Form VALUES are compared under per-kind ceilings rather than exactly - float noise accumulates
 * through the delta + grid-at-default reconstruction and the canvas-space rewrite.  Measured, the
 * corpus reconstructs SUB-PIXEL (mesh <= 0.36 px, control points and rotation fields <= 3e-4), so
 * the pinned ceilings sit just above that class and reject any space or reference-frame
 * regression outright.  End-to-end geometric parity against the Umamo C++ Runtime is
 * BlendShapeOracleTest's job; this test isolates the import mapping itself.
 *
 * Gated on `cmo3.probe` + `moc3.samples` (the build defaults both); skips when absent.
 */
class BlendBindingParityTest {
	@Test
	fun blendBindingsMatchAcrossImports() {
		val cmo3Files = System.getProperty("cmo3.probe")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()
		val moc3Root = System.getProperty("moc3.samples")?.let(::File)?.takeIf { it.isDirectory }
		val pairs =
			listOf("modelA", "modelB", "modelC").mapNotNull { prefix ->
				val cmo3File = cmo3Files.firstOrNull { it.name.startsWith(prefix) } ?: return@mapNotNull null
				val moc3File =
					moc3Root?.walkTopDown()?.firstOrNull { it.isFile && it.name == "$prefix.moc3" } ?: return@mapNotNull null
				prefix to (cmo3File to moc3File)
			}
		if (pairs.isEmpty()) {
			println("cmo3.probe/moc3.samples not present; skipping blend-binding parity")
			return
		}
		for ((name, files) in pairs) {
			val (cmo3File, moc3File) = files
			val cmo3Root = Cmo3.read(cmo3File).root as? CModelSource ?: error("$name: root is not a CModelSource")
			// Both sides through the same canvas-space rewrite so mesh form deltas share a base.
			val fromCmo3 = restMeshesToCanvasSpace(Cmo3Import.fromModelSource(cmo3Root))
			val fromMoc3 = restMeshesToCanvasSpace(Moc3Import.fromMocDocument(Moc3.decode(moc3File.readBytes()), null))

			var meshBindingPairs = 0
			var maxMeshDelta = 0f
			var maxScalarDelta = 0f
			val moc3DrawablesById = fromMoc3.drawables.associateBy { it.id }
			for (cmo3Drawable in fromCmo3.drawables) {
				val moc3Drawable = moc3DrawablesById[cmo3Drawable.id] ?: continue
				meshBindingPairs +=
					compareBindingLists(name, "drawable ${cmo3Drawable.id.raw}", cmo3Drawable.blendShapes, moc3Drawable.blendShapes) { cmo3Form, moc3Form ->
						var maxComponentDelta = 0f
						val componentCount = minOf(cmo3Form.positionDeltas.size, moc3Form.positionDeltas.size)
						assertEquals(cmo3Form.positionDeltas.size, moc3Form.positionDeltas.size, "$name: mesh form size")
						for (componentIndex in 0 until componentCount) {
							maxComponentDelta =
								maxOf(maxComponentDelta, abs(cmo3Form.positionDeltas[componentIndex] - moc3Form.positionDeltas[componentIndex]))
						}
						maxMeshDelta = maxOf(maxMeshDelta, maxComponentDelta)
						maxScalarDelta = maxOf(maxScalarDelta, abs(cmo3Form.drawOrder - moc3Form.drawOrder))
						maxScalarDelta = maxOf(maxScalarDelta, abs(cmo3Form.opacity - moc3Form.opacity))
					}
			}

			// Warp/rotation correspondence via the parent chains of shared drawables (a moc names its
			// deformers only by synthesized index, so ids cannot join directly).
			var warpBindingPairs = 0
			var rotationBindingPairs = 0
			var maxControlPointDelta = 0f
			var maxRotationDelta = 0f
			val cmo3DeformersById = fromCmo3.deformers.associateBy { it.id }
			val moc3DeformersById = fromMoc3.deformers.associateBy { it.id }
			val comparedDeformerPairs = HashSet<Pair<Any, Any>>()
			for (cmo3Drawable in fromCmo3.drawables) {
				val moc3Drawable = moc3DrawablesById[cmo3Drawable.id] ?: continue
				var cmo3ParentId = cmo3Drawable.parentDeformerId
				var moc3ParentId = moc3Drawable.parentDeformerId
				while (cmo3ParentId != null && moc3ParentId != null) {
					val cmo3Deformer = cmo3DeformersById[cmo3ParentId] ?: break
					val moc3Deformer = moc3DeformersById[moc3ParentId] ?: break
					if (!comparedDeformerPairs.add(cmo3Deformer.id to moc3Deformer.id)) {
						break
					}
					when (cmo3Deformer) {
						is Deformer.Warp -> {
							val moc3Warp = moc3Deformer as Deformer.Warp
							warpBindingPairs +=
								compareBindingLists(name, "warp ${cmo3Deformer.id.raw}", cmo3Deformer.blendShapes, moc3Warp.blendShapes) { cmo3Form, moc3Form ->
									assertEquals(cmo3Form.controlPoints.size, moc3Form.controlPoints.size, "$name: warp form size")
									for (componentIndex in cmo3Form.controlPoints.indices) {
										maxControlPointDelta =
											maxOf(maxControlPointDelta, abs(cmo3Form.controlPoints[componentIndex] - moc3Form.controlPoints[componentIndex]))
									}
								}
						}
						is Deformer.Rotation -> {
							val moc3Rotation = moc3Deformer as Deformer.Rotation
							rotationBindingPairs +=
								compareBindingLists(name, "rotation ${cmo3Deformer.id.raw}", cmo3Deformer.blendShapes, moc3Rotation.blendShapes) { cmo3Form, moc3Form ->
									maxRotationDelta = maxOf(maxRotationDelta, abs(cmo3Form.originX - moc3Form.originX))
									maxRotationDelta = maxOf(maxRotationDelta, abs(cmo3Form.originY - moc3Form.originY))
									maxRotationDelta = maxOf(maxRotationDelta, abs(cmo3Form.angle - moc3Form.angle))
									maxRotationDelta = maxOf(maxRotationDelta, abs(cmo3Form.scale - moc3Form.scale))
								}
						}
					}
					cmo3ParentId = cmo3Deformer.parent
					moc3ParentId = moc3Deformer.parent
				}
			}

			println(
				"[parity] $name: bindingPairs mesh=$meshBindingPairs warp=$warpBindingPairs rotation=$rotationBindingPairs " +
					"maxMeshDelta=$maxMeshDelta maxScalarDelta=$maxScalarDelta " +
					"maxControlPointDelta=$maxControlPointDelta maxRotationDelta=$maxRotationDelta",
			)
			assertTrue(meshBindingPairs + warpBindingPairs + rotationBindingPairs > 0, "$name: compared some bindings")
			// Ceilings pinned from the first measured run: maxMeshDelta 0.352 px (Model A) / 0.204
			// (Model C), maxControlPointDelta 1.3e-4, maxRotationDelta 2.5e-4, scalar deltas exactly
			// 0 - the reconstruction is sub-pixel across the corpus, so the ceilings sit just above
			// the measured float-noise class and reject any space or reference-frame regression
			// outright.
			assertTrue(maxMeshDelta <= 1f, "$name: mesh form parity ceiling (got $maxMeshDelta)")
			assertTrue(maxScalarDelta <= 0.001f, "$name: scalar form parity ceiling (got $maxScalarDelta)")
			assertTrue(maxControlPointDelta <= 0.001f, "$name: warp cp parity ceiling (got $maxControlPointDelta)")
			assertTrue(maxRotationDelta <= 0.01f, "$name: rotation parity ceiling (got $maxRotationDelta)")
		}
	}

	/**
	 * Compares two binding lists on one object: structural identity (driving parameters, keys,
	 * neutral index, limit curves, null pattern) asserted exactly; each non-neutral form pair is
	 * handed to [compareForms] for value measurement.
	 *
	 * @param String   modelName    The corpus model (for messages).
	 * @param String   objectLabel  The object under comparison (for messages).
	 * @param List     cmo3Bindings The CMO3-imported bindings.
	 * @param List     moc3Bindings The MOC3-imported bindings.
	 * @param Function compareForms Per-form value comparator.
	 * @return Int The number of binding pairs compared.
	 */
	private fun <TForm : Any> compareBindingLists(
		modelName: String,
		objectLabel: String,
		cmo3Bindings: List<BlendShapeBinding<TForm>>,
		moc3Bindings: List<BlendShapeBinding<TForm>>,
		compareForms: (TForm, TForm) -> Unit,
	): Int {
		assertEquals(cmo3Bindings.size, moc3Bindings.size, "$modelName: binding count on $objectLabel")
		val moc3ByParameter = moc3Bindings.associateBy { it.parameterId }
		for (cmo3Binding in cmo3Bindings) {
			val moc3Binding = moc3ByParameter[cmo3Binding.parameterId]
			assertTrue(moc3Binding != null, "$modelName: $objectLabel missing moc3 binding for ${cmo3Binding.parameterId.raw}")
			assertEquals(
				cmo3Binding.keys.toList(),
				moc3Binding.keys.toList(),
				"$modelName: keys on $objectLabel/${cmo3Binding.parameterId.raw}",
			)
			assertEquals(
				cmo3Binding.neutralIndex,
				moc3Binding.neutralIndex,
				"$modelName: neutral index on $objectLabel/${cmo3Binding.parameterId.raw}",
			)
			assertEquals(
				cmo3Binding.forms.map { it == null },
				moc3Binding.forms.map { it == null },
				"$modelName: form null pattern on $objectLabel/${cmo3Binding.parameterId.raw}",
			)
			assertEquals(
				cmo3Binding.limits.map { limit -> limit.parameterId to limit.points }.sortedBy { it.first.raw },
				moc3Binding.limits.map { limit -> limit.parameterId to limit.points }.sortedBy { it.first.raw },
				"$modelName: limit curves on $objectLabel/${cmo3Binding.parameterId.raw}",
			)
			for (formIndex in cmo3Binding.forms.indices) {
				val cmo3Form = cmo3Binding.forms[formIndex]
				val moc3Form = moc3Binding.forms[formIndex]
				if (cmo3Form != null && moc3Form != null) {
					compareForms(cmo3Form, moc3Form)
				}
			}
		}
		return cmo3Bindings.size
	}
}
