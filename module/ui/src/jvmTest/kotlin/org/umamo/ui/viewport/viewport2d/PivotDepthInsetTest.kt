package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.SelectionTarget
import org.umamo.edit.TransformAxisConstraint
import org.umamo.edit.selectAllMeshElements
import org.umamo.edit.transform.beginMeshOperator
import org.umamo.render.ViewportCamera
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The depth turn's bottom-right inset shows only in the area whose Pose-mode Rotate turns in depth about a locked
 * axis, reads the pivot's depth, and follows a push without the gesture restarting.
 */
@OptIn(ExperimentalTestApi::class)
class PivotDepthInsetTest {
	@Test
	fun theInsetShowsInTheTurningAreaOnlyWhileAnAxisIsLocked() =
		runComposeUiTest {
			val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
			session.togglePoseMode()
			assertEquals(EditorMode.Pose, session.mode.value)
			session.selectAllMeshElements()
			session.setPivotDepth(30f)
			setContent {
				UmamoTheme {
					ViewportHudOverlay(areaId = TURNING_AREA, session = session, liveCamera = ViewportCamera(0f, 0f, 1f))
					ViewportHudOverlay(areaId = OTHER_AREA, session = session, liveCamera = ViewportCamera(0f, 0f, 1f))
				}
			}

			session.beginMeshOperator(MeshOperatorKind.Rotate, TURNING_AREA)
			waitForIdle()
			assertEquals(0, insetsReading("Depth 30 px"), "the in-plane turn shows no inset")

			session.toggleAxisConstraint(TransformAxisConstraint.AxisZ)
			waitForIdle()
			assertEquals(1, insetsReading("Depth 30 px"), "a locked turn shows it in its own area alone")

			session.setPivotDepth(46f)
			waitForIdle()
			assertEquals(1, insetsReading("Depth 46 px"), "a push updates the readout")

			session.clearMeshOperator()
			waitForIdle()
			assertEquals(0, insetsReading("Depth 46 px"), "the confirm or cancel takes it away")
		}

	/**
	 * How many depth insets read [readout].
	 *
	 * @param String readout The readout's text.
	 * @return Int The count.
	 */
	private fun androidx.compose.ui.test.ComposeUiTest.insetsReading(readout: String): Int =
		onAllNodesWithContentDescription(readout, useUnmergedTree = true).fetchSemanticsNodes().size

	private companion object {
		/** The area the turn latched in. */
		const val TURNING_AREA = "turning"

		/** Another viewport over the same session. */
		const val OTHER_AREA = "other"
	}
}