package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the merge's face on the operation settings strip: a merge registers with its Merge At row,
 * switching the row re-merges the SAME vertices from the base at the new target over the same step
 * (the survivor keeps its index, so the selection the step carries stays valid), and an unknown
 * choice falls back to the target the merge ran with.
 */
class MergeAdjustTest {
	private val drawableId = DrawableId("a")

	/** A quad: v0 (0,0), v1 (10,0), v2 (0,10), v3 (10,10), two triangles. */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "a",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f), FloatArray(8), intArrayOf(0, 1, 2, 1, 3, 2)),
						geometryGrid = null,
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** A session in Edit mode with v0 and v1 selected, v1 active (so First is v0 and Last is v1). */
	private fun session(): EditorSession {
		val session = EditorSession(model())
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(
			MeshSelection(
				drawableIds = listOf(drawableId),
				activeDrawableId = drawableId,
				selectMode = MeshSelectMode.Vertex,
				elementsByDrawable = mapOf(drawableId to setOf(MeshElement.Vertex(0), MeshElement.Vertex(1))),
				activeElement = ActiveMeshElement(drawableId, MeshElement.Vertex(1)),
			),
		)
		return session
	}

	private fun survivor(session: EditorSession): Pair<Float, Float> {
		val positions = session.model.value.drawables.single().mesh!!.positions
		// The survivor appends last: the two kept vertices come first.
		return positions[4] to positions[5]
	}

	private fun targetRow(key: String): OperatorParameter =
		OperatorParameter.ChoiceParameter(MergeParameterKeys.TARGET, MergeParameterKeys.TARGET, key, emptyList())

	@Test
	fun aMergeRegistersAndItsTargetCanBeSwitchedInPlace() {
		val session = session()

		session.mergeSelectedVertices(MergeTarget.AtCenter, areaId = "area-1")

		val record = assertNotNull(session.adjustableOperation.value, "the merge registered")
		assertEquals("area-1", record.areaId)
		assertEquals("change.mesh.merge", record.change.labelKey)
		assertEquals("center", record.parameters.choiceValue(MergeParameterKeys.TARGET, "?"))
		assertEquals(5f to 0f, survivor(session), "landed at the center of v0 and v1")
		assertEquals(3, session.model.value.drawables.single().mesh!!.vertexCount)
		val stepsBefore = session.historyView.value.steps.size
		val selectionBefore = session.meshSelection.value

		session.adjustLastOperation(record.parameters.withParameter(MergeParameterKeys.TARGET, targetRow("first")))
		assertEquals(0f to 0f, survivor(session), "re-merged from the base at the first vertex")
		assertEquals(stepsBefore, session.historyView.value.steps.size, "the step was amended in place")
		assertEquals(selectionBefore, session.meshSelection.value, "the survivor selection still names the same index")

		session.adjustLastOperation(record.parameters.withParameter(MergeParameterKeys.TARGET, targetRow("last")))
		assertEquals(10f to 0f, survivor(session), "and at the last (active) vertex")
		assertNotNull(session.adjustableOperation.value, "the record stays live for the next adjustment")

		session.undo()
		assertEquals(4, session.model.value.drawables.single().mesh!!.vertexCount, "one undo returns to the unmerged quad")
		assertNull(session.adjustableOperation.value)
	}

	@Test
	fun anUnknownChoiceFallsBackToTheTargetTheMergeRanWith() {
		assertEquals(MergeTarget.AtLast, mergeTargetOf(listOf(targetRow("elsewhere")), MergeTarget.AtLast))
		assertEquals(MergeTarget.AtFirst, mergeTargetOf(emptyList(), MergeTarget.AtFirst))
		assertEquals(MergeTarget.AtCenter, mergeTargetOf(mergeParameters(MergeTarget.AtCenter), MergeTarget.AtLast))
		assertEquals(listOf("center", "first", "last"), mergeParameters(MergeTarget.AtCenter).filterIsInstance<OperatorParameter.ChoiceParameter>().single().choices.map { choice -> choice.key })
	}

	@Test
	fun aRefusedMergeRegistersNothing() {
		val session = EditorSession(model())
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelection(drawableIds = listOf(drawableId), activeDrawableId = drawableId, elementsByDrawable = mapOf(drawableId to setOf(MeshElement.Vertex(0)))))

		session.mergeSelectedVertices(MergeTarget.AtCenter, areaId = "area-1")

		assertNull(session.adjustableOperation.value, "one vertex merges nothing, so nothing is adjustable")
	}
}