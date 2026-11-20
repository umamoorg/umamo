package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.AdjustableOperation
import org.umamo.edit.DepthSurface
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.OperatorParameter
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TargetShape
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.transform.ToShapeParameterKeys
import org.umamo.edit.withParameter
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.ui.transform.elementEditStage
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The area the strip registers in. */
private const val STRIP_AREA = "area-1"

/** The parameter both warps are keyed on, two keys holding one lattice. */
private val SHAPE_PARAMETER = ParameterId("ParamAngleX")

/** The face warp: 4 x 4 cells over canvas x 0..80 and y 0..80, so world x 0..80 and z -80..0. */
private val SHAPE_FACE = DeformerId("face")

/** The hair warp: the same lattice 200 px to the right. */
private val SHAPE_HAIR = DeformerId("hair")

/** The part both warps belong to. */
private val SHAPE_PART = PartId("facePart")

/** The cells along each side of both lattices. */
private const val SHAPE_DIVISIONS = 4

/** The radius a lattice reaching 40 px from its center bends round all the way, its ends meeting at the back: 40 / pi. */
private val SHAPE_WHOLE_TURN_RADIUS = (40.0 / PI).toFloat()

/** The radius the same lattice bends round as a sphere all the way, its corners at the back: 40 sqrt(2) / pi. */
private val SHAPE_WHOLE_TURN_SPHERE_RADIUS = SHAPE_WHOLE_TURN_RADIUS * sqrt(2f)

/** The radius it bends round over the front half, its edges on the rim: 40 x 2 / pi. */
private val SHAPE_HALF_TURN_RADIUS = (80.0 / PI).toFloat()

/** Where a point 20 px out of the center shows bent over the front half: R sin(20 / R). */
private val SHAPE_HALF_TURN_QUARTER_OUT = SHAPE_HALF_TURN_RADIUS * sin(20f / SHAPE_HALF_TURN_RADIUS)

/**
 * To Shape as a Pose-mode lattice edit ([handleToShapeRequest]): it holds the bent lattice pending under its own history
 * label, leaves the model alone, and hands the depth rotation the radius and surface it bent round; its strip switches
 * the shape, the axis, and the factor over the one step; Edit and Object mode refuse it; a selection that would not
 * move answers with the notice; and Individual Origins bends each warp round its own center.
 */
class ToShapeEditTest {
	/**
	 * A 4 x 4 root warp whose lattice starts at canvas x [originX], keyed with two keys holding one form so every pose
	 * shows it.
	 *
	 * @param DeformerId id The warp.
	 * @param Float originX Its left edge in canvas px.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(id: DeformerId, originX: Float): Deformer.Warp {
		val points = FloatArray((SHAPE_DIVISIONS + 1) * (SHAPE_DIVISIONS + 1) * 2)

		for (row in 0..SHAPE_DIVISIONS) {
			for (column in 0..SHAPE_DIVISIONS) {
				val pointIndex = row * (SHAPE_DIVISIONS + 1) + column
				points[pointIndex * 2] = originX + 20f * column
				points[pointIndex * 2 + 1] = 20f * row
			}
		}

		return Deformer.Warp(
			id = id,
			name = id.raw,
			parent = null,
			partId = SHAPE_PART,
			rows = SHAPE_DIVISIONS,
			columns = SHAPE_DIVISIONS,
			isQuadTransform = true,
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(SHAPE_PARAMETER, floatArrayOf(-1f, 1f))),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(points)), KeyformCell(intArrayOf(1), WarpLatticeForm(points.copyOf()))),
				),
		)
	}

	/**
	 * The rig's model: the face and hair warps side by side.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(SHAPE_PARAMETER, "X", -1f, 1f, 0f)),
			parts = listOf(Part(SHAPE_PART, "Face", emptyList())),
			deformers = listOf(warp(SHAPE_FACE, 0f), warp(SHAPE_HAIR, 200f)),
			drawables = emptyList(),
			rootChildren = listOf(OrgChild.Part(SHAPE_PART)),
			rootPartId = null,
		)

	/**
	 * A session over the rig with [warps] selected (the last active) in [mode], every lattice point of each selected.
	 *
	 * @param EditorMode mode The mode to work in.
	 * @param List<DeformerId> warps The warps to select.
	 * @return EditorSession The session.
	 */
	private fun session(mode: EditorMode = EditorMode.Pose, warps: List<DeformerId> = listOf(SHAPE_FACE)): EditorSession {
		val session = EditorSession(model())
		val targets = warps.map { deformerId -> SelectionTarget.Deformer(deformerId) }
		session.setSelection(Selection(targets.toSet(), targets.last()))
		session.setMode(mode)
		assertEquals(mode, session.mode.value)

		if (mode != EditorMode.Object) {
			val everyPoint = (0 until (SHAPE_DIVISIONS + 1) * (SHAPE_DIVISIONS + 1)).map { pointIndex -> MeshElement.Vertex(pointIndex) }.toSet()
			session.setMeshSelection(session.meshSelection.value.copy(elementsByOwner = warps.associate { deformerId -> ElementOwner.Deformer(deformerId) to everyPoint }))
		}

		return session
	}

	/**
	 * Runs a To Shape over the session's selection, captured as the overlay would capture it.
	 *
	 * @param EditorSession session The session.
	 * @param TargetShape shape The surface to wrap onto.
	 * @param List<ElementOwner> owners The lattices to offer, the session's own by default.
	 * @return AdjustableOperation? The registered record, or null when nothing was recorded.
	 */
	private fun toShape(session: EditorSession, shape: TargetShape, owners: List<ElementOwner> = session.meshSelection.value.owners): AdjustableOperation? =
		handleToShapeRequest(session, editOwnerGeometries(session.model.value, owners, session.elementEditStage()), session.elementEditStage(), shape, STRIP_AREA)

	/**
	 * The point at [row] and [column] of a warp's pending lattice, in canvas px.
	 *
	 * @param EditorSession session The session.
	 * @param DeformerId warp The warp.
	 * @param Int row The row, top first in canvas space.
	 * @param Int column The column, left first.
	 * @return Pair<Float, Float> The point's canvas x and y.
	 */
	private fun pendingPoint(session: EditorSession, warp: DeformerId, row: Int, column: Int): Pair<Float, Float> {
		val points = assertNotNull(session.pendingLatticeEdits.value[warp], "$warp is held pending").controlPoints
		val pointIndex = row * (SHAPE_DIVISIONS + 1) + column

		return points[pointIndex * 2] to points[pointIndex * 2 + 1]
	}

	/**
	 * Sets one of the live strip record's rows, as the strip does.
	 *
	 * @param EditorSession session The session.
	 * @param String key The row.
	 * @param Function updated The row's new state from its current one.
	 */
	private fun adjust(session: EditorSession, key: String, updated: (OperatorParameter) -> OperatorParameter) {
		val record = assertNotNull(session.adjustableOperation.value)
		val row = record.parameters.first { parameter -> parameter.key == key }
		session.adjustLastOperation(record.parameters.withParameter(key, updated(row)), adjustedKey = key)
	}

	/**
	 * Picks one choice on a choice row of the live strip record.
	 *
	 * @param EditorSession session The session.
	 * @param String key The row.
	 * @param String choice The choice's key.
	 */
	private fun choose(session: EditorSession, key: String, choice: String) = adjust(session, key) { row -> (row as OperatorParameter.ChoiceParameter).copy(value = choice) }

	/** A Pose-mode cylinder holds the warp pending, its columns bent and its rows untouched, under the To Shape label. */
	@Test
	fun aPoseToShapeHoldsTheLatticePendingUnderItsOwnLabel() {
		val session = session()
		val modelBefore = session.model.value
		val record = assertNotNull(toShape(session, TargetShape.Cylinder))

		assertSame(modelBefore, session.model.value, "a posed edit leaves the model alone until a key")
		assertEquals("change.pose.toShape", session.historyView.value.steps.last().labelKey)
		assertEquals(
			listOf(ToShapeParameterKeys.SHAPE, ToShapeParameterKeys.AXIS, ToShapeParameterKeys.DEGREES, ToShapeParameterKeys.FACTOR),
			record.parameters.map { row -> row.key },
		)
		assertEquals(STRIP_AREA, record.areaId)

		// All the way round: the ends meet behind the center and the quarter columns sit on the rim.
		val expectedColumns = listOf(40f, 40f - SHAPE_WHOLE_TURN_RADIUS, 40f, 40f + SHAPE_WHOLE_TURN_RADIUS, 40f)

		for (row in 0..SHAPE_DIVISIONS) {
			for (column in 0..SHAPE_DIVISIONS) {
				val (x, y) = pendingPoint(session, SHAPE_FACE, row, column)
				assertEquals(expectedColumns[column], x, 1e-3f, "row $row column $column x")
				assertEquals(20f * row, y, 1e-3f, "row $row column $column y")
			}
		}

		assertEquals(SHAPE_WHOLE_TURN_RADIUS, session.pivotDepth.value, 1e-3f, "the depth rotation turns round the cylinder laid down")
		assertEquals(DepthSurface.Cylinder, session.depthSurface.value)
	}

	/** The strip switches to a sphere, about X, a half turn, and no factor, each over the same one step; undo clears it. */
	@Test
	fun theStripSwitchesShapeAxisDegreesAndFactorOverTheSameStep() {
		val session = session()
		assertNotNull(toShape(session, TargetShape.Cylinder))
		val stepsAfterCommit = session.historyView.value.steps.size

		choose(session, ToShapeParameterKeys.SHAPE, "sphere")
		val (cornerX, cornerY) = pendingPoint(session, SHAPE_FACE, 0, 0)
		assertEquals(40f, cornerX, 1e-3f, "a whole-turn sphere bends the corner round to the back")
		assertEquals(40f, cornerY, 1e-3f)
		assertEquals(SHAPE_WHOLE_TURN_SPHERE_RADIUS, session.pivotDepth.value, 1e-3f, "an adjustment hands over its own radius")
		assertEquals(DepthSurface.Sphere, session.depthSurface.value)

		choose(session, ToShapeParameterKeys.SHAPE, "cylinder")
		choose(session, ToShapeParameterKeys.AXIS, "x")
		val (columnX, rowY) = pendingPoint(session, SHAPE_FACE, 1, 1)
		assertEquals(20f, columnX, 1e-3f, "about X the columns stay")
		assertEquals(40f - SHAPE_WHOLE_TURN_RADIUS, rowY, 1e-3f, "and the quarter row bends onto the rim")

		adjust(session, ToShapeParameterKeys.DEGREES) { row -> (row as OperatorParameter.FloatParameter).copy(value = 180f) }
		assertEquals(40f - SHAPE_HALF_TURN_QUARTER_OUT, pendingPoint(session, SHAPE_FACE, 1, 1).second, 1e-3f, "a half turn bends it gentler")
		assertEquals(SHAPE_HALF_TURN_RADIUS, session.pivotDepth.value, 1e-3f)

		adjust(session, ToShapeParameterKeys.FACTOR) { row -> (row as OperatorParameter.FloatParameter).copy(value = 0f) }
		assertEquals(20f to 20f, pendingPoint(session, SHAPE_FACE, 1, 1), "no factor lands every point where it was")
		assertEquals(stepsAfterCommit, session.historyView.value.steps.size, "every adjustment amends the one step")

		session.undo()
		assertTrue(session.pendingLatticeEdits.value.isEmpty(), "undo drops the shaped lattice")
	}

	/** Edit and Object mode refuse To Shape for now: no step, no pending lattice, the model untouched. */
	@Test
	fun editAndObjectModeRefuse() {
		for (mode in listOf(EditorMode.Edit, EditorMode.Object)) {
			val session = session(mode)
			val modelBefore = session.model.value
			val stepsBefore = session.historyView.value.steps.size

			assertNull(toShape(session, TargetShape.Sphere, listOf(ElementOwner.Deformer(SHAPE_FACE))), "$mode refuses")
			assertNull(session.notice.value, "$mode refuses before looking at the selection")
			assertSame(modelBefore, session.model.value)
			assertEquals(stepsBefore, session.historyView.value.steps.size)
			assertTrue(session.pendingLatticeEdits.value.isEmpty())
			assertEquals(0f, session.pivotDepth.value, "the depth rotation is left alone")
		}
	}

	/** One point about its own median has no reach: the notice answers, and nothing is recorded. */
	@Test
	fun aSelectionThatWouldNotMoveAnswersWithTheNotice() {
		val session = session()
		session.selectLatticePoints(ElementOwner.Deformer(SHAPE_FACE), setOf(12), active = 12)
		val stepsBefore = session.historyView.value.steps.size

		assertNull(toShape(session, TargetShape.Sphere))
		assertEquals("notice.toShape.nothing", assertNotNull(session.notice.value).messageKey)
		assertEquals(stepsBefore, session.historyView.value.steps.size)
		assertTrue(session.pendingLatticeEdits.value.isEmpty())
	}

	/** Under Individual Origins each selected warp bends round its own center, its own edges on its own rim. */
	@Test
	fun individualOriginsShapesEachWarpRoundItsOwnCenter() {
		val session = session(warps = listOf(SHAPE_FACE, SHAPE_HAIR))
		session.setPivotMode(TransformPivotMode.IndividualOrigins)
		assertEquals(listOf(ElementOwner.Deformer(SHAPE_FACE), ElementOwner.Deformer(SHAPE_HAIR)).toSet(), session.meshSelection.value.owners.toSet())

		assertNotNull(toShape(session, TargetShape.Cylinder))

		assertEquals(40f, pendingPoint(session, SHAPE_FACE, 2, 4).first, 1e-3f, "the face's end comes round behind its own center")
		assertEquals(40f + SHAPE_WHOLE_TURN_RADIUS, pendingPoint(session, SHAPE_FACE, 2, 3).first, 1e-3f)
		assertEquals(240f, pendingPoint(session, SHAPE_HAIR, 2, 0).first, 1e-3f, "the hair's behind its own")
		assertEquals(240f - SHAPE_WHOLE_TURN_RADIUS, pendingPoint(session, SHAPE_HAIR, 2, 1).first, 1e-3f)
	}
}