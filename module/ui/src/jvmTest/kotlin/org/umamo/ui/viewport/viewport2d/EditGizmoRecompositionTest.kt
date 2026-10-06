package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformAxisConstraint
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.assertNothingRan
import org.umamo.ui.viewport.gizmo.countingGizmoRuns
import org.umamo.ui.viewport.gizmo.moveIn
import org.umamo.ui.viewport.gizmo.pressIn
import org.umamo.ui.viewport.gizmo.releaseIn
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins what recomposes the viewport gizmo overlays.  The pointer, the live preview, and the modal HUD's
 * inputs are read where they are drawn, not where the overlay composes, so hovering, driving a gesture,
 * and changing the proportional radius or the axis mid-gesture redraw the chrome and run no composable.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class EditGizmoRecompositionTest {
	/** The counter has to see the overlays compose at all, or every absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesTheOverlaysCompose() =
		countingGizmoRuns { counter ->
			mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))

			assertTrue(counter.runsOf("ViewportEditGizmoOverlay") >= 2, "one per area")
			assertTrue(counter.runsOf("ViewportObjectGizmoOverlay") >= 2, "one per area")
		}

	/** Hovering over an idle overlay moves only what the chrome draws. */
	@Test
	fun aHoverRunsNothing() =
		countingGizmoRuns { counter ->
			mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			counter.reset()

			moveIn(LEFT_AREA, listOf(Offset(200f, 150f), Offset(210f, 150f), Offset(220f, 160f)))

			assertNothingRan(counter, "a hover")
		}

	/** Driving a modal transform previews through the render service and runs no composable. */
	@Test
	fun aModalDriveRunsNothing() =
		countingGizmoRuns { counter ->
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			moveIn(LEFT_AREA, listOf(Offset(200f, 150f)))
			fixture.session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			counter.reset()

			moveIn(LEFT_AREA, listOf(Offset(210f, 150f), Offset(220f, 155f), Offset(240f, 150f)))

			assertTrue(fixture.service.pushedModels.isNotEmpty(), "the moves did drive")
			assertNothingRan(counter, "a modal drive")
		}

	/** A proportional radius or axis change mid-gesture redraws the HUD and runs no composable. */
	@Test
	fun aHudChangeMidGestureRunsNothing() =
		countingGizmoRuns { counter ->
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			moveIn(LEFT_AREA, listOf(Offset(200f, 150f)))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			counter.reset()

			session.setProportionalRadius(20f)
			waitForIdle()
			session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
			waitForIdle()

			assertNothingRan(counter, "a radius and an axis change")
		}

	/**
	 * Painting with the circle brush publishes the stroke to the session for the renderer's overlay and
	 * runs no composable: nothing that composes reads the stroke.
	 */
	@Test
	fun aCircleStampRunsNothing() =
		countingGizmoRuns { counter ->
			val fixture = mountGizmoOverlays(gizmoEditSession())
			fixture.session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			pressIn(LEFT_AREA, rigScreenOf(20f, -20f))
			waitForIdle()
			counter.reset()

			moveIn(LEFT_AREA, listOf(rigScreenOf(10f, -20f), rigScreenOf(0f, -20f), rigScreenOf(0f, -10f)))

			assertNothingRan(counter, "a circle stroke's stamps")
			releaseIn(LEFT_AREA)
		}
}