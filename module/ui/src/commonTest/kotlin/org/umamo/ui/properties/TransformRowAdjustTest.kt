package org.umamo.ui.properties

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.OperatorParameter
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.floatValue
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.withParameter
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.drawableWorldTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The Transform rows' commits register the operation strip the way the viewport's G / S do: a Position
 * edit as a Grab with Move X / Move Z rows, a Size edit as a Scale with Scale X / Scale Z rows, each
 * adjustable from the step's base with the step amended in place.
 *
 * The pivot pin is the case worth having: the default capture scales about the vertex MEAN, which on an
 * asymmetric mesh sits off the bounds center the panel scales about, so adjusting Scale X would have moved
 * the Position readout.
 */
class TransformRowAdjustTest {
	private val drawableId = DrawableId("d")
	private val parameterId = ParameterId("Param")
	private val areaId = "area-1"

	/**
	 * A right triangle 100 wide and 100 tall with its bounds centered on canvas (500, 500), keyed to sit
	 * [posedShiftX] further right at the parameter's maximum.  A triangle, so its vertex mean is off its
	 * bounds center.
	 *
	 * @param Float posedShiftX How far right of rest the shape sits at the parameter's maximum.
	 * @return PuppetModel The model.
	 */
	private fun model(posedShiftX: Float = 0f): PuppetModel {
		val triangle = floatArrayOf(450f, 450f, 550f, 450f, 450f, 550f)
		val atRest = FloatArray(triangle.size)
		val shifted = FloatArray(triangle.size) { componentIndex -> if (componentIndex % 2 == 0) posedShiftX else 0f }
		return PuppetModel(
			parameters = listOf(Parameter(parameterId, "Param", min = -1f, max = 1f, default = 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "d",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh.withLocalEqualToCanvas(triangle, FloatArray(triangle.size), intArrayOf(0, 1, 2)),
						geometryGrid =
							KeyformGrid(
								axes = listOf(KeyformAxis(parameterId, floatArrayOf(-1f, 0f, 1f))),
								cells =
									listOf(
										KeyformCell(intArrayOf(0), MeshDeltaForm(atRest.copyOf())),
										KeyformCell(intArrayOf(1), MeshDeltaForm(atRest.copyOf())),
										KeyformCell(intArrayOf(2), MeshDeltaForm(shifted)),
									),
							),
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * The drawable's world bounds in [session] at the shown pose.
	 *
	 * @param EditorSession session The session.
	 * @return org.umamo.edit.transform.MeshBounds The bounds.
	 */
	private fun boundsOf(session: EditorSession) = drawableWorldTransform(session.model.value, session.shownPose, drawableId)!!.bounds

	/**
	 * [parameters] with the float row [key] set to [value].
	 *
	 * @param List<OperatorParameter> parameters The record's rows.
	 * @param String key The row to set.
	 * @param Float value The new value.
	 * @return List<OperatorParameter> The rows as the strip would hand them back.
	 */
	private fun adjusted(parameters: List<OperatorParameter>, key: String, value: Float): List<OperatorParameter> {
		val row = assertIs<OperatorParameter.FloatParameter>(parameters.first { parameter -> parameter.key == key })
		return parameters.withParameter(key, row.copy(value = value))
	}

	/** A Position edit registers a Grab by its delta, and adjusting Move X re-lands from the base as one step. */
	@Test
	fun aPositionEditRegistersAGrabThatAdjustsFromTheBase() {
		val session = EditorSession(model())
		assertEquals(500f, boundsOf(session).centerX, "precondition")

		session.setDrawableWorldCenterAdjustable(drawableId, 0f, 0f, areaId)

		val record = assertNotNull(session.adjustableOperation.value, "the commit registered")
		assertEquals(areaId, record.areaId)
		val change = assertIs<MeshChange.TransformDrawables>(record.change)
		assertEquals(MeshOperatorKind.Grab, change.kind)
		assertEquals(-500f, record.parameters.floatValue(TransformParameterKeys.MOVE_X, Float.NaN))
		assertEquals(500f, record.parameters.floatValue(TransformParameterKeys.MOVE_Z, Float.NaN), "world y up, shown as Z")
		val stepsBefore = session.historyView.value.steps.size

		session.adjustLastOperation(adjusted(record.parameters, TransformParameterKeys.MOVE_X, -250f))

		assertEquals(250f, boundsOf(session).centerX, 1e-3f, "re-landed from the base by the new delta")
		assertEquals(0f, boundsOf(session).centerY, 1e-3f, "the other row still applies")
		assertEquals(stepsBefore, session.historyView.value.steps.size, "amended in place")
		session.undo()
		assertEquals(500f, boundsOf(session).centerX, "one undo returns to the base")
	}

	/** A Size edit registers a Scale by its factors, and adjusting Scale X keeps the bounds center still. */
	@Test
	fun aSizeEditRegistersAScaleAboutTheBoundsCenter() {
		val session = EditorSession(model())

		session.setDrawableWorldSizeAdjustable(drawableId, 50f, 25f, areaId)

		val record = assertNotNull(session.adjustableOperation.value)
		assertEquals(MeshOperatorKind.Scale, assertIs<MeshChange.TransformDrawables>(record.change).kind)
		assertEquals(0.5f, record.parameters.floatValue(TransformParameterKeys.SCALE_X, Float.NaN))
		assertEquals(0.25f, record.parameters.floatValue(TransformParameterKeys.SCALE_Z, Float.NaN))

		session.adjustLastOperation(adjusted(record.parameters, TransformParameterKeys.SCALE_X, 2f))

		val bounds = boundsOf(session)
		assertEquals(200f, bounds.width, 1e-3f)
		assertEquals(25f, bounds.height, 1e-3f)
		assertEquals(500f, bounds.centerX, 1e-3f, "the pivot is the bounds center, not the vertex mean")
		assertEquals(-500f, bounds.centerY, 1e-3f)
	}

	/** An edit that recorded nothing registers nothing - even right after an unregistered push. */
	@Test
	fun aNoOpEditAfterAnotherPushRegistersNothing() {
		val session = EditorSession(model())
		val target = SelectionTarget.Drawable(drawableId)
		session.setSelection(Selection(setOf(target), target))
		val current = boundsOf(session)

		session.setDrawableWorldCenterAdjustable(drawableId, current.centerX, current.centerY, areaId)
		session.setDrawableWorldSizeAdjustable(drawableId, current.width, current.height, areaId)

		assertNull(session.adjustableOperation.value, "nothing to adjust, and the selection step is not it")
	}

	/** A refused edit (the rig is posed in Object mode) registers nothing. */
	@Test
	fun aRefusedEditRegistersNothing() {
		val session = EditorSession(model(posedShiftX = 100f), initialPose = mapOf(parameterId to 1f))
		val before = session.model.value

		session.setDrawableWorldCenterAdjustable(drawableId, 0f, 0f, areaId)

		assertSame(before, session.model.value)
		assertNull(session.adjustableOperation.value)
	}

	/** In Edit mode on a posed rig the edit lands at rest, registers, and adjusts at rest; the pinned pose is untouched. */
	@Test
	fun inEditModeTheRegistrationRerunsAtRest() {
		val posed = mapOf(parameterId to 1f)
		val session = EditorSession(model(posedShiftX = 100f), initialPose = posed)
		val target = SelectionTarget.Drawable(drawableId)
		session.setSelection(Selection(setOf(target), target))
		session.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Edit, session.mode.value, "the fixture must really be in Edit mode")

		session.setDrawableWorldCenterAdjustable(drawableId, 0f, 0f, areaId)
		val record = assertNotNull(session.adjustableOperation.value)
		session.adjustLastOperation(adjusted(record.parameters, TransformParameterKeys.MOVE_X, -250f))

		assertEquals(250f, boundsOf(session).centerX, 1e-3f, "adjusted where the rigger is looking: at rest")
		assertEquals(posed, session.pose.value, "the pinned pose is held as it is")
	}
}