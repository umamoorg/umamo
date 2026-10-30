package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.SelectionTarget
import org.umamo.edit.selectAllObjects
import org.umamo.edit.structure.toggleSelectable
import org.umamo.render.pick.PickCandidate
import org.umamo.ui.viewport.gizmo.HIT_RADIUS_PX
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.clickIn
import org.umamo.ui.viewport.gizmo.dragIn
import org.umamo.ui.viewport.gizmo.withKeyHeld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins how the 2D viewport's Object overlay selects warps by their drawn lattices: a lattice under the pointer
 * wins over the art there, a locked warp passes the click through, an Alt click lists the lattices before the
 * art, and box select encloses a warp by its lattice's center.  The stub
 * answers the lattice pick and the art pick from each area's stacks, whatever the point, and the region from
 * the centers each case places.
 */
@OptIn(ExperimentalTestApi::class)
class LatticeSelectionGestureTest {
	private val body = SelectionTarget.Deformer(LATTICE_RIG_BODY)
	private val head = SelectionTarget.Deformer(LATTICE_RIG_HEAD)
	private val art = SelectionTarget.Drawable(LATTICE_RIG_ART)

	/** Where every click lands; the stubs answer from their stacks wherever it is. */
	private val clickPoint = rigScreenOf(10f, -10f)

	/** A box around world (10, -10) only. */
	private val aroundCenter = Offset(180f, 130f) to Offset(220f, 170f)

	@Test
	fun aLatticeUnderThePointerWinsOverTheArt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession())
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD, LATTICE_RIG_BODY)
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(LATTICE_RIG_ART, 0f, 0f))

			clickIn(LEFT_AREA, clickPoint)

			assertEquals(setOf<SelectionTarget>(head), fixture.session.selection.value.targets)
			assertEquals(head, fixture.session.selection.value.active)
			assertEquals(HIT_RADIUS_PX, fixture.service.latticePickRequests.last().second.first, "a control point picks like an Edit-mode vertex")
			assertTrue(fixture.service.latticePickRequests.last().second.second < HIT_RADIUS_PX, "and a grid line takes a narrower band")

			fixture.service.latticeStackByArea[LEFT_AREA] = emptyList()
			clickIn(LEFT_AREA, clickPoint)
			assertEquals(setOf<SelectionTarget>(art), fixture.session.selection.value.targets, "with no lattice there, the art")
		}

	@Test
	fun aLockedWarpPassesTheClickThrough() =
		runComposeUiTest {
			val session = latticeRigSession()
			session.toggleSelectable(head)
			val fixture = mountGizmoOverlays(session)
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD)
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(LATTICE_RIG_ART, 0f, 0f))

			clickIn(LEFT_AREA, clickPoint)

			assertEquals(setOf<SelectionTarget>(art), fixture.session.selection.value.targets)

			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD, LATTICE_RIG_BODY)
			clickIn(LEFT_AREA, clickPoint)
			assertEquals(setOf<SelectionTarget>(body), fixture.session.selection.value.targets, "the next selectable lattice under it")
		}

	@Test
	fun anAltClickListsTheLatticesBeforeTheArt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession())
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD, LATTICE_RIG_BODY)
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(LATTICE_RIG_ART, 0f, 0f))

			withKeyHeld(LEFT_AREA, Key.AltLeft) { clickIn(LEFT_AREA, clickPoint) }

			assertEquals(listOf<SelectionTarget>(head, body, art), fixture.overlapRequests.single().third.map { candidate -> candidate.target })
			assertTrue(fixture.session.selection.value.isEmpty, "the picker decides, not the click")
		}

	@Test
	fun aBoxEnclosesAWarpByItsLatticeCenter() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession())
			fixture.service.latticeCentersByArea[LEFT_AREA] = mapOf(LATTICE_RIG_HEAD to floatArrayOf(12f, -12f), LATTICE_RIG_BODY to floatArrayOf(100f, 100f))
			fixture.service.centroids[LATTICE_RIG_ART] = floatArrayOf(10f, -10f)

			dragIn(LEFT_AREA, aroundCenter.first, listOf(aroundCenter.second))

			assertEquals(setOf<SelectionTarget>(head, art), fixture.session.selection.value.targets, "the warp whose center is boxed, and the art")
		}
}