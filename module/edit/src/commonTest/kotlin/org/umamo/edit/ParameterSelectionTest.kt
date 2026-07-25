package org.umamo.edit

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the keyform-authoring target: which parameter an insert would write a key on.
 *
 * It lives on the session rather than a panel's view state because it is SHARED - a keyform sheet in one
 * area has to follow the parameter picked in a parameters panel in another - and it rides the undo stack
 * like the object and mesh selections, so undoing back across a keyform edit restores the target that edit
 * was made against.
 */
class ParameterSelectionTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")

	private fun parameter(id: ParameterId): Parameter = Parameter(id, id.raw, min = -1f, max = 1f, default = 0f)

	private fun session(ids: List<ParameterId>): EditorSession =
		EditorSession(
			PuppetModel(
				parameters = ids.map { id -> parameter(id) },
				parts = emptyList(),
				deformers = emptyList(),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
			),
		)

	/** Nothing is targeted until the user picks something - a fresh document keys nowhere. */
	@Test
	fun startsEmpty() {
		assertTrue(session(listOf(angleX)).parameterSelection.value.isEmpty)
		assertNull(session(listOf(angleX)).parameterSelection.value.active)
	}

	/** Picking a parameter targets it, and the pick is its own undo step. */
	@Test
	fun selectingIsItsOwnUndoStep() {
		val editorSession = session(listOf(angleX, angleY))
		editorSession.setParameterSelection(ParameterSelection.of(angleX))
		assertEquals(angleX, editorSession.parameterSelection.value.active)

		editorSession.setParameterSelection(ParameterSelection.of(angleY))
		assertEquals(angleY, editorSession.parameterSelection.value.active)

		editorSession.undo()
		assertEquals(angleX, editorSession.parameterSelection.value.active, "undo restores the previous target")
		editorSession.redo()
		assertEquals(angleY, editorSession.parameterSelection.value.active)
	}

	/** Re-picking the same target records nothing, so repeated clicks do not pile up undo steps. */
	@Test
	fun reselectingTheSameTargetIsANoOp() {
		val editorSession = session(listOf(angleX))
		editorSession.setParameterSelection(ParameterSelection.of(angleX))
		val stepsAfterFirst = editorSession.historyView.value.steps.size
		editorSession.setParameterSelection(ParameterSelection.of(angleX))
		assertEquals(stepsAfterFirst, editorSession.historyView.value.steps.size)
	}

	/** Toggling extends and contracts the set, keeping the active member inside it. */
	@Test
	fun togglingExtendsAndContracts() {
		val both = ParameterSelection.of(angleX).toggled(angleY)
		assertEquals(setOf(angleX, angleY), both.ids)
		assertEquals(angleY, both.active, "the newly added member becomes active")

		val backToOne = both.toggled(angleY)
		assertEquals(setOf(angleX), backToOne.ids)
		assertEquals(angleX, backToOne.active, "the active target stays inside the set")
	}

	/**
	 * Deleting a parameter drops it from the target, so an insert can never aim at an id the model no
	 * longer has.  This is the one way the two could silently diverge - the target is session state, the
	 * parameter list is document state, and only the delete path touches both.
	 */
	@Test
	fun deletingAParameterPrunesTheTarget() {
		val editorSession = session(listOf(angleX, angleY))
		editorSession.setParameterSelection(ParameterSelection(setOf(angleX, angleY), angleY))

		editorSession.deleteParameter(angleY)

		val target = editorSession.parameterSelection.value
		assertFalse(angleY in target, "the deleted parameter is no longer targeted")
		assertEquals(setOf(angleX), target.ids)
		assertEquals(angleX, target.active, "the active target falls back to a surviving member")
	}
}
