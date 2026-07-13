package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the Object-mode session surface: the select-tool guards relaxed for Object mode, the object
 * operator's eligibility guard and single-step commit, and the transient-tool clears that keep an armed
 * object tool from leaking across a mode switch or an undo.
 */
class ObjectOperatorSessionTest {
	private fun meshDrawable(id: String, positions: FloatArray): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2)),
			keyforms = null,
		)

	private val warp = Deformer.Warp(DeformerId("w"), "W", parent = null, partId = null, rows = 2, columns = 2, isQuadTransform = true, keyforms = null)

	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = listOf(warp),
			drawables = listOf(meshDrawable("a", floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)), meshDrawable("b", floatArrayOf(2f, 2f, 3f, 2f, 2f, 3f))),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	private fun session(): EditorSession = EditorSession(model())

	private val drawableA = SelectionTarget.Drawable(DrawableId("a"))

	/** Box / Circle select arm in Object mode with no drawable required (the box builds a whole-object selection). */
	@Test
	fun selectToolsArmInObjectModeWithoutDrawable() {
		val session = session()
		assertEquals(EditorMode.Object, session.mode.value)

		session.beginBoxSelect("area-test")
		assertTrue(session.activeSelectTool.value is ActiveSelectTool.BoxArmed, "box arms in Object mode")

		session.beginCircleSelect("area-test")
		assertTrue(session.activeSelectTool.value is ActiveSelectTool.Circle, "circle arms in Object mode")
	}

	/** An object operator latches on an eligible drawable selection. */
	@Test
	fun objectOperatorArmsOnEligibleSelection() {
		val session = session()
		session.setSelection(Selection(setOf(drawableA), drawableA))

		session.beginObjectOperator(MeshOperatorKind.Grab, "area-test")
		assertEquals(MeshOperatorKind.Grab, session.activeObjectOperator.value?.kind)
	}

	/** An object operator blocks (stays null) when the selection holds nothing transformable at all. */
	@Test
	fun objectOperatorBlocksOnIneligibleSelection() {
		val session = session()

		// Empty selection.
		session.beginObjectOperator(MeshOperatorKind.Grab, "area-test")
		assertNull(session.activeObjectOperator.value, "empty selection blocks")
		assertEquals("notice.transform.onlyDrawables", session.notice.value?.messageKey, "the block explains itself")
		assertEquals(NoticePlacement.NearCursor, session.notice.value?.placement, "the note shows near the pointer")

		// A selection of only a deformer blocks (nothing transformable).
		session.setSelection(Selection(setOf(SelectionTarget.Deformer(DeformerId("w"))), null))
		session.beginObjectOperator(MeshOperatorKind.Grab, "area-test")
		assertNull(session.activeObjectOperator.value, "an only-deformer selection blocks")
	}

	/** A deformer beside a drawable is silently skipped: the operator still arms for the drawable. */
	@Test
	fun objectOperatorSkipsIneligibleTargetsBesideDrawables() {
		val session = session()
		session.setSelection(Selection(setOf(drawableA, SelectionTarget.Deformer(DeformerId("w"))), drawableA))

		session.beginObjectOperator(MeshOperatorKind.Grab, "area-test")
		assertEquals(MeshOperatorKind.Grab, session.activeObjectOperator.value?.kind, "the drawable beside the deformer still transforms")
	}

	/** An object operator refuses to start while any parameter is scrubbed away from its default. */
	@Test
	fun objectOperatorBlocksWhilePoseIsDeformed() {
		val angle = Parameter(ParameterId("angle"), "Angle", min = -1f, max = 1f, default = 0f)
		val session = EditorSession(model().copy(parameters = listOf(angle)))
		session.setSelection(Selection(setOf(drawableA), drawableA))

		// Scrub the parameter away from its default, then try to transform.
		session.commitPose(ParameterChange.SetValue(listOf(angle.id)), mapOf(angle.id to 0.5f))
		session.beginObjectOperator(MeshOperatorKind.Grab, "area-test")
		assertNull(session.activeObjectOperator.value, "a deformed pose blocks the transform")
		assertEquals("notice.transform.deformed", session.notice.value?.messageKey, "the guard explains itself")
		assertEquals(NoticePlacement.NearCursor, session.notice.value?.placement, "the note shows near the pointer")

		// Back at defaults the same gesture arms.
		session.commitPose(ParameterChange.SetValue(listOf(angle.id)), mapOf(angle.id to 0f))
		session.beginObjectOperator(MeshOperatorKind.Grab, "area-test")
		assertEquals(MeshOperatorKind.Grab, session.activeObjectOperator.value?.kind, "a neutral pose arms")
	}

	/** An object operator only arms in Object mode. */
	@Test
	fun objectOperatorRequiresObjectMode() {
		val session = session()
		session.setSelection(Selection(setOf(drawableA), drawableA))
		session.setMode(EditorMode.Edit)

		session.beginObjectOperator(MeshOperatorKind.Grab, "area-test")
		assertNull(session.activeObjectOperator.value, "no object operator in Edit mode")
	}

	/** commitObjectPositions writes the new base positions as exactly one undo step. */
	@Test
	fun commitObjectPositionsIsOneUndoStep() {
		val session = session()
		assertFalse(session.canUndo.value)

		val moved = floatArrayOf(10f, 10f, 11f, 10f, 10f, 11f)
		session.commitObjectPositions(MeshChange.MoveDrawables(listOf(DrawableId("a"))), mapOf(DrawableId("a") to moved))

		assertTrue(session.canUndo.value, "the commit is undoable")
		assertTrue(session.dirty.value, "a rest-geometry edit dirties the document")
		assertEquals(moved.toList(), session.model.value.drawables.first { it.id == DrawableId("a") }.mesh!!.positions.toList())

		// Exactly one step: a single undo returns to the original geometry with nothing left to undo.
		session.undo()
		assertFalse(session.canUndo.value, "exactly one step was recorded")
		assertEquals(listOf(0f, 0f, 1f, 0f, 0f, 1f), session.model.value.drawables.first { it.id == DrawableId("a") }.mesh!!.positions.toList())
	}

	/** commitObjectPositions with the drawable's existing positions is a no-op that records nothing. */
	@Test
	fun commitObjectPositionsIdentityRecordsNothing() {
		val session = session()
		val current = session.model.value.drawables.first { it.id == DrawableId("a") }.mesh!!.positions

		session.commitObjectPositions(MeshChange.MoveDrawables(listOf(DrawableId("a"))), mapOf(DrawableId("a") to current))
		assertFalse(session.canUndo.value, "committing the same positions records nothing")
	}

	/** The MoveDrawables change carries the object-move history label. */
	@Test
	fun moveDrawablesLabelKey() {
		assertEquals("change.object.move", MeshChange.MoveDrawables(listOf(DrawableId("a"))).labelKey)
	}

	/** Switching to Edit mode clears an armed object select tool, so it cannot leak into the Edit overlay. */
	@Test
	fun switchingToEditClearsArmedObjectTool() {
		val session = session()
		session.beginBoxSelect("area-test")
		assertTrue(session.activeSelectTool.value is ActiveSelectTool.BoxArmed)

		session.setMode(EditorMode.Edit)
		assertNull(session.activeSelectTool.value, "the armed object tool is cleared entering Edit mode")
	}

	/** An undo (restore) ends any armed transient tool regardless of the restored mode. */
	@Test
	fun undoClearsArmedObjectTool() {
		val session = session()
		// A committed step so there is something to undo.
		session.commitObjectPositions(MeshChange.MoveDrawables(listOf(DrawableId("a"))), mapOf(DrawableId("a") to floatArrayOf(9f, 9f, 9f, 9f, 9f, 9f)))
		session.beginBoxSelect("area-test")
		assertTrue(session.activeSelectTool.value is ActiveSelectTool.BoxArmed)

		session.undo()
		assertNull(session.activeSelectTool.value, "restore clears the armed tool")
	}
}
