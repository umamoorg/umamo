package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the UV write path (Slice A of the UV editor): the withMeshUvs copy-on-write op and its
 * no-op guards, the commitMeshUvs undo integration, the activeUvOperator latch semantics (area
 * ownership, mutual exclusion, the Vertex Slide and mode refusals, the axis-constraint hookup), the
 * UV cursor, and the Mirror U / V command math.  The load-bearing invariant throughout is the mesh/UV
 * decoupling seen from the UV side: a UV edit shares the positions and indices arrays by reference
 * and never disturbs rest geometry.
 */
class UvEditsTest {
	private fun meshedDrawable(id: String, uvs: FloatArray): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), uvs, intArrayOf(0, 1, 2)),
			keyforms = null,
		)

	private fun uvModel(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					meshedDrawable("a", floatArrayOf(0.2f, 0.1f, 0.4f, 0.1f, 0.6f, 0.4f)),
					meshedDrawable("b", floatArrayOf(0.1f, 0.8f, 0.3f, 0.8f, 0.1f, 0.9f)),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** Asserts two interleaved uv arrays match element-wise within float tolerance. */
	private fun assertUvsEqual(expected: List<Float>, actual: FloatArray, message: String) {
		assertEquals(expected.size, actual.size, message)
		for (componentIndex in expected.indices) {
			assertEquals(expected[componentIndex], actual[componentIndex], 1e-5f, "$message (component $componentIndex)")
		}
	}

	/** Enters Edit mode with every vertex of drawable "a" selected (and optionally "b" too). */
	private fun editSession(selectBothDrawables: Boolean = false): EditorSession {
		val session = EditorSession(uvModel())
		session.setMode(EditorMode.Edit)
		var selection = session.meshSelection.value
		for (vertexIndex in 0..2) {
			selection = MeshSelectionOps.add(selection, DrawableId("a"), MeshElement.Vertex(vertexIndex))
		}
		if (selectBothDrawables) {
			for (vertexIndex in 0..2) {
				selection = MeshSelectionOps.add(selection, DrawableId("b"), MeshElement.Vertex(vertexIndex))
			}
		}
		session.setMeshSelection(selection)
		return session
	}

	/** withMeshUvs rebuilds only the touched mesh leaf, sharing positions / indices and every sibling. */
	@Test
	fun withMeshUvsSharesEverythingButTheUvArray() {
		val model = uvModel()
		val originalMesh = model.drawables[0].mesh!!
		val untouchedDrawable = model.drawables[1]

		val newUvs = floatArrayOf(0.5f, 0.5f, 0.6f, 0.5f, 0.5f, 0.6f)
		val updated = model.withMeshUvs(DrawableId("a"), newUvs)

		assertNotSame(model, updated, "a real edit produces a new model instance")
		val updatedMesh = updated.drawables[0].mesh!!
		assertSame(newUvs, updatedMesh.uvs, "the new uv array is adopted as-is")
		assertSame(originalMesh.positions, updatedMesh.positions, "positions are shared by reference (the decoupling invariant)")
		assertSame(originalMesh.indices, updatedMesh.indices, "indices are shared by reference")
		assertSame(untouchedDrawable, updated.drawables[1], "the other drawable is shared by reference")
		assertSame(originalMesh, model.drawables[0].mesh, "the input model is never mutated")
	}

	/** Every withMeshUvs no-op guard returns the same model instance so the session records nothing. */
	@Test
	fun withMeshUvsNoOpGuards() {
		val model = uvModel()
		val mesh = model.drawables[0].mesh!!

		assertSame(model, model.withMeshUvs(DrawableId("missing"), FloatArray(6)), "an unknown drawable is a no-op")
		assertSame(model, model.withMeshUvs(DrawableId("a"), mesh.uvs), "the live array instance is a no-op")
		assertSame(model, model.withMeshUvs(DrawableId("a"), FloatArray(4)), "a length mismatch is a no-op")

		val meshless =
			model.copy(drawables = listOf(model.drawables[0].copy(mesh = null)))
		assertSame(meshless, meshless.withMeshUvs(DrawableId("a"), FloatArray(6)), "a mesh-less drawable is a no-op")
	}

	/** commitMeshUvs folds several drawables into ONE undo step; undo restores the exact prior arrays. */
	@Test
	fun commitMeshUvsIsOneUndoStepAndUndoRestoresTheArrayReferences() {
		val session = editSession(selectBothDrawables = true)
		val originalUvsA = session.model.value.drawables[0].mesh!!.uvs
		val originalUvsB = session.model.value.drawables[1].mesh!!.uvs
		val stepsBefore = session.historyView.value.steps.size
		assertFalse(session.dirty.value, "selection setup never dirties the document")

		session.commitMeshUvs(
			MeshChange.TransformUvs(mapOf(DrawableId("a") to listOf(0, 1, 2), DrawableId("b") to listOf(0, 1, 2)), MeshOperatorKind.Grab),
			mapOf(
				DrawableId("a") to floatArrayOf(0.3f, 0.2f, 0.5f, 0.2f, 0.7f, 0.5f),
				DrawableId("b") to floatArrayOf(0.2f, 0.7f, 0.4f, 0.7f, 0.2f, 0.8f),
			),
		)

		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "both drawables commit as one step")
		assertEquals("change.uv.move", session.historyView.value.steps.last().labelKey)
		assertTrue(session.dirty.value, "a UV edit is document content")
		assertNotSame(originalUvsA, session.model.value.drawables[0].mesh!!.uvs)

		session.undo()
		assertSame(originalUvsA, session.model.value.drawables[0].mesh!!.uvs, "undo restores the exact prior uv array")
		assertSame(originalUvsB, session.model.value.drawables[1].mesh!!.uvs)
		assertFalse(session.dirty.value, "undo returns to the saved baseline")
	}

	/** A commit whose every entry no-ops (mismatch / same instance / empty) records nothing. */
	@Test
	fun commitMeshUvsNoOpRecordsNothing() {
		val session = editSession()
		val modelBefore = session.model.value
		val stepsBefore = session.historyView.value.steps.size

		session.commitMeshUvs(MeshChange.TransformUvs(emptyMap(), MeshOperatorKind.Grab), emptyMap())
		session.commitMeshUvs(MeshChange.TransformUvs(mapOf(DrawableId("a") to emptyList()), MeshOperatorKind.Grab), mapOf(DrawableId("a") to FloatArray(4)))
		session.commitMeshUvs(
			MeshChange.TransformUvs(mapOf(DrawableId("a") to emptyList()), MeshOperatorKind.Grab),
			mapOf(DrawableId("a") to modelBefore.drawables[0].mesh!!.uvs),
		)

		assertSame(modelBefore, session.model.value, "no-op commits leave the model instance untouched")
		assertEquals(stepsBefore, session.historyView.value.steps.size, "no-op commits record no history step")
		assertFalse(session.dirty.value)
	}

	/** The UV latch records the initiating area, refuses Vertex Slide, and requires Edit mode. */
	@Test
	fun uvOperatorLatchSemantics() {
		val session = editSession()

		session.beginUvOperator(MeshOperatorKind.Grab, "area-uv")
		assertEquals(ActiveOperator(MeshOperatorKind.Grab, "area-uv"), session.activeUvOperator.value)
		session.beginUvOperator(MeshOperatorKind.Scale, "area-other")
		assertEquals(
			ActiveOperator(MeshOperatorKind.Scale, "area-other"),
			session.activeUvOperator.value,
			"re-latching replaces both the kind and the owner",
		)
		session.clearUvOperator()
		assertNull(session.activeUvOperator.value)

		session.beginUvOperator(MeshOperatorKind.VertexSlide, "area-uv")
		assertNull(session.activeUvOperator.value, "Vertex Slide is rest-geometry math and is refused")

		session.setMode(EditorMode.Object)
		session.beginUvOperator(MeshOperatorKind.Grab, "area-uv")
		assertNull(session.activeUvOperator.value, "Object mode refuses the UV latch")
	}

	/** The three operator latches are mutually exclusive, and a restore (undo) clears the UV latch. */
	@Test
	fun uvOperatorMutualExclusionAndRestoreTeardown() {
		val session = editSession()

		session.beginMeshOperator(MeshOperatorKind.Grab, "area-a")
		session.beginUvOperator(MeshOperatorKind.Grab, "area-uv")
		assertNull(session.activeMeshOperator.value, "latching UV drops the mesh operator")
		assertEquals("area-uv", session.activeUvOperator.value?.areaId)

		session.beginMeshOperator(MeshOperatorKind.Grab, "area-a")
		assertNull(session.activeUvOperator.value, "latching mesh drops the UV operator")
		session.clearMeshOperator()

		session.beginBoxSelect("area-a")
		session.beginUvOperator(MeshOperatorKind.Grab, "area-uv")
		assertNull(session.activeSelectTool.value, "latching UV drops the armed select tool")

		session.undo()
		assertNull(session.activeUvOperator.value, "restore clears the latched UV operator")
	}

	/** The X / Z axis constraint toggles during a UV Grab / Scale, never for Rotate, and clears with the latch. */
	@Test
	fun uvOperatorAxisConstraint() {
		val session = editSession()

		session.beginUvOperator(MeshOperatorKind.Grab, "area-uv")
		session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
		assertEquals(TransformAxisConstraint.AxisX, session.axisConstraint.value)
		session.clearUvOperator()
		assertNull(session.axisConstraint.value, "clearing the UV operator clears the lock")

		session.beginUvOperator(MeshOperatorKind.Rotate, "area-uv")
		session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
		assertNull(session.axisConstraint.value, "Rotate refuses the lock")
	}

	/** A selection whose meshes carry no editable UV array refuses the latch with an explanatory notice. */
	@Test
	fun uvOperatorRefusesWithoutEditableUvs() {
		val model =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = listOf(meshedDrawable("a", FloatArray(0))),
				rootChildren = emptyList(),
				rootPartId = null,
			)
		val session = EditorSession(model)
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, DrawableId("a"), MeshElement.Vertex(0)))

		session.beginUvOperator(MeshOperatorKind.Grab, "area-uv")
		assertNull(session.activeUvOperator.value, "an empty uv array cannot be edited")
		assertEquals("notice.uv.noUvs", session.notice.value?.messageKey, "the refusal explains itself")

		session.mirrorSelectedUvs(mirrorU = true)
		assertFalse(session.dirty.value, "the mirror refuses for the same reason")
	}

	/** The UV cursor latch holds its placement and survives undo (transient by design, like cursor2d). */
	@Test
	fun uvCursorPlacement() {
		val session = editSession()
		assertNull(session.uvCursor.value, "no cursor before placement")
		session.setUvCursor(0.25f, 0.75f)
		assertEquals(UvCursor(0.25f, 0.75f), session.uvCursor.value)
		session.undo()
		assertEquals(UvCursor(0.25f, 0.75f), session.uvCursor.value, "the UV cursor survives undo")
	}

	/** Mirror U flips u about the covered median leaving v (and rest geometry) untouched; Mirror V is the converse. */
	@Test
	fun mirrorSelectedUvsAboutTheMedian() {
		val session = editSession()
		val originalPositions = session.model.value.drawables[0].mesh!!.positions

		// Covered u values are 0.2 / 0.4 / 0.6, so the median pivot's u is 0.4.
		session.mirrorSelectedUvs(mirrorU = true)
		val mirroredU = session.model.value.drawables[0].mesh!!.uvs
		assertUvsEqual(listOf(0.6f, 0.1f, 0.4f, 0.1f, 0.2f, 0.4f), mirroredU, "u reflects about 0.4, v is untouched")
		assertSame(originalPositions, session.model.value.drawables[0].mesh!!.positions, "rest geometry is untouched by reference")
		assertEquals("change.uv.mirror", session.historyView.value.steps.last().labelKey)

		// Covered v values are now 0.1 / 0.1 / 0.4, so the median pivot's v is 0.2.
		session.mirrorSelectedUvs(mirrorU = false)
		val mirroredV = session.model.value.drawables[0].mesh!!.uvs
		assertEquals(0.3f, mirroredV[1], 1e-5f, "v reflects about the median")
		assertEquals(0.6f, mirroredV[0], 1e-5f, "u is untouched by a V mirror")

		session.undo()
		session.undo()
		assertEquals(listOf(0.2f, 0.1f, 0.4f, 0.1f, 0.6f, 0.4f), session.model.value.drawables[0].mesh!!.uvs.toList(), "two undos restore the original mapping")
	}

	/** The Cursor pivot mode mirrors about the UV cursor, falling back to the median before any placement. */
	@Test
	fun mirrorSelectedUvsAboutTheCursor() {
		val session = editSession()
		session.setPivotMode(TransformPivotMode.Cursor)

		// No cursor placed yet: the median fallback keeps the command effective.
		session.mirrorSelectedUvs(mirrorU = true)
		assertEquals(0.6f, session.model.value.drawables[0].mesh!!.uvs[0], 1e-5f, "an unplaced cursor falls back to the median")
		session.undo()

		session.setUvCursor(0.5f, 0.5f)
		session.mirrorSelectedUvs(mirrorU = true)
		val mirrored = session.model.value.drawables[0].mesh!!.uvs
		assertUvsEqual(listOf(0.8f, 0.1f, 0.6f, 0.1f, 0.4f, 0.4f), mirrored, "u reflects about the cursor's 0.5")
	}
}
