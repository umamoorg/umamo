package org.umamo.edit

import org.umamo.edit.PoseLatticeTestRig.art
import org.umamo.edit.PoseLatticeTestRig.cage
import org.umamo.edit.PoseLatticeTestRig.model
import org.umamo.edit.PoseLatticeTestRig.offRest
import org.umamo.edit.PoseLatticeTestRig.parameterP
import org.umamo.edit.PoseLatticeTestRig.posing
import org.umamo.edit.PoseLatticeTestRig.second
import org.umamo.edit.transform.beginMeshOperator
import org.umamo.runtime.model.ElementOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins Pose mode as Edit mode at the pose: entering it opens the warps the pose draws the way Tab opens Edit mode,
 * the select modes switch a lattice between points, grid lines, and cells, Select All and G work on its elements,
 * Alt+Q's switch moves to another posed warp, and a warp's element selection rides a Tab between the two.
 */
class PoseSessionTest {
	@Test
	fun posingOpensTheSelectedWarps() {
		val session = posing(listOf(SelectionTarget.Drawable(art), SelectionTarget.Deformer(cage), SelectionTarget.Deformer(second)))

		assertEquals(EditorMode.Pose, session.mode.value)
		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(cage), ElementOwner.Deformer(second)), session.meshSelection.value.owners, "the warps, never the art")
		assertEquals(ElementOwner.Deformer(second), session.meshSelection.value.activeOwner)
	}

	@Test
	fun onlyTheWarpsThePoseDrawsJoin() {
		val atRest = posing(listOf(SelectionTarget.Deformer(cage), SelectionTarget.Deformer(offRest)))
		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(cage)), atRest.meshSelection.value.owners, "keyed above the default, so hidden at rest")

		val raised = posing(listOf(SelectionTarget.Deformer(cage), SelectionTarget.Deformer(offRest)), mapOf(parameterP to 0.75f))
		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(cage), ElementOwner.Deformer(offRest)), raised.meshSelection.value.owners, "drawn at the pose")
	}

	@Test
	fun withNoWarpSelectedItFallsBackToTheRememberedWarpThenTheFirst() {
		val remembered = EditorSession(model())
		remembered.setSelection(Selection(setOf(SelectionTarget.Deformer(second)), SelectionTarget.Deformer(second)))
		remembered.setSelection(Selection())
		remembered.setMode(EditorMode.Pose)
		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(second)), remembered.meshSelection.value.owners)
		assertTrue(remembered.selection.value.isEmpty, "a soft seed, not a re-selection")

		val fresh = posing(listOf(SelectionTarget.Drawable(art)))
		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(cage)), fresh.meshSelection.value.owners, "the first warp the pose draws")
	}

	@Test
	fun aRigWithNoWarpOpensAnEmptySession() {
		val session = EditorSession(model(withWarps = false))

		session.setMode(EditorMode.Pose)

		assertEquals(EditorMode.Pose, session.mode.value, "it still scrubs and keys")
		assertTrue(session.meshSelection.value.owners.isEmpty())
	}

	@Test
	fun theSelectModesSwitchALatticesElements() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)))

		session.setMeshSelectMode(MeshSelectMode.Edge)
		session.selectAllMeshElements()
		assertEquals(MeshSelectMode.Edge, session.meshSelection.value.selectMode)
		assertEquals(12, session.meshSelection.value.elementsOf(ElementOwner.Deformer(cage)).size, "every grid line of a 2 x 2 lattice")

		session.setMeshSelectMode(MeshSelectMode.Face)
		assertEquals(4, session.meshSelection.value.elementsOf(ElementOwner.Deformer(cage)).size, "every cell")

		session.invertMeshSelection()
		assertTrue(session.meshSelection.value.elementsOf(ElementOwner.Deformer(cage)).isEmpty())
	}

	@Test
	fun theObjectSelectAllIsMuteInPose() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)))
		val selectionBefore = session.selection.value

		session.selectAllObjects()
		session.invertObjectSelection()

		assertEquals(selectionBefore, session.selection.value)
	}

	@Test
	fun gArmsTheMeshOperatorOverSelectedPoints() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)))

		session.beginMeshOperator(MeshOperatorKind.Grab, "area")
		assertNull(session.activeMeshOperator.value, "nothing selected, nothing to move")

		session.selectAllMeshElements()
		session.beginMeshOperator(MeshOperatorKind.Grab, "area")
		assertEquals(MeshOperatorKind.Grab, assertNotNull(session.activeMeshOperator.value).kind)
	}

	@Test
	fun theSwitchMovesToAnotherPosedWarpAndRefusesArt() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)))

		session.switchEditOwner(ElementOwner.Deformer(second))
		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(second)), session.meshSelection.value.owners)
		assertEquals(SelectionTarget.Deformer(second), session.selection.value.active, "the object selection follows")

		val before = session.meshSelection.value
		session.switchEditOwner(ElementOwner.Drawable(art))
		session.switchEditOwner(ElementOwner.Deformer(offRest))
		assertEquals(before, session.meshSelection.value, "art is not posed, and the pose hides offRest")
	}

	@Test
	fun theSwitchTakesALatticeInEditModeToo() {
		val session = EditorSession(model())
		session.setSelection(Selection(setOf(SelectionTarget.Drawable(art)), SelectionTarget.Drawable(art)))
		session.setMode(EditorMode.Edit)

		session.switchEditOwner(ElementOwner.Deformer(cage))

		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(cage)), session.meshSelection.value.owners)
	}

	@Test
	fun aWarpsElementsRideATabBetweenEditAndPose() {
		val session = EditorSession(model())
		session.setSelection(Selection(setOf(SelectionTarget.Deformer(cage)), SelectionTarget.Deformer(cage)))
		session.setMode(EditorMode.Edit)
		val point = MeshElement.Vertex(4)
		session.setMeshSelection(session.meshSelection.value.copy(elementsByOwner = mapOf(ElementOwner.Deformer(cage) to setOf(point))))

		session.setMode(EditorMode.Pose)
		assertEquals(setOf<MeshElement>(point), session.meshSelection.value.elementsOf(ElementOwner.Deformer(cage)))

		session.toggleEditMode()
		assertEquals(EditorMode.Edit, session.mode.value)
		assertEquals(setOf<MeshElement>(point), session.meshSelection.value.elementsOf(ElementOwner.Deformer(cage)))

		session.toggleEditMode()
		assertEquals(EditorMode.Pose, session.mode.value, "Tab returns to the mode Edit was entered from")
		assertFalse(session.meshSelection.value.isEmpty)
	}
}