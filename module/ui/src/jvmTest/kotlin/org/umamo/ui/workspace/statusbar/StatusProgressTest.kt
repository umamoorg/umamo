package org.umamo.ui.workspace.statusbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
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
import org.umamo.ui.progress.LocalProgressTracker
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.progress.ProgressTracker
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.progress_count
import org.umamo.ui.resources.progress_mesh_generating
import org.umamo.ui.resources.progress_more_tasks
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.noticeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The running tasks on the strip: a task shows in place of the transient notice, with its count and how many
 * more run behind it; the notice raised meanwhile shows once the tasks end; and the tasks show with no
 * document open, since an open runs before the document it opens has a session.
 */
@OptIn(ExperimentalTestApi::class)
class StatusProgressTest {
	/** A status-bar notice with no arguments. */
	private val noticeKey = "notice.transform.onlyDrawables"

	/**
	 * What the strip shows, localized, so the cases find nodes by the strip's own strings.
	 *
	 * @property String generating The Generate Mesh task's label.
	 * @property String count      The task's count at one of three.
	 * @property String more       The one-more-task marker.
	 * @property String notice     The transient notice.
	 */
	private class StripText(val generating: String, val count: String, val more: String, val notice: String)

	/**
	 * Composes the strip over [tracker] and, when given, [session].
	 *
	 * @param ComposeUiTest   test    The running compose test.
	 * @param ProgressTracker tracker The tracker the strip reads.
	 * @param EditorSession?  session The open document's session, or null for none.
	 * @return StripText The strings the strip shows.
	 */
	private fun composeStrip(test: ComposeUiTest, tracker: ProgressTracker, session: EditorSession?): StripText {
		var text = StripText("", "", "", "")
		test.setContent {
			text =
				StripText(
					stringResource(Res.string.progress_mesh_generating),
					stringResource(Res.string.progress_count, 1, 3),
					stringResource(Res.string.progress_more_tasks, 1),
					noticeText(noticeKey),
				)
			UmamoTheme {
				CompositionLocalProvider(
					LocalCommands provides CommandRegistry(),
					LocalKeymap provides defaultKeymap(),
					LocalEditorSession provides session,
					LocalPuppet provides session?.model?.value,
					LocalProgressTracker provides tracker,
				) {
					Box(modifier = Modifier.width(900.dp)) {
						StatusBar(modifier = Modifier.fillMaxWidth())
					}
				}
			}
		}
		test.waitForIdle()

		return text
	}

	/**
	 * How many text nodes show exactly [text].
	 *
	 * @param ComposeUiTest test The running compose test.
	 * @param String        text The text.
	 * @return Int The count.
	 */
	private fun shown(test: ComposeUiTest, text: String): Int = test.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

	@Test
	fun aRunningTaskShowsInPlaceOfTheNoticeAndTheNoticeShowsWhenItEnds() =
		runComposeUiTest {
			val session = commandFixtureSession(EditorMode.Object)
			val tracker = ProgressTracker()
			val text = composeStrip(this, tracker, session)
			val task = tracker.begin(ProgressLabels.MESH_GENERATING, total = 3)
			task.advance()
			session.emitNotice(noticeKey, NoticePlacement.StatusBar)
			waitForIdle()

			assertEquals(1, shown(this, text.generating), "the task's label")
			assertEquals(1, shown(this, text.count), "and its count")
			assertEquals(0, shown(this, text.notice), "the notice waits behind the task")

			task.finish()
			waitForIdle()
			assertEquals(0, shown(this, text.generating))
			assertEquals(1, shown(this, text.notice), "and shows once the task ends")
		}

	@Test
	fun theTasksShowWithNoDocumentOpenNewestFirstWithACountOfTheRest() =
		runComposeUiTest {
			val tracker = ProgressTracker()
			val text = composeStrip(this, tracker, session = null)
			val packing = tracker.begin(ProgressLabels.ATLAS_PACKING, total = 1)
			tracker.begin(ProgressLabels.MESH_GENERATING, total = 3).advance()
			waitForIdle()

			assertEquals(1, shown(this, text.generating), "the newest task's label, with no session at all")
			assertEquals(1, shown(this, text.more), "and how many more run behind it")

			packing.finish()
			waitForIdle()
			assertEquals(0, shown(this, text.more))
		}
}