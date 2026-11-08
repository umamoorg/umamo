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
 * The depth rotation's bottom-right inset shows only in the area whose Pose-mode Rotate rotates in depth about a locked
 * axis, reads the pivot's depth, and follows a push without the gesture restarting.
 */
@OptIn(ExperimentalTestApi::class)
class PivotDepthInsetTest {
	/** The depth inset shows only in the rotating area, and only while an axis is locked. */
	@Test
	fun theInsetShowsInTheRotatingAreaOnlyWhileAnAxisIsLocked() =
		runComposeUiTest {
			val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
			session.togglePoseMode()
			assertEquals(EditorMode.Pose, session.mode.value)
			session.selectAllMeshElements()
			session.setPivotDepth(30f)
			setContent {
				UmamoTheme {
					ViewportHudOverlay(areaId = ROTATING_AREA, session = session, liveCamera = ViewportCamera(0f, 0f, 1f))
					ViewportHudOverlay(areaId = OTHER_AREA, session = session, liveCamera = ViewportCamera(0f, 0f, 1f))
				}
			}

			session.beginMeshOperator(MeshOperatorKind.Rotate, ROTATING_AREA)
			waitForIdle()
			assertEquals(0, insetsReading("Depth 30 px · Flat"), "the in-plane turn shows no inset")

			session.toggleAxisConstraint(TransformAxisConstraint.AxisZ)
			waitForIdle()
			assertEquals(1, insetsReading("Depth 30 px · Flat"), "a locked turn shows it in its own area alone")

			session.setPivotDepth(46f)
			waitForIdle()
			assertEquals(1, insetsReading("Depth 46 px · Flat"), "a push updates the readout")

			session.cycleDepthSurface(announce = false)
			waitForIdle()
			assertEquals(1, insetsReading("Depth 46 px · Cylinder"), "and so does T's surface cycle")

			session.clearMeshOperator()
			waitForIdle()
			assertEquals(0, insetsReading("Depth 46 px · Cylinder"), "the confirm or cancel takes it away")
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
		/** The area the rotation latched in. */
		const val ROTATING_AREA = "rotating"

		/** Another viewport over the same session. */
		const val OTHER_AREA = "other"
	}
}