package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DeformerId
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import org.umamo.ui.viewport.gizmo.clickIn
import org.umamo.ui.viewport.gizmo.moveIn
import org.umamo.ui.viewport.gizmo.withKeyHeld
import org.umamo.ui.workspace.PickKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the relation-pick overlay's viewport resolution for a deformer: a drawn lattice is taken first, the art's
 * parent deformer otherwise, a lattice the field refuses gives way to the one beneath it, the lattice under the
 * pointer lights up for as long as the pick lasts, and an Alt click lists the stack as the targets the pick takes.
 * The stub answers the lattice and art picks from each area's stacks, whatever the point.
 */
@OptIn(ExperimentalTestApi::class)
class ViewportPickOverlayTest {
	private val point = rigScreenOf(10f, -10f)

	/**
	 * A session over the lattice rig whose art is deformed by the head warp, with the art selected.
	 *
	 * @return EditorSession The session.
	 */
	private fun headArtSession(): EditorSession {
		val rig = latticeRigModel()
		val session = EditorSession(rig.copy(drawables = rig.drawables.map { drawable -> drawable.copy(parentDeformerId = LATTICE_RIG_HEAD) }))
		val art = SelectionTarget.Drawable(LATTICE_RIG_ART)
		session.setSelection(Selection(setOf(art), art))

		return session
	}

	@Test
	fun aDrawnLatticeIsTakenAheadOfTheArtUnderIt() =
		runComposeUiTest {
			val session = headArtSession()
			val fixture = mountGizmoOverlays(session)
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_BODY)
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(LATTICE_RIG_ART, 0f, 1f))
			val selection = session.selection.value
			val picked = ArrayList<SelectionTarget>()
			runOnIdle { fixture.relationPick.arm(setOf(PickKind.Deformer), model = { fixture.session.model.value }) { target -> picked.add(target) } }

			clickIn(LEFT_AREA, point)

			assertEquals(listOf<SelectionTarget>(SelectionTarget.Deformer(LATTICE_RIG_BODY)), picked)
			assertEquals(selection, session.selection.value, "the pick selects nothing")
		}

	@Test
	fun artOffEveryLatticeTakesItsParentDeformer() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(headArtSession())
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(LATTICE_RIG_ART, 0f, 1f))
			val picked = ArrayList<SelectionTarget>()
			runOnIdle { fixture.relationPick.arm(setOf(PickKind.Deformer), model = { fixture.session.model.value }) { target -> picked.add(target) } }

			moveIn(LEFT_AREA, listOf(point))
			assertEquals(SelectionTarget.Deformer(LATTICE_RIG_HEAD), fixture.relationPick.hoveredTarget, "the badge names the art's deformer")
			assertTrue(fixture.service.pushedLatticeHovers.none { (_, deformerId) -> deformerId != null }, "reached through its art, no lattice lights up")

			clickIn(LEFT_AREA, point)

			assertEquals(listOf<SelectionTarget>(SelectionTarget.Deformer(LATTICE_RIG_HEAD)), picked)
		}

	@Test
	fun artUnderNoDeformerIsAMissAndThePickStaysArmed() =
		runComposeUiTest {
			val session = latticeRigSession(listOf(SelectionTarget.Drawable(LATTICE_RIG_ART)))
			val fixture = mountGizmoOverlays(session)
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(LATTICE_RIG_ART, 0f, 1f))
			val selection = session.selection.value
			val picked = ArrayList<SelectionTarget>()
			runOnIdle { fixture.relationPick.arm(setOf(PickKind.Deformer), model = { fixture.session.model.value }) { target -> picked.add(target) } }

			clickIn(LEFT_AREA, point)

			assertTrue(picked.isEmpty())
			assertNotNull(fixture.relationPick.request, "a miss leaves the pick armed to try again")
			assertEquals(selection, session.selection.value, "and the click reached no gizmo")
		}

	@Test
	fun aLatticeTheFieldRefusesGivesWayToTheOneBeneathIt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_HEAD))))
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD, LATTICE_RIG_BODY)
			val own = SelectionTarget.Deformer(LATTICE_RIG_HEAD)
			val picked = ArrayList<SelectionTarget>()
			runOnIdle { fixture.relationPick.arm(setOf(PickKind.Deformer), admits = { target -> target != own }) { target -> picked.add(target) } }

			moveIn(LEFT_AREA, listOf(point))
			assertEquals(LEFT_AREA to LATTICE_RIG_BODY, fixture.service.pushedLatticeHovers.last(), "the refused lattice never lights up")

			clickIn(LEFT_AREA, point)

			assertEquals(listOf<SelectionTarget>(SelectionTarget.Deformer(LATTICE_RIG_BODY)), picked)
		}

	@Test
	fun theLatticeHoverGoesWithThePick() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(headArtSession())
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_GUIDE)
			runOnIdle { fixture.relationPick.arm(setOf(PickKind.Deformer)) {} }

			moveIn(LEFT_AREA, listOf(point))
			assertEquals(LEFT_AREA to LATTICE_RIG_GUIDE, fixture.service.pushedLatticeHovers.last())
			assertEquals(SelectionTarget.Deformer(LATTICE_RIG_GUIDE), fixture.relationPick.hoveredTarget)

			val before = fixture.service.pushedLatticeHovers.size
			runOnIdle { fixture.relationPick.cancel() }
			waitForIdle()

			// The pick's highlight clears first; the Object gizmo uncovered beneath may then light what is under the
			// pointer by its own rule.
			assertEquals(LEFT_AREA to null, fixture.service.pushedLatticeHovers.drop(before).firstOrNull(), "no highlight outlives the pick")
			assertTrue(fixture.service.pushedLatticeHovers.none { (areaId, _) -> areaId == RIGHT_AREA }, "the other area never hovers")
		}

	@Test
	fun anAltClickListsTheStackAsTheTargetsThePickTakes() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(headArtSession())
			// The art's own deformer is also a lattice under the pointer: it is listed once.
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD, LATTICE_RIG_BODY)
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(LATTICE_RIG_ART, 0f, 1f))
			val picked = ArrayList<SelectionTarget>()
			runOnIdle { fixture.relationPick.arm(setOf(PickKind.Deformer), model = { fixture.session.model.value }) { target -> picked.add(target) } }

			withKeyHeld(LEFT_AREA, Key.AltLeft) { clickIn(LEFT_AREA, point) }

			val (areaId, _, candidates) = fixture.overlapRequests.single()
			assertEquals(LEFT_AREA, areaId)
			assertEquals(listOf<SelectionTarget>(SelectionTarget.Deformer(LATTICE_RIG_HEAD), SelectionTarget.Deformer(LATTICE_RIG_BODY)), candidates.map { candidate -> candidate.target })
			assertTrue(picked.isEmpty(), "the popup's row binds, not the Alt click")
			assertNotNull(fixture.relationPick.request)
		}

	@Test
	fun anAltClickOverOneTargetBindsIt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(headArtSession())
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(LATTICE_RIG_ART, 0f, 1f))
			val picked = ArrayList<SelectionTarget>()
			runOnIdle { fixture.relationPick.arm(setOf(PickKind.Drawable)) { target -> picked.add(target) } }

			withKeyHeld(LEFT_AREA, Key.AltLeft) { clickIn(LEFT_AREA, point) }

			assertTrue(fixture.overlapRequests.isEmpty())
			assertEquals(listOf<SelectionTarget>(SelectionTarget.Drawable(LATTICE_RIG_ART)), picked)
			assertNull(fixture.relationPick.request)
		}

	@Test
	fun aPickThatTakesNoDeformerNeverAsksForALattice() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(headArtSession())
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf<DeformerId>(LATTICE_RIG_BODY)
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(LATTICE_RIG_ART, 0f, 1f))
			val picked = ArrayList<SelectionTarget>()
			runOnIdle { fixture.relationPick.arm(setOf(PickKind.Drawable)) { target -> picked.add(target) } }

			clickIn(LEFT_AREA, point)

			assertEquals(listOf<SelectionTarget>(SelectionTarget.Drawable(LATTICE_RIG_ART)), picked, "the art, not the lattice over it")
		}
}