package org.umamo.ui.kit.field

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.umamo.ui.kit.textentry.InlineEditController
import org.umamo.ui.kit.textentry.LocalInlineEditController
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pins the commit-once lifecycle of [CommitTextField] and its one-paragraph rule. */
@OptIn(ExperimentalTestApi::class)
class CommitTextFieldTest {
	/** Enter commits the draft once, and the focus loss that follows commits nothing more. */
	@Test
	fun enterCommitsOnce() =
		runComposeUiTest {
			val host = CommitFieldHost()
			mountCommitField(host)

			typeIntoField("Opens the jaw")
			onNodeWithTag(FIELD_TAG).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()
			runOnIdle { host.elsewhere.requestFocus() }
			waitForIdle()

			assertEquals(listOf("Opens the jaw"), host.commits)
			assertNull(host.controller.cancel, "the field gives its cancel hook back once the edit ends")
		}

	/** Focus moving anywhere else commits the draft. */
	@Test
	fun aFocusMoveCommits() =
		runComposeUiTest {
			val host = CommitFieldHost()
			mountCommitField(host)

			typeIntoField("Opens the jaw")
			runOnIdle { host.elsewhere.requestFocus() }
			waitForIdle()

			assertEquals(listOf("Opens the jaw"), host.commits)
		}

	/** Escape the field hears itself discards the draft, and the field shows the committed text again. */
	@Test
	fun escapeDiscards() =
		runComposeUiTest {
			val host = CommitFieldHost(initial = "Raises the brow")
			mountCommitField(host)

			typeIntoField("Lowers the brow")
			onNodeWithTag(FIELD_TAG).performKeyInput { pressKey(Key.Escape) }
			waitForIdle()

			assertEquals(emptyList(), host.commits)
			assertEquals("Raises the brow", shownText())
			assertNull(host.controller.cancel)
		}

	/** The cancel hook the field parks, which the shell calls on Escape, discards the draft too. */
	@Test
	fun theParkedCancelDiscards() =
		runComposeUiTest {
			val host = CommitFieldHost(initial = "Raises the brow")
			mountCommitField(host)

			typeIntoField("Lowers the brow")
			val cancel = assertNotNull(host.controller.cancel, "a focused field parks its cancel hook")
			runOnIdle { cancel() }
			waitForIdle()

			assertEquals(emptyList(), host.commits)
			assertEquals("Raises the brow", shownText())
		}

	/** An edit that ends where it started commits nothing. */
	@Test
	fun anUnchangedDraftCommitsNothing() =
		runComposeUiTest {
			val host = CommitFieldHost(initial = "Raises the brow")
			mountCommitField(host)

			onNodeWithTag(FIELD_TAG).performClick()
			waitForIdle()
			onNodeWithTag(FIELD_TAG).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()

			assertEquals(emptyList(), host.commits)
		}

	/** The field wraps, yet Enter still ends the edit instead of breaking the line. */
	@Test
	fun enterInsertsNoLineBreak() =
		runComposeUiTest {
			val host = CommitFieldHost()
			mountCommitField(host)

			onNodeWithTag(FIELD_TAG).performClick()
			waitForIdle()
			onNodeWithTag(FIELD_TAG).performTextInput("Opens")
			onNodeWithTag(FIELD_TAG).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()

			assertEquals(listOf("Opens"), host.commits)
		}

	/** A pasted line break, in any platform's form, arrives as one space. */
	@Test
	fun aPastedLineBreakArrivesAsASpace() =
		runComposeUiTest {
			val host = CommitFieldHost()
			mountCommitField(host)

			typeIntoField("Opens\r\nthe\njaw\rwide")
			assertEquals("Opens the jaw wide", shownText())

			onNodeWithTag(FIELD_TAG).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()
			assertEquals(listOf("Opens the jaw wide"), host.commits)
		}

	/** The placeholder shows while the field is empty and goes once it holds text. */
	@Test
	fun thePlaceholderShowsOnlyWhileEmpty() =
		runComposeUiTest {
			val host = CommitFieldHost()
			mountCommitField(host)
			assertEquals(1, placeholderCount())

			typeIntoField("Opens the jaw")
			assertEquals(0, placeholderCount())

			onNodeWithTag(FIELD_TAG).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()
			assertEquals(0, placeholderCount())
		}

	/** A field nobody is typing in follows the committed text; a field being typed in keeps its draft. */
	@Test
	fun theFieldFollowsTheValueOnlyWhileNotEdited() =
		runComposeUiTest {
			val host = CommitFieldHost(initial = "Raises the brow")
			mountCommitField(host)

			runOnIdle { host.value = "Lowers the brow" }
			waitForIdle()
			assertEquals("Lowers the brow", shownText())

			typeIntoField("Knits the brow")
			runOnIdle { host.value = "Arches the brow" }
			waitForIdle()
			assertEquals("Knits the brow", shownText(), "an outside change must not overwrite the draft being typed")
		}

	/** A field that leaves the composition while being typed in commits its draft and gives its hook back. */
	@Test
	fun disposalWhileFocusedCommits() =
		runComposeUiTest {
			val host = CommitFieldHost()
			mountCommitField(host)

			typeIntoField("Opens the jaw")
			runOnIdle { host.shown = false }
			waitForIdle()

			assertEquals(listOf("Opens the jaw"), host.commits)
			assertNull(host.controller.cancel, "a disposed field must not leave the shell yielding the keyboard to it")
		}

	/** A text wider than the field wraps onto more lines, so the field grows taller. */
	@Test
	fun aLongTextWraps() =
		runComposeUiTest {
			val host = CommitFieldHost(initial = "Short")
			mountCommitField(host)
			val oneLine = onNodeWithTag(FIELD_TAG).getUnclippedBoundsInRoot().height

			runOnIdle { host.value = "A note long enough that it cannot fit on one line of this narrow field" }
			waitForIdle()
			val wrapped = onNodeWithTag(FIELD_TAG).getUnclippedBoundsInRoot().height

			assertTrue(wrapped > oneLine * 1.5f, "the long text must wrap ($oneLine -> $wrapped)")
		}

	/** Each line break becomes one space, and the caret lands where it would have in the joined text. */
	@Test
	fun lineBreaksBecomeSpacesAndTheSelectionFollows() {
		val joined = TextFieldValue("a\r\nb\nc\rd", selection = TextRange(8)).withLineBreaksAsSpaces()
		assertEquals("a b c d", joined.text)
		assertEquals(TextRange(7), joined.selection)

		val inside = TextFieldValue("one\r\ntwo", selection = TextRange(0, 5)).withLineBreaksAsSpaces()
		assertEquals("one two", inside.text)
		assertEquals(TextRange(0, 4), inside.selection, "a selection ending after a CRLF ends after its space")
	}

	/** A value holding no line break comes back as the same instance. */
	@Test
	fun aValueWithoutLineBreaksIsUnchanged() {
		val plain = TextFieldValue("Opens the jaw", selection = TextRange(3))

		assertSame(plain, plain.withLineBreaksAsSpaces())
	}

	/**
	 * Focuses the field and replaces its text, as typing it would.
	 *
	 * @param String text The text to type.
	 */
	private fun ComposeUiTest.typeIntoField(text: String) {
		onNodeWithTag(FIELD_TAG).performClick()
		waitForIdle()
		onNodeWithTag(FIELD_TAG).performTextReplacement(text)
		waitForIdle()
	}

	/**
	 * The text the field shows.
	 *
	 * @return String The field's editable text.
	 */
	private fun ComposeUiTest.shownText(): String = onNodeWithTag(FIELD_TAG).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

	/**
	 * How many placeholders are on screen.
	 *
	 * @return Int The count.
	 */
	private fun ComposeUiTest.placeholderCount(): Int = onAllNodesWithText(PLACEHOLDER, useUnmergedTree = true).fetchSemanticsNodes().size
}

/**
 * The state one case shares with its composition: the committed text, which a commit writes back as an owner
 * would, and every commit in order.
 *
 * @property String initial The text the field starts on.
 */
private class CommitFieldHost(initial: String = "") {
	var value by mutableStateOf(initial)
	var shown by mutableStateOf(true)
	val commits = ArrayList<String>()
	val controller = InlineEditController()
	val elsewhere = FocusRequester()
}

/**
 * Mounts the field above a focusable node that stands in for the rest of the window.
 *
 * @param CommitFieldHost host The state this case shares with its composition.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.mountCommitField(host: CommitFieldHost) {
	setContent {
		UmamoTheme {
			CompositionLocalProvider(LocalInlineEditController provides host.controller) {
				Column {
					if (host.shown) {
						CommitTextField(
							value = host.value,
							onCommit = { committed ->
								host.commits.add(committed)
								host.value = committed
							},
							placeholder = PLACEHOLDER,
							modifier = Modifier.width(FIELD_WIDTH).testTag(FIELD_TAG),
						)
					}

					Box(modifier = Modifier.size(20.dp).focusRequester(host.elsewhere).focusable())
				}
			}
		}
	}
	waitForIdle()
}

private const val FIELD_TAG = "field"
private const val PLACEHOLDER = "Say what this does"
private val FIELD_WIDTH = 120.dp