package org.umamo.ui.viewport.uv

import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TransformAxisConstraint
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.assertNothingRan
import org.umamo.ui.viewport.gizmo.moveIn
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Pins what recomposes the UV editor's Object-mode overlay.  The pointer, the placement's live evaluation,
 * and the modal HUD's inputs are read where they are drawn, not where the overlay composes, and the islands
 * and their selection styling are the render service's, so hovering, driving a placement, changing the
 * object selection, and changing the axis mid-placement run no composable.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class UvObjectGizmoRecompositionTest {
	/** Hovering over an idle Object overlay moves only what the chrome draws. */
	@Test
	fun aHoverRunsNothing() =
		countingUvGizmoRuns { counter ->
			mountUvGizmoOverlays(uvObjectSession(model = uvRigPlacedModel()), uvRigPlacementSurface())
			counter.reset()

			moveIn(LEFT_AREA, listOf(Offset(200f, 150f), Offset(210f, 150f), Offset(220f, 160f)))

			assertNothingRan(counter, "a hover")
		}

	/** Driving a placement updates the host's readout and placement scene and runs no composable. */
	@Test
	fun aPlacementDriveRunsNothing() =
		countingUvGizmoRuns { counter ->
			val fixture = mountUvGizmoOverlays(uvObjectSession(model = uvRigPlacedModel()), uvRigPlacementSurface())
			moveIn(LEFT_AREA, listOf(Offset(200f, 150f)))
			fixture.session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			awaitPlacementCapture(fixture, LEFT_AREA, Offset(200f, 150f))
			counter.reset()

			moveIn(LEFT_AREA, listOf(Offset(210f, 150f), Offset(220f, 155f), Offset(240f, 150f)))

			assertNotNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value, "the moves did drive")
			assertNothingRan(counter, "a placement drive")
		}

	/** A change of the object selection runs nothing in the overlay: the islands' styles are the published scene's. */
	@Test
	fun anObjectSelectionChangeRunsNothing() =
		countingUvGizmoRuns { counter ->
			val fixture = mountUvGizmoOverlays(uvObjectSession(model = uvRigPlacedModel()), uvRigPlacementSurface())
			counter.reset()

			fixture.session.setSelection(Selection(setOf(SelectionTarget.Drawable(UV_RIG_OTHER)), SelectionTarget.Drawable(UV_RIG_OTHER)))
			waitForIdle()

			assertNothingRan(counter, "a selection change")
		}

	/** An axis change mid-placement redraws the HUD and runs no composable. */
	@Test
	fun anAxisChangeMidPlacementRunsNothing() =
		countingUvGizmoRuns { counter ->
			val fixture = mountUvGizmoOverlays(uvObjectSession(model = uvRigPlacedModel()), uvRigPlacementSurface())
			moveIn(LEFT_AREA, listOf(Offset(200f, 150f)))
			fixture.session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			awaitPlacementCapture(fixture, LEFT_AREA, Offset(200f, 150f))
			counter.reset()

			fixture.session.toggleAxisConstraint(TransformAxisConstraint.AxisX)
			waitForIdle()

			assertNothingRan(counter, "an axis change")
		}
}