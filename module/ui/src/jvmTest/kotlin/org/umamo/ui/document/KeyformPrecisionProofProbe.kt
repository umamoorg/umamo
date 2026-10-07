package org.umamo.ui.document

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.runtime.eval.cellsByLinearIndex
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Measures, on one MOC3 family, how exactly the editor's whole MOC3 path gives back a deformer child's keyforms: the
 * loader's import and canvas-space rest pass, then the export.  Every keyform component of every warp- or
 * rotation-parented mesh is compared with the file it came from, its error measured, and each triangle checked
 * for a winding the rebuilt keyform flipped.  One vertex is traced through each value it passes through, so a
 * report can show the arithmetic on real numbers.  Written to compile whether a build stores its deltas as float32
 * or float64, so the same probe measures either.
 *
 * A probe, not a gate: it asserts nothing about the numbers and self-skips without `-Dproof.sample`.
 * `-Dproof.mesh`, `-Dproof.keyform`, and `-Dproof.component` pin the traced vertex, so both builds trace the same one.
 */
class KeyformPrecisionProofProbe {
	/**
	 * A component of a delta array as a double, whichever precision this build stores.
	 *
	 * @param Any deltas         The delta array.
	 * @param Int componentIndex The component.
	 * @return Double? The value, or null past the array's end.
	 */
	private fun deltaComponent(deltas: Any, componentIndex: Int): Double? =
		when (deltas) {
			is FloatArray -> deltas.getOrNull(componentIndex)?.toDouble()
			is DoubleArray -> deltas.getOrNull(componentIndex)
			else -> null
		}

	/**
	 * A float's raw bits as eight hexadecimal digits.
	 *
	 * @param Float value The value.
	 * @return String The digits.
	 */
	private fun bitsOf(value: Float): String = "%08x".format(value.toRawBits())

	/**
	 * A double's raw bits as sixteen hexadecimal digits.
	 *
	 * @param Double value The value.
	 * @return String The digits.
	 */
	private fun bitsOf(value: Double): String = "%016x".format(value.toRawBits())

	@Test
	fun proveKeyformPrecision() {
		val sampleFile = System.getProperty("proof.sample")?.let(::File)?.takeIf { file -> file.isFile } ?: return
		val directory = sampleFile.parentFile
		val bytes = sampleFile.readBytes()
		val original = Moc3.read(bytes)
		val loaded =
			assertIs<DocumentLoad.Loaded>(
				buildMoc3Document(sampleFile.path, sampleFile.name, bytes) { reference -> File(directory, reference).takeIf { file -> file.isFile }?.readBytes() },
			).document as Moc3Document
		val puppet = loaded.puppet
		val exported = Moc3Export.toMocDocument(puppet).document
		val exportedById = exported.artMeshes.associateBy { mesh -> mesh.id }
		val drawableById = puppet.drawables.associateBy { drawable -> drawable.id.raw }

		var warpComponents = 0L
		var rotationComponents = 0L
		var mismatched = 0L
		var largestError = 0.0
		var largestErrorAt = ""
		var keyformsCompared = 0L
		var keyformsWithFlips = 0L
		var flippedTriangles = 0L
		var meshesWithFlips = 0
		for (mesh in original.artMeshes) {
			val parent = original.deformers.getOrNull(mesh.parentDeformerIndex) ?: continue
			val exportedMesh = exportedById[mesh.id] ?: continue
			if (exportedMesh.keyforms.size != mesh.keyforms.size) {
				continue
			}
			val indices = IntArray(mesh.triangleIndices.size) { index -> mesh.triangleIndices[index].toInt() and 0xFFFF }
			var meshFlipped = false
			for ((keyformIndex, keyform) in mesh.keyforms.withIndex()) {
				val expected = keyform.vertexPositions
				val actual = exportedMesh.keyforms[keyformIndex].vertexPositions
				if (expected.size != actual.size) {
					continue
				}
				keyformsCompared++
				for (componentIndex in expected.indices) {
					when (parent) {
						is WarpDeformer -> warpComponents++
						is RotationDeformer -> rotationComponents++
						else -> {}
					}
					if (expected[componentIndex].toRawBits() != actual[componentIndex].toRawBits()) {
						mismatched++
						val error = abs(expected[componentIndex].toDouble() - actual[componentIndex].toDouble())
						if (error > largestError) {
							largestError = error
							largestErrorAt = "${mesh.id} keyform $keyformIndex component $componentIndex"
						}
					}
				}
				var flipsHere = 0
				for (triangle in 0 until indices.size / 3) {
					val first = indices[triangle * 3]
					val second = indices[triangle * 3 + 1]
					val third = indices[triangle * 3 + 2]

					/**
					 * Twice the triangle's signed area.
					 *
					 * @param FloatArray points The positions.
					 * @return Double The doubled signed area.
					 */
					fun area(points: FloatArray): Double =
						(points[2 * second] - points[2 * first].toDouble()) * (points[2 * third + 1] - points[2 * first + 1].toDouble()) -
							(points[2 * third] - points[2 * first].toDouble()) * (points[2 * second + 1] - points[2 * first + 1].toDouble())
					val before = area(expected)
					val after = area(actual)
					if (before != 0.0 && (after == 0.0 || (before > 0) != (after > 0))) {
						flipsHere++
					}
				}
				if (flipsHere > 0) {
					keyformsWithFlips++
					flippedTriangles += flipsHere
					meshFlipped = true
				}
			}
			if (meshFlipped) {
				meshesWithFlips++
			}
		}

		// The traced vertex: pinned, or the warp child whose canvas rest x is largest (the canvas magnitude is what
		// costs a float32 delta its precision), keyform 0, component 0.
		val pinnedMesh = System.getProperty("proof.mesh")
		val tracedMesh =
			if (pinnedMesh != null) {
				original.artMeshes.first { mesh -> mesh.id == pinnedMesh }
			} else {
				original.artMeshes
					.filter { mesh -> original.deformers.getOrNull(mesh.parentDeformerIndex) is WarpDeformer && drawableById[mesh.id]?.mesh != null }
					.maxBy { mesh -> abs(drawableById.getValue(mesh.id).mesh!!.positions[0]) }
			}
		val keyformIndex = System.getProperty("proof.keyform")?.toInt() ?: 0
		val componentIndex = System.getProperty("proof.component")?.toInt() ?: 0
		val drawable = drawableById.getValue(tracedMesh.id)
		val fileValue = tracedMesh.keyforms[keyformIndex].vertexPositions[componentIndex]
		val canvasBase = drawable.mesh!!.positions[componentIndex]
		val localBase = drawable.mesh!!.localPositions[componentIndex]
		// As Any: the float32 build's static type would make a DoubleArray check a compile error.
		val storedDelta: Any? = drawable.geometryGrid?.let { grid -> cellsByLinearIndex(grid)[keyformIndex]?.form?.positionDeltas }
		val storedDeltaValue = storedDelta?.let { deltas -> deltaComponent(deltas, componentIndex) }
		val exportedValue = exportedById.getValue(tracedMesh.id).keyforms[keyformIndex].vertexPositions[componentIndex]
		val floatDelta = fileValue - canvasBase
		val doubleDelta = fileValue.toDouble() - canvasBase.toDouble()
		val floatRebuilt = canvasBase + floatDelta
		val doubleRebuilt = (canvasBase.toDouble() + doubleDelta).toFloat()
		val parent = original.deformers[tracedMesh.parentDeformerIndex]
		val canvas = original.canvas
		val line =
			buildJsonObject {
				put("sample", JsonPrimitive(sampleFile.name))
				put("deltaType", JsonPrimitive(if (storedDelta is DoubleArray) "float64" else "float32"))
				put("canvasWidth", JsonPrimitive(canvas?.width ?: 0f))
				put("canvasHeight", JsonPrimitive(canvas?.height ?: 0f))
				put("pixelsPerUnit", JsonPrimitive(canvas?.pixelsPerUnit ?: 0f))
				put("originX", JsonPrimitive(canvas?.originX ?: 0f))
				put("originY", JsonPrimitive(canvas?.originY ?: 0f))
				put("deformerChildMeshes", JsonPrimitive(original.artMeshes.count { mesh -> mesh.parentDeformerIndex >= 0 }))
				put("keyformsCompared", JsonPrimitive(keyformsCompared))
				put("warpComponents", JsonPrimitive(warpComponents))
				put("rotationComponents", JsonPrimitive(rotationComponents))
				put("mismatchedComponents", JsonPrimitive(mismatched))
				put("largestError", JsonPrimitive(largestError))
				put("largestErrorAt", JsonPrimitive(largestErrorAt))
				put("keyformsWithFlips", JsonPrimitive(keyformsWithFlips))
				put("flippedTriangles", JsonPrimitive(flippedTriangles))
				put("meshesWithFlips", JsonPrimitive(meshesWithFlips))
				put("tracedMesh", JsonPrimitive(tracedMesh.id))
				put("tracedParent", JsonPrimitive(parent.id))
				put("tracedParentKind", JsonPrimitive(if (parent is WarpDeformer) "warp" else "rotation"))
				put("tracedKeyform", JsonPrimitive(keyformIndex))
				put("tracedComponent", JsonPrimitive(componentIndex))
				put("tracedKeyformCount", JsonPrimitive(tracedMesh.keyforms.size))
				put("fileValue", JsonPrimitive(fileValue.toString()))
				put("fileBits", JsonPrimitive(bitsOf(fileValue)))
				put("canvasBase", JsonPrimitive(canvasBase.toString()))
				put("canvasBaseBits", JsonPrimitive(bitsOf(canvasBase)))
				put("storedDelta", JsonPrimitive(storedDeltaValue?.toString() ?: "none"))
				put("storedDeltaBits", JsonPrimitive(storedDeltaValue?.let { delta -> if (storedDelta is DoubleArray) bitsOf(delta) else bitsOf(delta.toFloat()) } ?: "none"))
				put("exportedValue", JsonPrimitive(exportedValue.toString()))
				put("exportedBits", JsonPrimitive(bitsOf(exportedValue)))
				put("floatDelta", JsonPrimitive(floatDelta.toString()))
				put("floatDeltaBits", JsonPrimitive(bitsOf(floatDelta)))
				put("floatRebuilt", JsonPrimitive(floatRebuilt.toString()))
				put("doubleDelta", JsonPrimitive(doubleDelta.toString()))
				put("doubleDeltaBits", JsonPrimitive(bitsOf(doubleDelta)))
				put("doubleRebuilt", JsonPrimitive(doubleRebuilt.toString()))
				put("localBase", JsonPrimitive(localBase.toString()))
				put("localBaseBits", JsonPrimitive(bitsOf(localBase)))
				put("localRebuilt", JsonPrimitive(storedDeltaValue?.let { delta -> (localBase + delta.toFloat()).toString() } ?: "none"))
				put("localUlp", JsonPrimitive(Math.ulp(localBase).toDouble()))
				put("canvasUlp", JsonPrimitive(Math.ulp(canvasBase).toDouble()))
				put("fileUlp", JsonPrimitive(Math.ulp(fileValue).toDouble()))
			}
		println("[proof-json] $line")
	}
}