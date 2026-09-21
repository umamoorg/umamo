package org.umamo.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.noticeText
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.status_bind_grab
import org.umamo.ui.resources.status_parts
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.commands.everyCommandTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The status strip's layout under pressure, against a real composition.
 *
 * The hints are the one zone whose length follows the pointer, and the longest list the default keymap
 * produces - six entries over a UV editor in Object mode - is wider than everything else on the strip.
 * What is measured here is who gives way when the window cannot hold it all, which is a property of how
 * the Row measures its children and so of nothing a pure test can reach.
 */
class StatusBarLayoutTest {
	/** The notice every case shows, chosen only for being a status-bar notice with no arguments. */
	private val noticeKey = "notice.transform.onlyDrawables"

	/**
	 * What a composed strip resolved its localized text to, so the cases find their nodes by the strings
	 * the strip really shows rather than by an English literal.
	 *
	 * @property String hintFragment A label only the hint zone shows.
	 * @property String statsFragment An entry only the model stats show.
	 * @property String notice The transient notice's text.
	 */
	private class StripText(val hintFragment: String, val statsFragment: String, val notice: String)

	/**
	 * Composes the strip at a width the case controls, over an Object-mode session with the whole command
	 * table registered and the pointer on a UV editor - the longest hint list there is.
	 *
	 * @param ComposeUiTest test The running compose test.
	 * @param EditorSession session The session the strip reads.
	 * @param HoveredSurfaceTracker tracker The tracker the hint zone observes.
	 * @param Function barWidth Reads the width to lay the strip out at, as snapshot state.
	 * @return StripText The localized strings the strip shows, for finding its nodes.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun composeStrip(test: ComposeUiTest, session: EditorSession, tracker: HoveredSurfaceTracker, barWidth: () -> Dp): StripText {
		val registry = CommandRegistry()
		everyCommandTable(session).forEach { command -> registry.register(command) }
		var hintFragment = ""
		var statsFragment = ""
		var notice = ""
		test.setContent {
			hintFragment = stringResource(Res.string.status_bind_grab)
			statsFragment = stringResource(Res.string.status_parts, 0)
			notice = noticeText(noticeKey)
			UmamoTheme {
				CompositionLocalProvider(
					LocalCommands provides registry,
					LocalKeymap provides defaultKeymap(),
					LocalHoveredSurfaceTracker provides tracker,
					LocalEditorSession provides session,
					LocalPuppet provides session.model.value,
				) {
					Box(modifier = Modifier.width(barWidth())) {
						StatusBar(modifier = Modifier.fillMaxWidth())
					}
				}
			}
		}
		test.waitForIdle()
		return StripText(hintFragment, statsFragment, notice)
	}

	/**
	 * The bounds of the one text node containing [fragment].
	 *
	 * @param ComposeUiTest test The running compose test.
	 * @param String fragment Text only that node shows.
	 * @return DpRect The node's bounds in the root, unclipped.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun boundsOf(test: ComposeUiTest, fragment: String): DpRect =
		test.onNodeWithText(fragment, substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()

	/**
	 * When the window cannot hold the full hint list, the hints are cut and the model stats keep their
	 * whole width: a suggestion is worth less than a count the user is reading.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theHintsGiveWayBeforeTheStats() =
		runComposeUiTest {
			var barWidth by mutableStateOf(3000.dp)
			val tracker = HoveredSurfaceTracker()
			tracker.lastTouched = HoveredSurface("uv-1", SpaceKind.UvEditor)
			val text = composeStrip(this, commandFixtureSession(EditorMode.Object), tracker) { barWidth }

			val roomyHints = boundsOf(this, text.hintFragment)
			val roomyStats = boundsOf(this, text.statsFragment)

			// Room for the stats and about a third of the hints: the hints cannot fit, the stats can.
			barWidth = roomyStats.width + roomyHints.width / 3
			waitForIdle()
			val tightHints = boundsOf(this, text.hintFragment)
			val tightStats = boundsOf(this, text.statsFragment)

			assertEquals(roomyStats.width, tightStats.width, "the stats keep their whole width")
			assertEquals(roomyStats.height, tightStats.height, "and stay on one line")
			assertTrue(tightHints.width < roomyHints.width, "the hints are what gets cut")
			assertEquals(roomyHints.height, tightHints.height, "cut on one line, not wrapped inside the fixed-height strip")
			assertTrue(tightHints.right <= tightStats.left, "and end before the stats begin")
			assertTrue(tightStats.right <= barWidth, "with the stats still inside the window")
		}

	/**
	 * The transient notice holds its place as the pointer crosses between spaces.  The hint list under it
	 * changes length with every crossing, and a notice that slid sideways each time would read as a new
	 * message.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theNoticeHoldsItsPlaceAsTheHintsChange() =
		runComposeUiTest {
			// The notice dismisses itself after a few seconds of the test clock, so the clock is stepped by
			// hand and never that far.
			mainClock.autoAdvance = false
			val session = commandFixtureSession(EditorMode.Object)
			val tracker = HoveredSurfaceTracker()
			tracker.lastTouched = HoveredSurface("uv-1", SpaceKind.UvEditor)
			val text = composeStrip(this, session, tracker) { 1600.dp }
			session.emitNotice(noticeKey, NoticePlacement.StatusBar)
			mainClock.advanceTimeBy(64L)
			val overTheUvEditor = boundsOf(this, text.notice)

			// An outliner is offered only the two hints that belong everywhere: a much shorter list.
			tracker.lastTouched = HoveredSurface("outliner-1", SpaceKind.Outliner)
			mainClock.advanceTimeBy(64L)
			val grabHints = onAllNodesWithText(text.hintFragment, substring = true, useUnmergedTree = true).fetchSemanticsNodes()
			assertTrue(grabHints.isEmpty(), "the hint list must really have changed for this to mean anything")

			assertEquals(overTheUvEditor, boundsOf(this, text.notice))
		}
}