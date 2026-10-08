package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.SnapKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the Object-mode grid snap ([handleObjectSnapRequest]): a selected drawable's centroid rounds to the
 * step it is handed, the executing area's, and the whole drawable translates by that delta.  The rig's quad
 * spans world x 0..20 and z -20..0, so its centroid is world (10, -10); a parentless drawable's local y is
 * its world z negated.
 */
class ObjectSnapTest {
	/** With a 25-unit step the centroid rounds to the origin, and every vertex moves by the same (-10, +10). */
	@Test
	fun selectionToGridRoundsTheCentroidToTheAreasStep() {
		val session = gizmoObjectSession()

		handleObjectSnapRequest(session, SnapKind.SelectionToGrid, gridStep = 25f)

		assertEquals(listOf(-10f, -10f, 10f, -10f, 10f, 10f, -10f, 10f), rigPositionsOf(session, RIG_QUAD))
	}

	/** A finer step lands the centroid on the nearest 8-unit intersection, (8, -8), a (-2, +2) move. */
	@Test
	fun aFinerStepRoundsToItsNearestIntersection() {
		val session = gizmoObjectSession()

		handleObjectSnapRequest(session, SnapKind.SelectionToGrid, gridStep = 8f)

		assertEquals(listOf(-2f, -2f, 18f, -2f, 18f, 18f, -2f, 18f), rigPositionsOf(session, RIG_QUAD))
	}
}