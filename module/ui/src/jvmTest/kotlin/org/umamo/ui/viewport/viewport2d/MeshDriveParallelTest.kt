package org.umamo.ui.viewport.viewport2d

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TransformGestureParameters
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the 2D drive's parallel compute against its sequential one on the real default dispatcher: with
 * every mesh its own chunk, the threads finish in any order, and the result must still be the sequential
 * one element for element, in capture order.
 */
class MeshDriveParallelTest {
	/**
	 * A model of [meshCount] grid meshes of varying sizes, side by side and none under a deformer.
	 *
	 * @param Int meshCount How many meshes.
	 * @return PuppetModel The model.
	 */
	private fun manyMeshModel(meshCount: Int): PuppetModel {
		val drawables =
			List(meshCount) { meshIndex ->
				val side = meshIndex % 5 + 2
				val positions = FloatArray(side * side * 2)
				for (rowIndex in 0 until side) {
					for (columnIndex in 0 until side) {
						val vertexIndex = rowIndex * side + columnIndex
						positions[vertexIndex * 2] = meshIndex * 30f + columnIndex * 4f
						positions[vertexIndex * 2 + 1] = rowIndex * 4f
					}
				}
				val indices = ArrayList<Int>()
				for (rowIndex in 0 until side - 1) {
					for (columnIndex in 0 until side - 1) {
						val corner = rowIndex * side + columnIndex
						indices.addAll(listOf(corner, corner + 1, corner + side + 1, corner, corner + side + 1, corner + side))
					}
				}
				Drawable(
					id = DrawableId("mesh$meshIndex"),
					name = "mesh$meshIndex",
					parentDeformerId = null,
					blendMode = BlendMode.Normal,
					maskedBy = emptyList(),
					mesh = DrawableMesh.withLocalEqualToCanvas(positions, FloatArray(positions.size), indices.toIntArray()),
					geometryGrid = null,
				)
			}
		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
		)
	}

	/** Every operator's parallel drive matches the sequential one, mesh for mesh and in capture order. */
	@Test
	fun theParallelDriveMatchesTheSequentialOne() {
		val session = EditorSession(manyMeshModel(48))
		val targets = session.model.value.drawables.map { drawable -> SelectionTarget.Drawable(drawable.id) }
		session.setSelection(Selection(targets.toSet(), targets.first()))
		val transform = ObjectModalTransform(AREA_ID, session) { }
		session.beginObjectOperator(MeshOperatorKind.Rotate, AREA_ID)
		transform.begin(MeshOperatorKind.Rotate)
		val gestureData = assertNotNull(transform.gesture.capture)
		val jobs = meshDriveJobs(gestureData.transform, gestureData.geometryById, wholeMeshes = true)
		assertEquals(48, jobs.size, "every mesh moves")
		val parameters = TransformGestureParameters(3.5f, -2.25f, 1.3f, 0.7f, 0.9f)
		for (operator in listOf(MeshOperatorKind.Grab, MeshOperatorKind.Scale, MeshOperatorKind.Rotate)) {
			val request = MeshDriveRequest(operator, parameters, jobs, null, session.model.value)
			val sequential = computeMeshDrive(request)
			val parallel = runBlocking { withContext(Dispatchers.Default) { computeMeshDriveParallel(request, minChunkWeight = 1) } }
			assertEquals(sequential.preview.keys.toList(), parallel.preview.keys.toList(), "$operator: capture order")
			for ((drawableId, rest) in sequential.preview) {
				assertContentEquals(rest.positions, parallel.preview.getValue(drawableId).positions, "$operator: $drawableId")
				assertContentEquals(rest.localPositions, parallel.preview.getValue(drawableId).localPositions, "$operator: $drawableId base")
			}
			for ((drawableIndex, drawable) in sequential.folded.drawables.withIndex()) {
				val parallelDrawable = parallel.folded.drawables[drawableIndex]
				assertEquals(drawable.id, parallelDrawable.id)
				assertContentEquals(drawable.mesh?.positions, parallelDrawable.mesh?.positions, "$operator: folded $drawableIndex")
				assertContentEquals(drawable.mesh?.localPositions, parallelDrawable.mesh?.localPositions, "$operator: folded $drawableIndex base")
			}
		}
	}

	private companion object {
		/** The area the gesture runs in. */
		const val AREA_ID = "left"
	}
}