package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.SelectionTarget
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.ActiveMeshInfoLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the active-object chip over an active warp: the 2D viewport shows "Part | Warp" with its parent deformer
 * below in Object and Pose mode, and a surface that shows no deformers (the UV editor) shows nothing.
 */
@OptIn(ExperimentalTestApi::class)
class ActiveDeformerChipTest {
	@Test
	fun anActiveWarpHasItsChipWhereDeformersShow() =
		runComposeUiTest {
			val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_HEAD)))
			setContent {
				UmamoTheme {
					ActiveMeshInfoLabel(session = session, showsDeformers = true)
					ActiveMeshInfoLabel(session = session)
				}
			}
			waitForIdle()

			assertEquals(1, onAllNodesWithText("Body | head", useUnmergedTree = true).fetchSemanticsNodes().size, "the viewport's chip, and not the UV editor's")
			assertEquals(1, onAllNodesWithText("body", useUnmergedTree = true).fetchSemanticsNodes().size, "with the parent deformer below")

			session.togglePoseMode()
			waitForIdle()
			assertEquals(1, onAllNodesWithText("Body | head", useUnmergedTree = true).fetchSemanticsNodes().size, "Pose mode shows it too")
		}
}