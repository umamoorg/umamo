package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.parameter.createParameter
import org.umamo.edit.parameter.renameParameter
import org.umamo.runtime.model.ParameterKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Pins when the panel's pose state is kept and when it is replaced.  Every row holds the state, so a
 * replacement recomposes them all and a stale one writes through a lock that has since changed.
 */
@OptIn(ExperimentalTestApi::class)
class ParameterPoseStateTest {
	/**
	 * Composes the pose state alone and collects each distinct instance it hands back.
	 *
	 * @param ParametersPanelHarness harness The session and seam the state is built over.
	 * @return List<ParameterPoseState> The instances, in the order composition produced them.
	 */
	private fun ComposeUiTest.collectPoseStates(harness: ParametersPanelHarness): List<ParameterPoseState> {
		val seen = mutableListOf<ParameterPoseState>()
		setContent {
			val puppet by harness.session.model.collectAsState()
			val state = rememberParameterPoseState(puppet, harness.liveParamsHandle, harness.session)
			SideEffect {
				if (seen.lastOrNull() !== state) {
					seen += state
				}
			}
		}
		waitForIdle()
		return seen
	}

	/** The state opens on the live pose, not on the defaults. */
	@Test
	fun theStateOpensOnTheLivePose() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			val seen = collectPoseStates(harness)

			assertEquals(2f, runOnIdle { seen.single().valueOf(parameterOf(harness, PanelIds.bodyX)) })
		}

	/** An edit that keeps the set of parameters keeps the state, and with it every row's callbacks. */
	@Test
	fun anEditThatKeepsTheParametersKeepsTheState() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			val seen = collectPoseStates(harness)
			val modelBefore = harness.session.model.value

			runOnIdle { harness.session.renameParameter(PanelIds.bodyX, "Sway") }
			waitForIdle()

			assertNotSame(modelBefore, harness.session.model.value, "the rename must really have published a model")
			assertEquals(1, seen.size)
		}

	/** A change to the set of parameters replaces the state, which opens on the live pose again. */
	@Test
	fun aNewParameterReplacesTheState() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			val seen = collectPoseStates(harness)

			runOnIdle { harness.session.createParameter("Extra", ParameterKind.NORMAL) }
			waitForIdle()

			assertEquals(2, seen.size)
			assertEquals(2f, runOnIdle { seen.last().valueOf(parameterOf(harness, PanelIds.bodyX)) })
		}

	/**
	 * A mode change replaces the state with one that holds the new lock.  Locked, it shows the rest pose, as
	 * the viewport does; the values it holds carry across, and show again once the lock is gone.
	 */
	@Test
	fun aModeChangeReplacesTheWriterAndKeepsTheValues() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			val seen = collectPoseStates(harness)
			runOnIdle { seen.single().commitValue(PanelIds.bodyX, 5f) }
			waitForIdle()
			assertEquals(5f, harness.committed(PanelIds.bodyX))

			runOnIdle { harness.enterEditMode() }
			waitForIdle()
			assertEquals(2, seen.size, "the lock is a value of the state, so a new lock is a new state")
			val cursorLocked = harness.historyCursor
			runOnIdle { seen.last().commitValue(PanelIds.bodyX, 7f) }
			waitForIdle()
			assertEquals(0f, runOnIdle { seen.last().valueOf(parameterOf(harness, PanelIds.bodyX)) }, "a locked state shows the rest pose")
			assertEquals(5f, harness.committed(PanelIds.bodyX))
			assertEquals(cursorLocked, harness.historyCursor)

			runOnIdle { harness.leaveEditMode() }
			waitForIdle()
			assertEquals(3, seen.size)
			assertEquals(5f, runOnIdle { seen.last().valueOf(parameterOf(harness, PanelIds.bodyX)) }, "the value carried across, and the locked write left it")
			runOnIdle { seen.last().commitValue(PanelIds.bodyX, 7f) }
			waitForIdle()
			assertEquals(7f, harness.committed(PanelIds.bodyX))
		}

	/** A recomposition that changes nothing the state is keyed on hands back the same instance. */
	@Test
	fun aCommitKeepsTheState() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			val seen = collectPoseStates(harness)
			val first = seen.single()

			runOnIdle { first.commitValue(PanelIds.bodyX, 5f) }
			waitForIdle()

			assertSame(first, seen.single(), "a commit publishes a pose, which recomposes the caller and must not rebuild the state")
		}
}