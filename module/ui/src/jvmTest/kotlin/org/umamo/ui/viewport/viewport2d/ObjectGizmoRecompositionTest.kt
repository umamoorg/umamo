package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.TransformAxisConstraint
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.assertNothingRan
import org.umamo.ui.viewport.gizmo.countingGizmoRuns
import org.umamo.ui.viewport.gizmo.dragIn
import org.umamo.ui.viewport.gizmo.moveIn
import org.umamo.ui.viewport.gizmo.pressIn
import org.umamo.ui.viewport.gizmo.releaseIn
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins what recomposes the Object-mode gizmo overlay.  The pointer, the rubber band, the modal HUD's
 * inputs, and a circle stroke's paint are read where they are drawn or by the session, not where the
 * overlay composes, so hovering, a box drag, a stroke, driving a transform, and an axis change mid-gesture
 * run no composable of the package.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class ObjectGizmoRecompositionTest {
	/** The Object overlay has to compose at all, or every absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesTheObjectOverlayCompose() =
		countingGizmoRuns { counter ->
			mountGizmoOverlays(gizmoObjectSession())

			assertTrue(counter.runsOf("ViewportObjectGizmoOverlay") >= 2, "one per area")
		}

	/** Hovering over the idle overlay runs nothing. */
	@Test
	fun aHoverRunsNothing() =
		countingGizmoRuns { counter ->
			mountGizmoOverlays(gizmoObjectSession())
			counter.reset()

			moveIn(LEFT_AREA, listOf(Offset(200f, 150f), Offset(210f, 150f), Offset(220f, 160f)))

			assertNothingRan(counter, "a hover")
		}

	/** An idle box drag, rubber band and all, runs nothing. */
	@Test
	fun aBoxDragRunsNothing() =
		countingGizmoRuns { counter ->
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			fixture.service.centroids[RIG_QUAD] = floatArrayOf(10f, -10f)
			counter.reset()

			dragIn(LEFT_AREA, Offset(180f, 130f), listOf(Offset(200f, 150f), Offset(220f, 170f)))

			assertNothingRan(counter, "a box drag")
		}

	/** A circle stroke after arming runs nothing: the paint reaches the tint through the session. */
	@Test
	fun aCircleStrokeRunsNothing() =
		countingGizmoRuns { counter ->
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			fixture.service.centroids[RIG_QUAD] = floatArrayOf(10f, -10f)
			fixture.session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			counter.reset()

			pressIn(LEFT_AREA, Offset(200f, 150f))
			moveIn(LEFT_AREA, listOf(Offset(210f, 150f), Offset(220f, 155f)))
			releaseIn(LEFT_AREA)

			assertNothingRan(counter, "a circle stroke")
		}

	/** Driving a transform, and an axis change in the middle of it, run nothing. */
	@Test
	fun aModalDriveAndAnAxisChangeRunNothing() =
		countingGizmoRuns { counter ->
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			moveIn(LEFT_AREA, listOf(Offset(200f, 150f)))
			fixture.session.beginObjectOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			counter.reset()

			moveIn(LEFT_AREA, listOf(Offset(210f, 150f), Offset(220f, 155f), Offset(240f, 150f)))
			fixture.session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
			waitForIdle()

			assertTrue(fixture.service.pushedModels.isNotEmpty(), "the moves did drive")
			assertNothingRan(counter, "a modal drive and an axis change")
		}
}