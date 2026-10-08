package org.umamo.ui.viewport.viewport2d

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorMode
import org.umamo.edit.SnapKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the Object overlay's request routing ([collectObjectGizmoRequests]): a snap runs in the area it
 * names and only in Object mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObjectGizmoRequestsTest {
	/** A snap naming this area moves the cursor onto the selected drawable's centroid. */
	@Test
	fun aSnapRunsInItsOwnArea() =
		runTest {
			val session = gizmoObjectSession()
			backgroundScope.launch { collectObjectGizmoRequests(LEFT_AREA_ID, session, overlays = null) }
			runCurrent()

			session.requestSnap(SnapKind.CursorToSelected, "right")
			runCurrent()
			assertNull(session.cursor2d.value, "another area's request is not this one's")

			session.requestSnap(SnapKind.CursorToSelected, LEFT_AREA_ID)
			runCurrent()
			val cursor = assertNotNull(session.cursor2d.value)
			assertEquals(10f, cursor.worldX)
			assertEquals(-10f, cursor.worldZ)
		}

	/** Outside Object mode the collector leaves a snap to the Edit overlay. */
	@Test
	fun aSnapOutsideObjectModeIsNotThisCollectors() =
		runTest {
			val session = gizmoEditSession()
			assertEquals(EditorMode.Edit, session.mode.value)
			backgroundScope.launch { collectObjectGizmoRequests(LEFT_AREA_ID, session, overlays = null) }
			runCurrent()

			session.requestSnap(SnapKind.CursorToSelected, LEFT_AREA_ID)
			runCurrent()

			assertNull(session.cursor2d.value)
		}

	private companion object {
		const val LEFT_AREA_ID = "left"
	}
}