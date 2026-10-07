package org.umamo.edit.mesh

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.SessionTestModels.drawable
import org.umamo.edit.SessionTestModels.meshModel
import org.umamo.edit.SessionTestModels.model
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the batch mesh folds against the per-drawable ones they replace in the commits and the modal drive:
 * the same model content as folding entry by entry, every untouched drawable shared, the same instance back
 * on every no-op (which is how a session tells a no-op commit from an edit), and only the first drawable of
 * a duplicated id touched, as the per-drawable fold's first-match lookup does.
 */
class MeshArrayEditsTest {
	private val first = DrawableId("first")
	private val second = DrawableId("second")
	private val third = DrawableId("third")

	@Test
	fun theBatchFoldsTheSameModelAsTheSerialFold() {
		val model = model()
		val edits = mapOf(first to MeshRestPositions.shared(triangle(5f)), third to MeshRestPositions.shared(triangle(7f)))

		val batch = model.withMeshPositions(edits)
		val serial = edits.entries.fold(model) { folded, (drawableId, rest) -> folded.withMeshPositions(drawableId, rest) }

		assertEquals(serial.drawables.map { drawable -> drawable.id }, batch.drawables.map { drawable -> drawable.id })
		for (drawableIndex in serial.drawables.indices) {
			val serialMesh = serial.drawables[drawableIndex].mesh
			val batchMesh = batch.drawables[drawableIndex].mesh
			assertSame(serialMesh?.positions, batchMesh?.positions, "drawable $drawableIndex's positions")
			assertSame(serialMesh?.localPositions, batchMesh?.localPositions, "and its base")
			assertSame(serialMesh?.uvs, batchMesh?.uvs, "and its uvs")
		}
		assertSame(model.drawables[1], batch.drawables[1], "an untouched drawable is shared")
	}

	@Test
	fun theUvBatchReplacesUvsAndSharesThePositions() {
		val model = model()
		val newUvs = triangle(0.5f)

		val batch = model.withMeshUvs(mapOf(second to newUvs))

		val mesh = batch.drawables[1].mesh!!
		assertSame(newUvs, mesh.uvs)
		assertSame(model.drawables[1].mesh!!.positions, mesh.positions, "the rest geometry is shared")
		assertSame(model.drawables[1].mesh!!.localPositions, mesh.localPositions, "and so is the base")
		assertSame(model.drawables[0], batch.drawables[0])
	}

	@Test
	fun everyNoOpReturnsTheSameModel() {
		val model = model()
		val held = model.drawables[0].mesh!!

		assertSame(model, model.withMeshPositions(emptyMap()), "no edits")
		assertSame(model, model.withMeshPositions(mapOf(DrawableId("missing") to MeshRestPositions.shared(triangle(1f)))), "an unknown id")
		assertSame(model, model.withMeshPositions(mapOf(first to MeshRestPositions(held.positions, held.localPositions))), "the arrays it holds")
		assertSame(model, model.withMeshPositions(mapOf(first to MeshRestPositions.shared(FloatArray(4)))), "a length mismatch")
		assertSame(model, model.withMeshPositions(mapOf(first to MeshRestPositions(triangle(1f), FloatArray(4)))), "a base of the wrong length")
		assertSame(model, model.withMeshUvs(mapOf(first to held.uvs)), "the uvs it holds")
		val meshless = model.copy(drawables = model.drawables + meshDrawable("bare", null))
		assertSame(meshless, meshless.withMeshPositions(mapOf(DrawableId("bare") to MeshRestPositions.shared(triangle(1f)))), "a drawable with no mesh")
	}

	@Test
	fun aNoOpBesideAnEditIsSkipped() {
		val model = model()
		val moved = triangle(9f)

		val batch = model.withMeshPositions(mapOf(first to MeshRestPositions.shared(FloatArray(4)), second to MeshRestPositions.shared(moved)))

		assertNotSame(model, batch)
		assertSame(model.drawables[0], batch.drawables[0], "the mismatched entry is left alone")
		assertSame(moved, batch.drawables[1].mesh!!.positions)
	}

	@Test
	fun onlyTheFirstDrawableOfAnIdIsTouched() {
		val model = model()
		val duplicated = model.copy(drawables = model.drawables + meshDrawable(first.raw, triangle(0f)))
		val moved = triangle(3f)

		val batch = duplicated.withMeshPositions(mapOf(first to MeshRestPositions.shared(moved)))

		assertSame(moved, batch.drawables[0].mesh!!.positions, "the first one moves")
		assertSame(duplicated.drawables[3], batch.drawables[3], "the second one does not, as with the per-drawable fold")
		assertContentEquals(duplicated.withMeshPositions(first, MeshRestPositions.shared(moved)).drawables[3].mesh!!.positions, batch.drawables[3].mesh!!.positions)
	}

	/**
	 * Three meshed drawables, each a triangle.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(meshDrawable(first.raw, triangle(0f)), meshDrawable(second.raw, triangle(1f)), meshDrawable(third.raw, triangle(2f))),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/**
	 * One drawable over [positions], with its own uvs, or none when [positions] is null.
	 *
	 * @param String id The drawable id.
	 * @param FloatArray? positions The rest positions, or null for no mesh.
	 * @return Drawable The drawable.
	 */
	private fun meshDrawable(id: String, positions: FloatArray?): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = positions?.let { meshPositions -> DrawableMesh.withLocalEqualToCanvas(meshPositions, triangle(0.25f), intArrayOf(0, 1, 2)) },
			geometryGrid = null,
		)

	/**
	 * A triangle's interleaved coordinates, offset by [offset], a new array each call.
	 *
	 * @param Float offset The offset added to every coordinate.
	 * @return FloatArray The coordinates.
	 */
	private fun triangle(offset: Float): FloatArray = floatArrayOf(offset, offset, offset + 1f, offset, offset, offset + 1f)

	/** withMeshPositions copy-on-writes the mesh: new positions, shared uvs/indices, input array untouched. */
	@Test
	fun withMeshPositionsCowsAndSharesUnchangedArrays() {
		val before = meshModel()
		val beforeMesh = before.drawables.first().mesh!!
		val newPositions = floatArrayOf(5f, 5f, 2f, 0f, 0f, 2f)

		val after = before.withMeshPositions(DrawableId("d"), MeshRestPositions.shared(newPositions))
		val afterMesh = after.drawables.first().mesh!!

		assertSame(beforeMesh.uvs, afterMesh.uvs, "uvs shared by reference")
		assertSame(beforeMesh.indices, afterMesh.indices, "indices shared by reference")
		assertEquals(5f, afterMesh.positions[0], "new positions applied")
		assertEquals(0f, beforeMesh.positions[0], "the prior mesh's array is unmutated (COW)")
		assertSame(before, before.withMeshPositions(DrawableId("d"), MeshRestPositions(beforeMesh.positions, beforeMesh.localPositions)), "same array is a no-op")
		assertSame(before, before.withMeshPositions(DrawableId("missing"), MeshRestPositions.shared(newPositions)), "missing id is a no-op")
	}

	/**
	 * withMeshPositions sets the canvas mesh and the keyform-space base apart: an array the rest shape
	 * passes back unchanged keeps its instance, so a canvas-only edit leaves the base (and with it every
	 * rebuilt keyform) alone, and a size mismatch on either array is a no-op.
	 */
	@Test
	fun withMeshPositionsSetsTheCanvasMeshAndTheBaseApart() {
		val canvas = floatArrayOf(100f, 200f, 120f, 200f, 100f, 220f)
		val local = floatArrayOf(0.25f, 0.5f, 0.75f, 0.5f, 0.25f, 0.75f)
		val mesh = DrawableMesh(positions = canvas, localPositions = local, uvs = FloatArray(6), indices = intArrayOf(0, 1, 2))
		val before = model().copy(drawables = listOf(drawable.copy(mesh = mesh)))
		val movedCanvas = floatArrayOf(110f, 200f, 130f, 200f, 110f, 220f)

		val canvasOnly = before.withMeshPositions(DrawableId("d"), MeshRestPositions(movedCanvas, local)).drawables.first().mesh!!
		assertSame(movedCanvas, canvasOnly.positions, "the canvas mesh takes the new array")
		assertSame(local, canvasOnly.localPositions, "the base keeps its instance")

		val movedLocal = floatArrayOf(0.3f, 0.5f, 0.8f, 0.5f, 0.3f, 0.75f)
		val both = before.withMeshPositions(DrawableId("d"), MeshRestPositions(movedCanvas, movedLocal)).drawables.first().mesh!!
		assertSame(movedCanvas, both.positions)
		assertSame(movedLocal, both.localPositions)

		assertSame(before, before.withMeshPositions(DrawableId("d"), MeshRestPositions(movedCanvas, FloatArray(4))), "a short base is a no-op")
		assertSame(before, before.withMeshPositions(DrawableId("d"), MeshRestPositions(FloatArray(8), local)), "a long canvas mesh is a no-op")
	}

	/** A mesh edit is one undo step that marks dirty; undo restores the original array instance and clears dirty. */
	@Test
	fun commitMeshPositionsIsOneUndoStepAndRestores() {
		val initial = meshModel()
		val session = EditorSession(initial)
		val originalArray = initial.drawables.first().mesh!!.positions
		val moved = floatArrayOf(9f, 9f, 2f, 0f, 0f, 2f)

		session.commitMeshPositions(MeshChange.TransformVertices(mapOf(DrawableId("d") to listOf(0)), MeshOperatorKind.Grab), mapOf(DrawableId("d") to MeshRestPositions.shared(moved)))
		assertTrue(session.dirty.value, "a mesh edit dirties the document")
		assertTrue(session.canUndo.value)
		assertEquals(9f, session.model.value.drawables.first().mesh!!.positions[0])

		session.undo()
		assertSame(originalArray, session.model.value.drawables.first().mesh!!.positions, "undo restores the array instance")
		assertFalse(session.dirty.value)

		session.redo()
		assertEquals(9f, session.model.value.drawables.first().mesh!!.positions[0])
	}

	/**
	 * Entering Edit with several meshed drawables selected seeds them ALL into the session (multi-mesh
	 * edit), with the object selection's active drawable as the active mesh; a commit moving vertices
	 * on two meshes is ONE undo step that restores both.
	 */
	@Test
	fun multiMeshEditSessionSeedsAndCommitsAcrossMeshes() {
		val model =
			meshModel().let { base ->
				base.copy(drawables = base.drawables + base.drawables.first().copy(id = DrawableId("d2"), name = "d2"))
			}
		val session = EditorSession(model)
		val targetD = SelectionTarget.Drawable(DrawableId("d"))
		val targetD2 = SelectionTarget.Drawable(DrawableId("d2"))
		session.setSelection(Selection(setOf(targetD, targetD2), targetD2))

		session.setMode(EditorMode.Edit)
		assertEquals(listOf(DrawableId("d"), DrawableId("d2")), session.meshSelection.value.drawableIds, "both meshes join the session")
		assertEquals(DrawableId("d2"), session.meshSelection.value.activeDrawableId, "the object selection's active drawable is the active mesh")

		// One commit moving vertices on both meshes is a single undo step restoring both.
		val stepsBefore = session.historyView.value.steps.size
		session.commitMeshPositions(
			MeshChange.TransformVertices(mapOf(DrawableId("d") to listOf(0), DrawableId("d2") to listOf(1)), MeshOperatorKind.Grab),
			mapOf(
				DrawableId("d") to MeshRestPositions.shared(floatArrayOf(9f, 9f, 2f, 0f, 0f, 2f)),
				DrawableId("d2") to MeshRestPositions.shared(floatArrayOf(0f, 0f, 7f, 7f, 0f, 2f)),
			),
		)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "two meshes, one undo step")
		assertEquals(9f, session.model.value.drawables.first { it.id == DrawableId("d") }.mesh!!.positions[0])
		assertEquals(7f, session.model.value.drawables.first { it.id == DrawableId("d2") }.mesh!!.positions[2])

		session.undo()
		assertEquals(0f, session.model.value.drawables.first { it.id == DrawableId("d") }.mesh!!.positions[0])
		assertEquals(2f, session.model.value.drawables.first { it.id == DrawableId("d2") }.mesh!!.positions[2])
	}
}