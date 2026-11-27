package org.umamo.ui.viewport.viewport2d

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.relation.RelationSlot
import org.umamo.edit.structure.rename
import org.umamo.edit.structure.toggleVisibility
import org.umamo.edit.structure.withDeformerVisibility
import org.umamo.render.puppet.LatticeOverlay
import org.umamo.render.puppet.LatticeRole
import org.umamo.render.puppet.MeshOverlaySizes
import org.umamo.runtime.model.visibleDeformerIds
import org.umamo.ui.viewport.StubPuppetViewportService
import org.umamo.ui.workspace.PickKind
import org.umamo.ui.workspace.RelationPickController
import org.umamo.ui.workspace.RelationPickRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins which warps the 2D viewport is told to draw a lattice over and in which role: every shown warp in Object
 * and Pose mode, the active one active and the other selected ones selected, none in Edit mode, nothing for a
 * rotation, a hidden warp, or a warp in a hidden part, and none an armed deformer pick would refuse unless it is
 * selected; and a publish only when that answer changes, so an edit that leaves the roles as they were publishes
 * nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatticeOverlayPublishTest {
	private val sizes = MeshOverlaySizes(2.5f, 1f, 0f)

	@Test
	fun everyShownWarpTakesItsRoleInObjectAndPoseMode() {
		val model = latticeRigModel()
		val selection = Selection(setOf(SelectionTarget.Deformer(LATTICE_RIG_HEAD), SelectionTarget.Deformer(LATTICE_RIG_BODY)), SelectionTarget.Deformer(LATTICE_RIG_BODY))

		for (mode in listOf(EditorMode.Object, EditorMode.Pose)) {
			val overlay = assertNotNull(latticeOverlayOf(mode, selection, model, model.visibleDeformerIds(), sizes))

			assertEquals(
				mapOf(LATTICE_RIG_BODY to LatticeRole.Active, LATTICE_RIG_HEAD to LatticeRole.Selected, LATTICE_RIG_GUIDE to LatticeRole.Idle),
				overlay.roles,
				"$mode: every warp, and no rotation",
			)
			assertEquals(sizes, overlay.sizes)
		}
	}

	@Test
	fun editModeDrawsNoLattice() {
		val model = latticeRigModel()
		val selection = Selection(setOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)), SelectionTarget.Deformer(LATTICE_RIG_BODY))

		assertNull(latticeOverlayOf(EditorMode.Edit, selection, model, model.visibleDeformerIds(), sizes))
	}

	@Test
	fun aWarpInAHiddenPartIsLeftOut() {
		val model = latticeRigModel()
		val shownDeformers = model.visibleDeformerIds() - LATTICE_RIG_GUIDE

		val overlay = assertNotNull(latticeOverlayOf(EditorMode.Object, Selection(), model, shownDeformers, sizes))

		assertEquals(setOf(LATTICE_RIG_BODY, LATTICE_RIG_HEAD), overlay.roles.keys)
		assertNull(latticeOverlayOf(EditorMode.Object, Selection(), model, emptySet(), sizes), "no shown warp, no overlay")
	}

	@Test
	fun aHiddenWarpIsLeftOutEvenActiveWhileTheWarpsNestedInItStay() {
		val model = latticeRigModel().withDeformerVisibility(LATTICE_RIG_BODY, false)
		val selection = Selection(setOf(SelectionTarget.Deformer(LATTICE_RIG_BODY), SelectionTarget.Deformer(LATTICE_RIG_HEAD)), SelectionTarget.Deformer(LATTICE_RIG_BODY))

		val overlay = assertNotNull(latticeOverlayOf(EditorMode.Object, selection, model, model.visibleDeformerIds(), sizes))

		assertEquals(mapOf(LATTICE_RIG_HEAD to LatticeRole.Selected, LATTICE_RIG_GUIDE to LatticeRole.Idle), overlay.roles, "body's own eye hides body alone")
	}

	@Test
	fun aPublishFollowsTheRolesAndNothingElse() =
		runTest {
			val session = latticeRigSession()
			val service = publishing(session)
			assertEquals(1, service.pushedLatticeOverlays.size, "the first value publishes")
			assertEquals(setOf(LatticeRole.Idle), assertNotNull(service.pushedLatticeOverlays.last()).roles.values.toSet(), "nothing selected: every warp idle")

			session.setSelection(Selection(setOf(SelectionTarget.Deformer(LATTICE_RIG_HEAD)), SelectionTarget.Deformer(LATTICE_RIG_HEAD)))
			runCurrent()
			assertEquals(2, service.pushedLatticeOverlays.size, "a selection change publishes")
			assertEquals(LatticeRole.Active, assertNotNull(service.pushedLatticeOverlays.last()).roles[LATTICE_RIG_HEAD])

			session.rename(SelectionTarget.Drawable(LATTICE_RIG_ART), "renamed art")
			runCurrent()
			assertEquals(2, service.pushedLatticeOverlays.size, "an edit that leaves every role as it was publishes nothing")

			session.toggleVisibility(SelectionTarget.Part(LATTICE_RIG_GUIDE_PART))
			runCurrent()
			assertEquals(3, service.pushedLatticeOverlays.size, "hiding a warp's part publishes")
			assertEquals(setOf(LATTICE_RIG_BODY, LATTICE_RIG_HEAD), assertNotNull(service.pushedLatticeOverlays.last()).roles.keys)

			session.toggleVisibility(SelectionTarget.Deformer(LATTICE_RIG_HEAD))
			runCurrent()
			assertEquals(4, service.pushedLatticeOverlays.size, "hiding a warp's own eye publishes")
			assertEquals(setOf(LATTICE_RIG_BODY), assertNotNull(service.pushedLatticeOverlays.last()).roles.keys)

			session.setMode(EditorMode.Edit)
			runCurrent()
			assertEquals(5, service.pushedLatticeOverlays.size)
			assertNull(service.pushedLatticeOverlays.last(), "entering Edit mode takes every lattice down")
		}

	@Test
	fun aSizeChangeRepublishes() =
		runTest {
			val session = latticeRigSession()
			val sizeFlow = MutableStateFlow(sizes)
			val service = StubPuppetViewportService()
			backgroundScope.launch { publishLatticeOverlay(service, session, sizeFlow, MutableStateFlow(null)) }
			runCurrent()

			sizeFlow.value = sizes.copy(vertexDotRadiusPx = 5f)
			runCurrent()

			assertEquals(listOf(2.5f, 5f), service.pushedLatticeOverlays.map { overlay: LatticeOverlay? -> assertNotNull(overlay).sizes.vertexDotRadiusPx })
		}

	@Test
	fun anArmedDeformerPickListsOnlyTheIdleLatticesItWouldBind() {
		val session = latticeRigSession()
		val body = SelectionTarget.Deformer(LATTICE_RIG_BODY)
		val selection = Selection(setOf(body), body)
		val picker = RelationPickController()
		picker.armFor(RelationSlot.ParentDeformer(body), session) { null }

		val overlay = assertNotNull(latticeOverlayOf(EditorMode.Object, selection, session.model.value, session.model.value.visibleDeformerIds(), sizes, pick = picker.request))

		assertEquals(
			mapOf(LATTICE_RIG_BODY to LatticeRole.Active, LATTICE_RIG_GUIDE to LatticeRole.Idle),
			overlay.roles,
			"the body's own child is refused and left out; the body itself keeps its role",
		)
	}

	@Test
	fun aPickThatTakesNoDeformerLeavesEveryLattice() {
		val model = latticeRigModel()
		val drawablePick = RelationPickRequest(setOf(PickKind.Drawable), owner = null, admits = { false }, onPicked = {})

		val overlay = assertNotNull(latticeOverlayOf(EditorMode.Object, Selection(), model, model.visibleDeformerIds(), sizes, pick = drawablePick))

		assertEquals(setOf(LATTICE_RIG_BODY, LATTICE_RIG_HEAD, LATTICE_RIG_GUIDE), overlay.roles.keys)
	}

	@Test
	fun armingAndEndingAPickRepublishes() =
		runTest {
			val session = latticeRigSession()
			val picks = MutableStateFlow<RelationPickRequest?>(null)
			val service = StubPuppetViewportService()
			backgroundScope.launch { publishLatticeOverlay(service, session, MutableStateFlow(sizes), picks) }
			runCurrent()
			val head = SelectionTarget.Deformer(LATTICE_RIG_HEAD)
			picks.value = RelationPickRequest(setOf(PickKind.Deformer), owner = null, admits = { target -> target != head }, onPicked = {})
			runCurrent()

			assertEquals(setOf(LATTICE_RIG_BODY, LATTICE_RIG_GUIDE), assertNotNull(service.pushedLatticeOverlays.last()).roles.keys, "the refused lattice goes")

			picks.value = null
			runCurrent()

			assertEquals(setOf(LATTICE_RIG_BODY, LATTICE_RIG_HEAD, LATTICE_RIG_GUIDE), assertNotNull(service.pushedLatticeOverlays.last()).roles.keys, "it returns as the pick ends")
		}

	/**
	 * A stub service with the lattice publish running over [session] at the test's sizes, with no pick armed.
	 *
	 * @param EditorSession session The session.
	 * @return StubPuppetViewportService The service it publishes to.
	 */
	private fun TestScope.publishing(session: EditorSession): StubPuppetViewportService {
		val service = StubPuppetViewportService()
		backgroundScope.launch { publishLatticeOverlay(service, session, MutableStateFlow(sizes), MutableStateFlow(null)) }
		runCurrent()

		return service
	}
}