package org.umamo.ui.workspace.rowdrag

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared row drag state's hit-test: the target is whatever visible row the pointer's Y is over
 * except the dragged row, a shared edge belongs to the lower row only, empty space is no target, a
 * fresh grab starts with none, and cancelling clears everything at once.
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
}