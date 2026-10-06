package org.umamo.ui.viewport.uv

import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
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
import org.umamo.ui.viewport.gizmo.scrollIn
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The UV overlays' package, whose composables these cases count. */
private const val UV_PACKAGE_PREFIX = "org.umamo.ui.viewport.uv."

/** The UV fixture's function, whose own body and lambdas are not counted. */
private const val UV_FIXTURE_FUNCTION = "mountUvGizmoOverlays"

/**
 * Runs [body] with the UV overlays' composable runs counted.
 *
 * @param Function body The case, handed the counter.
 */
@OptIn(ExperimentalTestApi::class)
private fun countingUvGizmoRuns(body: ComposeUiTest.(ComposableRunCounter) -> Unit) = countingGizmoRuns(UV_PACKAGE_PREFIX, UV_FIXTURE_FUNCTION, body)

/**
 * Pins what recomposes the UV editor's gizmo overlays.  The pointer, the live preview, the radius, and the
 * modal HUD's inputs are read where they are drawn, not where the overlay composes, so hovering, driving a
 * gesture, and changing the radius, the falloff, or the axis mid-gesture redraw the chrome and run no
 * composable.  These are the overlays' own costs: the host follows the render sync's preview by design,
 * so in the app a drive recomposes the space around them.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class UvEditGizmoRecompositionTest {
	/** The counter has to see the overlays compose at all, or every absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesTheOverlaysCompose() =
		countingUvGizmoRuns { counter ->
			mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))

			assertTrue(counter.runsOf("UvEditGizmoOverlay") >= 2, "one per area")
			assertTrue(counter.runsOf("UvObjectGizmoOverlay") >= 2, "one per area")
		}

	/** Hovering over an idle overlay moves only what the chrome draws. */
	@Test
	fun aHoverRunsNothing() =
		countingUvGizmoRuns { counter ->
			mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			counter.reset()

			moveIn(LEFT_AREA, listOf(Offset(200f, 150f), Offset(210f, 150f), Offset(220f, 160f)))

			assertNothingRan(counter, "a hover")
		}

	/** Driving a modal transform previews through the render sync and runs no composable. */
	@Test
	fun aModalDriveRunsNothing() =
		countingUvGizmoRuns { counter ->
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			moveIn(LEFT_AREA, listOf(Offset(200f, 150f)))
			fixture.session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			counter.reset()

			moveIn(LEFT_AREA, listOf(Offset(210f, 150f), Offset(220f, 155f), Offset(240f, 150f)))

			assertTrue(fixture.renderSync.previewed.isNotEmpty(), "the moves did drive")
			assertNothingRan(counter, "a modal drive")
		}

	/** A wheel resize, a falloff change, and an axis change mid-gesture redraw the HUD and run no composable. */
	@Test
	fun aHudChangeMidGestureRunsNothing() =
		countingUvGizmoRuns { counter ->
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			moveIn(LEFT_AREA, listOf(Offset(200f, 150f)))
			session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(Offset(220f, 150f)))
			counter.reset()

			scrollIn(LEFT_AREA, -1f)
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Sharp, 10f))
			waitForIdle()
			session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
			waitForIdle()

			assertNothingRan(counter, "a radius, a falloff, and an axis change")
		}

	/**
	 * Painting with the circle brush recomposes the Edit overlay alone: it derives the highlighted domain
	 * from the live stroke while it composes, so each stamp runs it once per area that shows the stroke.
	 */
	@Test
	fun aCircleStampRunsOnlyTheEditOverlay() =
		countingUvGizmoRuns { counter ->
			val fixture = mountUvGizmoOverlays(uvEditSession())
			fixture.session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			pressIn(LEFT_AREA, uvRigScreenOf(120f, 120f))
			waitForIdle()
			counter.reset()

			moveIn(LEFT_AREA, listOf(uvRigScreenOf(110f, 120f), uvRigScreenOf(100f, 120f)))

			assertEquals(setOf("UvEditGizmoOverlay"), counter.namedRuns().keys, "a circle stroke's stamps")
			releaseIn(LEFT_AREA)
		}
}