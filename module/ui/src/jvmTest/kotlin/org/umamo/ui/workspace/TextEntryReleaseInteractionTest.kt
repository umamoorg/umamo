package org.umamo.ui.workspace

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.parseKeyChord
import org.umamo.ui.kit.InlineEditController
import org.umamo.ui.kit.InlineRenameField
import org.umamo.ui.kit.LocalInlineEditController
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.SearchField
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.commands.registerAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the release under a miniature shell: a focusable root carrying the real modal ladder and the two
 * pointer observers the shell installs, with real presses and real key events.
 *
 * The bug this pins let a header filter keep the keyboard for the rest of the session - undo and every
 * other shortcut went quietly dead the moment someone typed in one, because nothing in the tree ever took
 * focus back off a text field.
 */
@OptIn(ExperimentalTestApi::class)
class TextEntryReleaseInteractionTest {
	/** The shell state one case shares between its composition and its assertions. */
	private class Harness {
		val overlays = ShellOverlayState()
		val controller = InlineEditController()
		val registry = CommandRegistry()
		val keymap = Keymap(mapOf(parseKeyChord("primary+KeyS")!! to SHORTCUT_COMMAND))
		val rootFocus = FocusRequester()

		/** How many times the bound chord reached the keymap, which is what "shortcuts work" means here. */
		var shortcutRuns = 0

		/** Whether the root itself holds focus, so a release can be told from a focus left null. */
		var rootFocused by mutableStateOf(false)

		/** The filter query, hoisted so a case can assert the text outlived the release. */
		var query by mutableStateOf("")

		/** The value a number field committed, or null until it commits one. */
		var committedNumber: Float? = null

		/** The name an inline rename committed, and whether it cancelled instead. */
		var committedName: String? = null
		var renameCancelled = false
	}

	/**
	 * Mounts [content] under a root that behaves like the shell: the modal ladder previews every key, and
	 * the press observer hands focus back when a press lands away from a live text editor.  An "elsewhere"
	 * target sits below the content, standing in for the panel a user clicks into next.
	 *
	 * @param ComposeUiTest test    The running UI test.
	 * @param Harness       harness The state this case shares with its composition.
	 * @param Function      content The text entry under test.
	 */
	private fun mount(test: ComposeUiTest, harness: Harness, content: @Composable () -> Unit) {
		harness.registry.registerAll(listOf(Command(SHORTCUT_COMMAND, title = null) { harness.shortcutRuns += 1 }))
		test.setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalInlineEditController provides harness.controller) {
					Box(
						modifier =
							Modifier
								.fillMaxSize()
								.testTag(ROOT_TAG)
								// Before the focus target it observes: after focusable() it reports whichever
								// DESCENDANT holds focus, which would make every root-focus assertion here pass.
								.onFocusChanged { focusState -> harness.rootFocused = focusState.isFocused }
								.focusRequester(harness.rootFocus)
								.focusable()
								.pointerInput(Unit) {
									observeTextEntryPresses(
										beginPress = { harness.controller.pressLandedOnTextEditor = false },
										settlePress = {
											val releases =
												shouldReleaseTextEntry(
													textEntryActive = harness.controller.cancel != null,
													pressLandedOnTextEditor = harness.controller.pressLandedOnTextEditor,
													selfFocusedOverlayOpen = harness.overlays.selfFocusedOverlayOpen,
												)
											if (releases) {
												harness.rootFocus.requestFocus()
											}
										},
									)
								}
								.onPreviewKeyEvent { event ->
									handleModalKeyLadder(
										event.toShellKeyStroke(),
										ShellModalState(
											overlays = harness.overlays,
											inlineEditController = harness.controller,
											commandRegistry = harness.registry,
											keymap = harness.keymap,
										),
									)
								},
					) {
						Column {
							content()
							Box(modifier = Modifier.testTag(ELSEWHERE_TAG).size(ELSEWHERE_SIZE))
						}
					}
				}
			}
		}
	}

	/**
	 * Mounts a header filter and types into it, leaving text entry live.
	 *
	 * @param ComposeUiTest test    The running UI test.
	 * @param Harness       harness The state this case shares with its composition.
	 */
	private fun typeInTheFilter(test: ComposeUiTest, harness: Harness) {
		mount(test, harness) {
			SearchField(
				value = harness.query,
				onValueChange = { edited -> harness.query = edited },
				modifier = Modifier.testTag(FIELD_TAG),
			)
		}
		test.onNodeWithTag(FIELD_TAG).performClick()
		test.waitForIdle()
		test.onNodeWithTag(FIELD_TAG).performTextInput(QUERY)
		test.waitForIdle()
		assertNotNull(harness.controller.cancel, "typing in the filter has to leave text entry live")
	}

	/** The bug: after typing in a filter and pressing elsewhere, the shortcuts work again. */
	@Test
	fun pressingElsewhereAfterTypingInAFilterRevivesTheShortcuts() {
		runComposeUiTest {
			val harness = Harness()
			typeInTheFilter(this, harness)
			onNodeWithTag(ROOT_TAG).performKeyInput {
				keyDown(Key.CtrlLeft)
				keyDown(Key.S)
				keyUp(Key.S)
				keyUp(Key.CtrlLeft)
			}
			waitForIdle()
			assertEquals(0, harness.shortcutRuns, "a live text editor keeps the chord for itself")

			onNodeWithTag(ELSEWHERE_TAG).performClick()
			waitForIdle()
			assertNull(harness.controller.cancel, "a press away from the field has to end text entry")

			onNodeWithTag(ROOT_TAG).performKeyInput {
				keyDown(Key.CtrlLeft)
				keyDown(Key.S)
				keyUp(Key.S)
				keyUp(Key.CtrlLeft)
			}
			waitForIdle()
			assertEquals(1, harness.shortcutRuns, "the chord has to reach the keymap once the field lets go")
		}
	}

	/** The release takes the keyboard, never the text: the filter still holds what was typed into it. */
	@Test
	fun theFilterTextSurvivesTheRelease() {
		runComposeUiTest {
			val harness = Harness()
			typeInTheFilter(this, harness)
			onNodeWithTag(ELSEWHERE_TAG).performClick()
			waitForIdle()
			assertEquals(QUERY, harness.query, "the release must not touch what the field holds")
		}
	}

	/** A press on the field itself is a caret move: text entry survives it. */
	@Test
	fun aPressInsideTheFieldKeepsTextEntryLive() {
		runComposeUiTest {
			val harness = Harness()
			typeInTheFilter(this, harness)
			onNodeWithTag(FIELD_TAG).performClick()
			waitForIdle()
			assertNotNull(harness.controller.cancel, "pressing the field you are typing in must not end text entry")
		}
	}

	/** The clear affordance rides inside the field, so pressing it clears the query and keeps the keyboard. */
	@Test
	fun pressingTheClearAffordanceKeepsTextEntryLive() {
		runComposeUiTest {
			val harness = Harness()
			typeInTheFilter(this, harness)
			onNodeWithContentDescription(CLEAR_LABEL, useUnmergedTree = true).performClick()
			waitForIdle()
			assertEquals("", harness.query)
			assertNotNull(harness.controller.cancel, "the clear button is part of the field, not somewhere else")
		}
	}

	/** After a release the root holds focus - a focus left null would kill the shortcuts just as dead. */
	@Test
	fun aReleasedShellHasFocusRatherThanNull() {
		runComposeUiTest {
			val harness = Harness()
			typeInTheFilter(this, harness)
			assertFalse(harness.rootFocused, "the field owns the keyboard while it is being typed in")
			onNodeWithTag(ELSEWHERE_TAG).performClick()
			waitForIdle()
			assertTrue(harness.rootFocused, "the release has to hand focus to the root, not clear it")
		}
	}

	/** Clicking away from an inline rename commits it, the way renaming a file in an explorer does. */
	@Test
	fun anOutsidePressCommitsAnInlineRename() {
		runComposeUiTest {
			val harness = Harness()
			mount(this, harness) {
				InlineRenameField(
					initialName = OLD_NAME,
					textStyle = TextStyle.Default,
					cursorColor = Color.Black,
					onCommit = { committed -> harness.committedName = committed },
					onCancel = { harness.renameCancelled = true },
					modifier = Modifier.testTag(FIELD_TAG),
				)
			}
			waitForIdle()
			onNodeWithTag(FIELD_TAG).performTextInput(NEW_NAME)
			waitForIdle()

			onNodeWithTag(ELSEWHERE_TAG).performClick()
			waitForIdle()
			assertEquals(NEW_NAME, harness.committedName, "clicking away commits the typed name")
			assertFalse(harness.renameCancelled, "clicking away must never run the cancel the shell parks for Escape")
		}
	}

	/** A number field commits its typed value on the way out, the same as the rename beside it. */
	@Test
	fun anOutsidePressCommitsAHalfTypedNumber() {
		runComposeUiTest {
			val harness = Harness()
			mount(this, harness) {
				NumberField(
					value = 0f,
					onValueChange = { committed -> harness.committedNumber = committed },
					range = 0f..100f,
					modifier = Modifier.testTag(FIELD_TAG),
					plain = true,
				)
			}
			onNodeWithTag(FIELD_TAG).performClick()
			waitForIdle()
			// The tag rides the field's box; the typing action is on the text node inside it.  Replacement,
			// not insertion: typing into the value already shown would read as a number nobody typed.
			onNode(hasSetTextAction()).performTextReplacement(TYPED_NUMBER)
			waitForIdle()

			onNodeWithTag(ELSEWHERE_TAG).performClick()
			waitForIdle()
			assertEquals(TYPED_NUMBER.toFloat(), harness.committedNumber)
		}
	}

	/**
	 * An overlay that owns its own focus keeps it.  The command palette parks the same hook from its own
	 * search box, and its list navigation lives on an ancestor of that box - releasing would take the
	 * arrows off the focus path while the palette is still open.
	 */
	@Test
	fun aPressWhileASelfFocusedOverlayIsOpenKeepsItsFocus() {
		runComposeUiTest {
			val harness = Harness()
			harness.overlays.paletteVisible = true
			typeInTheFilter(this, harness)
			onNodeWithTag(ELSEWHERE_TAG).performClick()
			waitForIdle()
			assertNotNull(harness.controller.cancel, "an open self-focused overlay keeps its own text entry")
		}
	}

	private companion object {
		const val ROOT_TAG = "root"
		const val FIELD_TAG = "field"
		const val ELSEWHERE_TAG = "elsewhere"

		/** The command the bound chord runs, standing in for undo and the rest of the keymap. */
		const val SHORTCUT_COMMAND = "test.shortcut"

		const val QUERY = "hip"
		const val OLD_NAME = "Old"
		const val NEW_NAME = "New"
		const val TYPED_NUMBER = "42"

		/** The clear button's English name; it doubles as its accessible label. */
		const val CLEAR_LABEL = "Clear search"

		/** Big enough to press without landing on the field above it. */
		val ELSEWHERE_SIZE = 40.dp
	}
}