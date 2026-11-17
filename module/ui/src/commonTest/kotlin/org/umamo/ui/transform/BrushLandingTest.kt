package org.umamo.ui.transform

import org.umamo.edit.BrushSettings
import org.umamo.edit.EditorMode
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.SelectionTarget
import org.umamo.edit.transform.BrushParameterKeys
import org.umamo.edit.transform.BrushStroke
import org.umamo.edit.transform.brushParameters
import org.umamo.render.eval.warpLatticeAt
import org.umamo.ui.assertArrayNear
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_BODY
import org.umamo.ui.viewport.viewport2d.latticeRigModel
import org.umamo.ui.viewport.viewport2d.latticeRigSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the deform brush's landing: a stroke over a cage's corner moves that corner's lattice point and
 * leaves the rest of the cage alone, a stroke that reaches no cage changes nothing, and the model the
 * stroke lands on carries the lattice where the stroke put it.
 */
class BrushLandingTest {
	// How close a landed point must be, in world units.
	private val tolerance = 1e-3f

	/** The brush's configuration: the smooth falloff at a fifteen-unit radius and full strength. */
	private val settings = BrushSettings(ProportionalFalloff.Smooth, 15f, 1f)

	/**
	 * A stroke that walks the brush along the cage's bottom edge, from the left of the lower-left corner
	 * to the right of it.
	 *
	 * @return BrushStroke The stroke.
	 */
	private fun stroke(): BrushStroke = BrushStroke.starting(-10f, 0f, 15f).stampedTo(10f, 0f, 15f)

	/** The body's rest lattice in world space. */
	private fun latticeWorld(): FloatArray = warpLatticeAt(latticeRigModel(), emptyMap(), LATTICE_RIG_BODY)!!.world

	/** A stroke over the cage's lower-left corner moves that corner and leaves the rest of the cage alone. */
	@Test
	fun aBrushOverAPointMovesThatPointAndLeavesTheRestAlone() {
		val before = latticeWorld()
		val world = brushLatticeWorld(latticeRigModel(), stroke(), settings, listOf(LATTICE_RIG_BODY))

		assertEquals(setOf(LATTICE_RIG_BODY), world.keys, "the brush touched the one cage it was over")
		val after = world.getValue(LATTICE_RIG_BODY)

		assertTrue(after[0] > before[0], "the corner the brush passed over moved along x")
		assertEquals(before[1], after[1], tolerance, "and did not move off the edge")

		for (pointIndex in 1 until before.size / 2) {
			assertEquals(before[pointIndex * 2], after[pointIndex * 2], tolerance, "point $pointIndex stayed on x")
			assertEquals(before[pointIndex * 2 + 1], after[pointIndex * 2 + 1], tolerance, "point $pointIndex stayed on z")
		}
	}

	/** The falloff holds a point back short of the brush's whole travel. */
	@Test
	fun theFalloffHoldsAPointBackShortOfTheWholeTravel() {
		val before = latticeWorld()
		val after = brushLatticeWorld(latticeRigModel(), stroke(), settings, listOf(LATTICE_RIG_BODY)).getValue(LATTICE_RIG_BODY)
		val travelled = after[0] - before[0]

		assertTrue(travelled > 0f, "the point moved")
		assertTrue(travelled < 20f, "the falloff held it back short of the brush's travel")
	}

	/** A stroke that reaches no lattice changes nothing. */
	@Test
	fun aStrokeThatReachesNoLatticeChangesNothing() {
		val far = BrushStroke.starting(500f, 500f, 15f).stampedTo(510f, 500f, 15f)
		val world = brushLatticeWorld(latticeRigModel(), far, settings, listOf(LATTICE_RIG_BODY))

		assertTrue(world.isEmpty(), "the brush reached no cage")
	}

	/** The model a stroke lands on carries the lattice where the stroke put it. */
	@Test
	fun theLandedModelCarriesTheLatticeWhereTheStrokePutIt() {
		val model = latticeRigModel()
		val world = brushLatticeWorld(model, stroke(), settings, listOf(LATTICE_RIG_BODY)).getValue(LATTICE_RIG_BODY)
		val reshape = brushLatticeReshape(model, stroke(), settings, listOf(LATTICE_RIG_BODY))

		assertArrayNear(world, warpLatticeAt(reshape.model, emptyMap(), LATTICE_RIG_BODY)!!.world, tolerance, "the lattice lands where the brush put it")
		assertEquals(0, reshape.unconvertedCount)
	}

	/**
	 * The stroke's own step is what an adjustment rewrites: the strength row set to half lands over the
	 * same step, moves the lattice less, and one undo puts the pre-stroke lattice back.
	 */
	@Test
	fun theAdjustedStrokeLandsOverItsOwnStep() {
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
		session.setMode(EditorMode.Edit)
		val stepsBefore = session.historyView.value.steps.size

		assertTrue(commitBrushStroke(session, "area", stroke(), settings, listOf(LATTICE_RIG_BODY)), "the stroke recorded a step")
		val stepsAfterStroke = session.historyView.value.steps.size
		assertEquals(stepsBefore + 1, stepsAfterStroke, "one stroke, one step")
		val full = warpLatticeAt(session.model.value, emptyMap(), LATTICE_RIG_BODY)!!.world[0]

		session.adjustLastOperation(brushParameters(settings.copy(strength = 0.5f)), adjustedKey = BrushParameterKeys.STRENGTH)

		assertEquals(stepsAfterStroke, session.historyView.value.steps.size, "the adjustment rewrote the stroke's own step")
		val halved = warpLatticeAt(session.model.value, emptyMap(), LATTICE_RIG_BODY)!!.world[0]
		assertTrue(halved < full, "the weaker stroke moved the corner less")

		session.undo()
		assertArrayNear(latticeWorld(), warpLatticeAt(session.model.value, emptyMap(), LATTICE_RIG_BODY)!!.world, tolerance, "one undo puts the pre-stroke lattice back")
	}
}