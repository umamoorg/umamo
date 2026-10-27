package org.umamo.ui.sidebar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.edit.Cursor2d
import org.umamo.edit.EditorSession
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.kit.field.LocalScrubCancel
import org.umamo.ui.kit.field.ScrubCancelController
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.LocalViewportChrome
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.ViewportChromeState
import org.umamo.ui.workspace.commands.inMemorySettings
import org.umamo.ui.workspace.sidebar.SidebarDrawer
import org.umamo.ui.workspace.sidebar.SidebarState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The View tab's 2D Cursor panel: it reads the cursor from the world axes, an unplaced cursor reading the
 * origin; a typed value places it without an undo step; and a cancelled scrub leaves it unplaced again.
 */
@OptIn(ExperimentalTestApi::class)
class CursorPanelTest {
	/**
	 * An empty session whose world origin sits off the canvas origin, so a value read or written without the
	 * origin shift lands visibly wrong.
	 *
	 * @return EditorSession The session.
	 */
	private fun session(): EditorSession =
		EditorSession(
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
			).copy(worldOriginX = 300f, worldOriginZ = -200f),
		)

	/**
	 * Mounts an open sidebar on its View tab over [session], at density 1.
	 *
	 * @param EditorSession session The session the panel reads and writes.
	 * @param ScrubCancelController scrubCancel The shell's scrub seam, so a test can cancel the way Escape does.
	 */
	private fun ComposeUiTest.mountViewTab(session: EditorSession, scrubCancel: ScrubCancelController = ScrubCancelController()) {
		val settings = inMemorySettings()
		val sidebarRegistry = SidebarRegistry().also { registry -> registerBuiltInSidebar(registry) }
		val sidebarState = SidebarState().also { state -> state.activeTabId = VIEW_SIDEBAR_TAB_ID }
		setContent {
			UmamoTheme {
				val puppet by session.model.collectAsState()
				CompositionLocalProvider(
					LocalDensity provides Density(1f),
					LocalSettings provides settings,
					LocalCommands provides CommandRegistry(),
					LocalKeymap provides defaultKeymap(),
					LocalSidebarRegistry provides sidebarRegistry,
					LocalViewportChrome provides ViewportChromeState(showSidebar = true),
					LocalScrubCancel provides scrubCancel,
					LocalPuppet provides puppet,
					LocalEditorSession provides session,
				) {
					Box(modifier = Modifier.size(420.dp, 500.dp)) {
						SidebarDrawer(
							areaId = "area-1",
							space = SpaceKind.Viewport2D,
							state = sidebarState,
							passPointerThrough = false,
							modifier = Modifier.align(Alignment.CenterEnd),
						)
					}
				}
			}
		}
		waitForIdle()
	}

	/** An unplaced cursor reads the world origin, both axes zero. */
	@Test
	fun anUnplacedCursorReadsTheOrigin() =
		runComposeUiTest {
			mountViewTab(session())
			onAllNodesWithText(ORIGIN).assertCountEquals(2)
		}

	/** A typed X places the cursor that far right of the world axes, leaving Z on them, with no undo step. */
	@Test
	fun aTypedValuePlacesTheCursorWithoutAnUndoStep() =
		runComposeUiTest {
			val session = session()
			val stepsBefore = session.historyView.value.steps.size
			mountViewTab(session)

			onAllNodesWithText(ORIGIN)[0].performMouseInput {
				advanceEventTime(GESTURE_GAP_MILLIS)
				click()
			}
			waitForIdle()
			onNode(hasSetTextAction() and isFocused()).performTextReplacement("50")
			onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()

			assertEquals(Cursor2d(350f, -200f), session.cursor2d.value)
			onAllNodesWithText("50.0").assertCountEquals(1)
			onAllNodesWithText(ORIGIN).assertCountEquals(1)
			assertFalse(session.canUndo.value, "a cursor move is not an undo step")
			assertEquals(stepsBefore, session.historyView.value.steps.size)
		}

	/** A scrub cancelled through the shell's seam takes the cursor off the canvas again, as it was. */
	@Test
	fun aCancelledScrubLeavesTheCursorUnplaced() =
		runComposeUiTest {
			val session = session()
			val scrubCancel = ScrubCancelController()
			mountViewTab(session, scrubCancel)

			onAllNodesWithText(ORIGIN)[0].performMouseInput {
				advanceEventTime(GESTURE_GAP_MILLIS)
				moveTo(center)
				press()

				for (stepIndex in 1..SCRUB_STEPS) {
					advanceEventTime(GESTURE_STEP_MILLIS)
					moveTo(center + Offset(SCRUB_PIXELS * stepIndex / SCRUB_STEPS, 0f))
				}
			}
			waitForIdle()
			assertNotNull(session.cursor2d.value, "precondition: the scrub placed the cursor")
			val parked = scrubCancel.cancel
			assertNotNull(parked, "the scrub parks its cancel for Escape")
			runOnIdle { parked() }
			waitForIdle()

			assertNull(session.cursor2d.value, "the cancel puts the cursor back off the canvas")
			onAllNodesWithText(ORIGIN).assertCountEquals(2)
		}

	private companion object {
		const val ORIGIN = "0.0"
		const val GESTURE_GAP_MILLIS = 1000L
		const val GESTURE_STEP_MILLIS = 16L
		const val SCRUB_PIXELS = 90f
		const val SCRUB_STEPS = 6
	}
}