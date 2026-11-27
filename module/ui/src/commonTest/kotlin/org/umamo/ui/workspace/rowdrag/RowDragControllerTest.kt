package org.umamo.ui.workspace.rowdrag

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared row drag state's hit-test: the target is whatever visible row the pointer's Y is over
 * except the dragged row, a shared edge belongs to the lower row only, empty space is no target, a
 * fresh grab starts with none, and cancelling clears everything at once.  And the part each row plays
 * in a drag, which is what a row draws its drag feedback from.
 */
class RowDragControllerTest {
	private fun controllerOverThreeRows(): RowDragController<String> =
		RowDragController<String>().apply {
			reportBounds("a", Rect(0f, 0f, 100f, 20f))
			reportBounds("b", Rect(0f, 20f, 100f, 40f))
			reportBounds("c", Rect(0f, 40f, 100f, 60f))
		}

	@Test
	fun theTargetIsTheRowUnderThePointerNeverTheDraggedOne() {
		val controller = controllerOverThreeRows()
		assertNull(controller.dropTargetKey, "nothing is dragged")
		controller.start("a", "payload-a", windowX = 10f, windowY = 5f)
		assertTrue(controller.isDragging)
		assertEquals("payload-a", controller.draggedPayload)
		assertNull(controller.dropTargetKey, "a fresh grab sits on its own row, which is no target")
		controller.drag(10f, 30f)
		assertEquals("b", controller.dropTargetKey)
		assertEquals(0.5f, controller.dropTargetFraction)
		controller.drag(10f, 5f)
		assertNull(controller.dropTargetKey, "back over the dragged row is no target again")
		controller.drag(10f, 95f)
		assertNull(controller.dropTargetKey, "empty space below every row")
		assertNull(controller.dropTargetFraction)
	}

	@Test
	fun aSharedEdgeBelongsToTheLowerRowOnly() {
		val controller = controllerOverThreeRows()
		controller.start("a", "payload-a", 10f, 5f)
		controller.drag(10f, 40f)
		assertEquals("c", controller.dropTargetKey, "y == 40 is c's top edge, not b's bottom")
		assertEquals(0f, controller.dropTargetFraction)
		controller.drag(10f, 39.5f)
		assertEquals("b", controller.dropTargetKey)
	}

	@Test
	fun cancelClearsTheDragAndScrolledOffRowsStopBeingTargets() {
		val controller = controllerOverThreeRows()
		controller.start("a", "payload-a", 10f, 5f)
		controller.drag(10f, 50f)
		assertEquals("c", controller.dropTargetKey)
		controller.clearBounds("c")
		assertNull(controller.dropTargetKey, "a row that left composition cannot take a drop")
		controller.drag(10f, 30f)
		controller.cancel()
		assertFalse(controller.isDragging)
		assertNull(controller.draggingKey)
		assertNull(controller.draggedPayload)
		assertNull(controller.dropTargetKey, "no drag, no target, however the pointer moves")
		controller.drag(10f, 30f)
		assertNull(controller.dropTargetKey)
	}

	/** Halves a row into an upper and a lower band, and refuses the payload named "refused". */
	private fun halves(dragged: String, fraction: Float): String? =
		when {
			dragged == "refused" -> null
			fraction < 0.5f -> "upper"
			else -> "lower"
		}

	@Test
	fun theTargetAndItsFractionAreOneValue() {
		val controller = controllerOverThreeRows()
		assertNull(controller.dropTarget, "nothing is dragged")
		controller.start("a", "payload-a", 10f, 5f)
		assertNull(controller.dropTarget, "a fresh grab sits on its own row")
		controller.drag(10f, 25f)
		assertEquals(RowDropTarget("b", 0.25f), controller.dropTarget)
		controller.drag(10f, 95f)
		assertNull(controller.dropTarget, "empty space below every row")
	}

	@Test
	fun aRowWithNoHeightTakesNoDrop() {
		val controller = controllerOverThreeRows()
		controller.reportBounds("flat", Rect(0f, 60f, 100f, 60f))
		controller.reportBounds("under", Rect(0f, 60f, 100f, 80f))
		controller.start("a", "payload-a", 10f, 5f)
		controller.drag(10f, 60f)
		assertEquals("under", controller.dropTargetKey, "a row with no height holds no point at all")
	}

	@Test
	fun withNoDragEveryRowIsIdle() {
		val controller = controllerOverThreeRows()
		controller.drag(10f, 30f)
		assertEquals(RowDragRole.Idle, controller.roleOf("a", ::halves))
		assertEquals(RowDragRole.Idle, controller.roleOf("b", ::halves))
	}

	@Test
	fun theRowInHandIsDraggedAndTheRowUnderThePointerIsTheTarget() {
		val controller = controllerOverThreeRows()
		controller.start("a", "payload-a", 10f, 5f)
		controller.drag(10f, 25f)
		assertEquals(RowDragRole.Dragged, controller.roleOf("a", ::halves))
		assertEquals(RowDragRole.Target("upper"), controller.roleOf("b", ::halves))
		assertEquals(RowDragRole.Idle, controller.roleOf("c", ::halves))
		assertEquals("upper", controller.roleOf("b", ::halves).bandOrNull)
		assertNull(controller.roleOf("a", ::halves).bandOrNull)
		assertNull(controller.roleOf("c", ::halves).bandOrNull)
	}

	@Test
	fun aRolesBandFollowsThePointerDownTheRow() {
		val controller = controllerOverThreeRows()
		controller.start("a", "payload-a", 10f, 5f)
		controller.drag(10f, 22f)
		val near = controller.roleOf("b", ::halves)
		controller.drag(10f, 28f)
		assertEquals(near, controller.roleOf("b", ::halves), "a move inside one band changes no row's part")
		controller.drag(10f, 32f)
		assertEquals(RowDragRole.Target("lower"), controller.roleOf("b", ::halves))
	}

	@Test
	fun aRowTheDragMayNotLandOnPlaysNoPart() {
		val controller = controllerOverThreeRows()
		controller.start("a", "refused", 10f, 5f)
		controller.drag(10f, 30f)
		assertEquals("b", controller.dropTargetKey, "the pointer is over the row all the same")
		assertEquals(RowDragRole.Idle, controller.roleOf("b", ::halves))
		assertEquals(RowDragRole.Dragged, controller.roleOf("a", ::halves), "the row in hand is in hand whatever it may land on")
	}

	@Test
	fun theDraggedRowIsNeverItsOwnTarget() {
		val controller = controllerOverThreeRows()
		controller.start("b", "payload-b", 10f, 30f)
		controller.drag(10f, 35f)
		assertEquals(RowDragRole.Dragged, controller.roleOf("b", ::halves))
		assertNull(controller.dropTarget)
	}

	@Test
	fun aCancelledDragLeavesEveryRowIdle() {
		val controller = controllerOverThreeRows()
		controller.start("a", "payload-a", 10f, 5f)
		controller.drag(10f, 30f)
		controller.cancel()
		assertEquals(RowDragRole.Idle, controller.roleOf("a", ::halves))
		assertEquals(RowDragRole.Idle, controller.roleOf("b", ::halves))
	}

	@Test
	fun thePointerIsReportedInWindowCoordinates() {
		val controller = controllerOverThreeRows()
		controller.start("a", "payload-a", 12f, 5f)
		controller.drag(14f, 33f)
		assertEquals(Offset(14f, 33f), controller.pointerInWindow)
	}
}