package org.umamo.ui.workspace.shell

import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import org.umamo.ui.viewport.gizmo.assertNothingRan
import org.umamo.ui.viewport.gizmo.countingGizmoRuns
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter
import kotlin.test.Test
import kotlin.test.assertTrue

/** The shell's package, whose composables these cases count. */
private const val SHELL_PACKAGE_PREFIX = "org.umamo.ui.workspace.shell."

/** The strip fixture's class, whose own composable lambdas are not counted. */
private const val FIXTURE_CLASS = "WorkspaceTabsFixture"

/**
 * Pins what a tab drag recomposes.  The drag offset is read where the held tab is drawn and by the derived
 * reorder preview, so a pointer move composes nothing until the predicted destination changes.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class WorkspaceTabsRecompositionTest {
	/**
	 * Runs [body] with the shell package's composable runs counted.
	 *
	 * @param Function body The case, handed the counter.
	 */
	private fun countingStripRuns(body: ComposeUiTest.(ComposableRunCounter) -> Unit) = countingGizmoRuns(packagePrefix = SHELL_PACKAGE_PREFIX, fixtureFunction = FIXTURE_CLASS, body = body)

	/**
	 * Picks Alpha up: presses it, holds past the reorder gate, and nudges it, leaving the button down.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun pickUpAlpha(test: ComposeUiTest) {
		test.onNodeWithText("Alpha").performMouseInput {
			moveTo(center)
			press()
			advanceEventTime(150L)
			moveBy(Offset(2f, 0f))
		}
		test.waitForIdle()
	}

	/** The strip has to compose at all, or the absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesTheStripCompose() =
		countingStripRuns { counter ->
			WorkspaceTabsFixture().mount(this)

			assertTrue(counter.runsOf("WorkspaceTabs") >= 1)
		}

	/** A drag move that stays short of every neighbor's center runs nothing. */
	@Test
	fun aMoveThatCrossesNoNeighborRunsNothing() =
		countingStripRuns { counter ->
			WorkspaceTabsFixture().mount(this)
			pickUpAlpha(this)
			counter.reset()

			onRoot().performMouseInput {
				moveBy(Offset(3f, 0f))
				moveBy(Offset(3f, 0f))
			}
			waitForIdle()

			assertNothingRan(counter, "a move inside the held tab's own slot")
			onRoot().performMouseInput { release() }
		}

	/** A drag move that crosses a neighbor's center runs the strip, which slides that neighbor aside. */
	@Test
	fun aMoveThatCrossesANeighborRunsTheStrip() =
		countingStripRuns { counter ->
			WorkspaceTabsFixture().mount(this)
			val alphaCenter = onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.center.x
			val bravoCenter = onNodeWithText("Bravo").fetchSemanticsNode().boundsInRoot.center.x
			pickUpAlpha(this)
			counter.reset()

			onRoot().performMouseInput { moveBy(Offset(bravoCenter - alphaCenter, 0f)) }
			waitForIdle()

			assertTrue(counter.namedRuns().values.sum() + counter.lambdaRuns() > 0, "the crossing composed the strip")
			onRoot().performMouseInput { release() }
		}
}