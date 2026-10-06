package org.umamo.ui.viewport.uv

import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformAxisConstraint
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.assertNothingRan
import org.umamo.ui.viewport.gizmo.moveIn
import org.umamo.ui.viewport.gizmo.pressIn
import org.umamo.ui.viewport.gizmo.releaseIn
import org.umamo.ui.viewport.gizmo.scrollIn
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins what recomposes the UV editor's gizmo overlays.  The pointer, the live preview, the radius, and the
 * modal HUD's inputs are read where they are drawn, not where the overlay composes, so hovering, driving a
 * gesture, and changing the radius, the falloff, or the axis mid-gesture redraw the chrome and run no
 * composable; the wireframe is the render service's, so a circle stamp or a selection change runs none
 * either.  These are the overlays' own costs: the host follows the render sync's preview by design,
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
	 * Painting with the circle brush runs nothing: the live stroke goes to the host's per-area state, which
	 * only the scene publish reads, and the brush circle is chrome drawn from the pointer.
	 */
	@Test
	fun aCircleStampRunsNothing() =
		countingUvGizmoRuns { counter ->
			val fixture = mountUvGizmoOverlays(uvEditSession())
			fixture.session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			pressIn(LEFT_AREA, uvRigScreenOf(120f, 120f))
			waitForIdle()
			counter.reset()

			moveIn(LEFT_AREA, listOf(uvRigScreenOf(110f, 120f), uvRigScreenOf(100f, 120f)))

			assertTrue(fixture.circleStrokeByArea.getValue(LEFT_AREA).value != null, "the stamps did paint")
			assertNothingRan(counter, "a circle stroke's stamps")
			releaseIn(LEFT_AREA)
		}

	/** A mesh selection change runs nothing in the overlays: what lights up is the published wireframe's. */
	@Test
	fun aMeshSelectionChangeRunsNothing() =
		countingUvGizmoRuns { counter ->
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			counter.reset()

			session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, UV_RIG_QUAD, MeshElement.Vertex(1)))
			waitForIdle()

			assertNothingRan(counter, "a mesh selection change")
		}
}