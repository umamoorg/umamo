package org.umamo.ui.workspace.shell

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.parseKeyChord
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.Button
import org.umamo.ui.kit.button.ButtonGroup
import org.umamo.ui.kit.button.ButtonGroupItem
import org.umamo.ui.kit.container.Tab
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.LocalViewportChrome
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.ViewportChromeState
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.sidebar.SidebarDrawer
import org.umamo.ui.workspace.sidebar.SidebarState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A click on a control that leaves the composition with that click leaves the keyboard with the shell root,
 * driven under a miniature shell (a focusable root running the real modal ladder) with real clicks and real
 * key events.  A control that took focus on the click would take it along when it went, leaving focus null,
 * and every shortcut, undo included, would stay dead until something focusable was clicked again.
 */
@OptIn(ExperimentalTestApi::class)
class ClickedChromeKeyboardTest {
	/** The shell state one case shares between its composition and its assertions. */
	private class Harness {
		val registry = CommandRegistry()
		val keymap = Keymap(mapOf(parseKeyChord("primary+KeyS")!! to SHORTCUT_COMMAND))
		val rootFocus = FocusRequester()

		/** How many times the bound chord reached the keymap, which is what "shortcuts work" means here. */
		var shortcutRuns = 0
	}

	/**
	 * Mounts [content] under a root that owns the keyboard as the shell's does, gives the root focus, and
	 * checks the bound chord reaches the keymap before anything is clicked.
	 *
	 * @param ComposeUiTest test    The running UI test.
	 * @param Harness       harness The state this case shares with its composition.
	 * @param Function      content The chrome under test.
	 */
	private fun mount(test: ComposeUiTest, harness: Harness, content: @Composable () -> Unit) {
		harness.registry.registerAll(listOf(Command(SHORTCUT_COMMAND, title = null) { harness.shortcutRuns += 1 }))
		test.setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalCommands provides harness.registry) {
					Box(
						modifier =
							Modifier
								.fillMaxSize()
								.focusRequester(harness.rootFocus)
								.focusable()
								.onPreviewKeyEvent { event ->
									handleModalKeyLadder(
										event.toShellKeyStroke(),
										ShellModalState(
											overlays = ShellOverlayState(),
											commandRegistry = harness.registry,
											keymap = harness.keymap,
										),
									)
								},
					) {
						content()
					}
				}
			}
		}
		test.runOnIdle { harness.rootFocus.requestFocus() }
		test.waitForIdle()
		pressShortcut(test)
		assertEquals(1, harness.shortcutRuns, "precondition: the chord reaches the keymap before the click")
	}

	/**
	 * Presses and releases the bound chord in the window, where it reaches whichever node holds focus - or
	 * nothing.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun pressShortcut(test: ComposeUiTest) {
		test.onRoot().performKeyInput {
			keyDown(Key.CtrlLeft)
			keyDown(Key.S)
			keyUp(Key.S)
			keyUp(Key.CtrlLeft)
		}
		test.waitForIdle()
	}

	/** The sidebar's pull tab opens the sidebar and goes away with the click: the shortcuts still work. */
	@Test
	fun thePullTabLeavesTheKeyboardWithTheRoot() =
		runComposeUiTest {
			val harness = Harness()
			val sidebarState = SidebarState()
			harness.registry.registerAll(listOf(Command("view.toggleSidebar", title = null) { sidebarState.visibility.choice = true }))
			mount(this, harness) {
				CompositionLocalProvider(LocalViewportChrome provides ViewportChromeState(showSidebar = false)) {
					SidebarDrawer(
						areaId = AREA_ID,
						space = SpaceKind.Viewport2D,
						state = sidebarState,
						passPointerThrough = false,
					)
				}
			}

			onNodeWithContentDescription(PULL_TAB_LABEL).performClick()
			waitForIdle()
			onNodeWithContentDescription(PULL_TAB_LABEL).assertDoesNotExist()
			pressShortcut(this)

			assertEquals(2, harness.shortcutRuns, "the chord has to reach the keymap after the pull tab went away")
		}

	/** A button-group segment that goes away with its click, as Edit mode's select modes do on Tab: the shortcuts still work. */
	@Test
	fun aButtonGroupSegmentLeavesTheKeyboardWithTheRoot() =
		runComposeUiTest {
			val harness = Harness()
			var shown by mutableStateOf(true)
			mount(this, harness) {
				if (shown) {
					ButtonGroup(
						listOf(
							ButtonGroupItem(
								icon = LocalUmamoIcons.chevronLeft,
								selected = false,
								onClick = { shown = false },
								contentDescription = SEGMENT_LABEL,
							),
						),
					)
				}
			}

			onNodeWithContentDescription(SEGMENT_LABEL).performClick()
			waitForIdle()
			pressShortcut(this)

			assertEquals(2, harness.shortcutRuns, "the chord has to reach the keymap after the segment went away")
		}

	/** A button that goes away with its click, as a Properties row does when the selection moves on: the shortcuts still work. */
	@Test
	fun aButtonLeavesTheKeyboardWithTheRoot() =
		runComposeUiTest {
			val harness = Harness()
			var shown by mutableStateOf(true)
			mount(this, harness) {
				if (shown) {
					Button(label = BUTTON_LABEL, onClick = { shown = false })
				}
			}

			onNodeWithText(BUTTON_LABEL).performClick()
			waitForIdle()
			pressShortcut(this)

			assertEquals(2, harness.shortcutRuns, "the chord has to reach the keymap after the button went away")
		}

	/** A tab that goes away with its click: the shortcuts still work. */
	@Test
	fun aTabLeavesTheKeyboardWithTheRoot() =
		runComposeUiTest {
			val harness = Harness()
			var shown by mutableStateOf(true)
			mount(this, harness) {
				if (shown) {
					Tab(selected = false, onClick = { shown = false }) {
						Text(TAB_LABEL)
					}
				}
			}

			onNodeWithText(TAB_LABEL).performClick()
			waitForIdle()
			pressShortcut(this)

			assertEquals(2, harness.shortcutRuns, "the chord has to reach the keymap after the tab went away")
		}

	private companion object {
		/** The command the bound chord runs, standing in for undo and the rest of the keymap. */
		const val SHORTCUT_COMMAND = "test.shortcut"

		/** The area the sidebar belongs to. */
		const val AREA_ID = "area-1"

		/** The pull tab's accessible label, the toggle command's title. */
		const val PULL_TAB_LABEL = "Toggle Sidebar"

		/** The labels of the controls clicked away. */
		const val SEGMENT_LABEL = "Segment"
		const val BUTTON_LABEL = "Apply"
		const val TAB_LABEL = "Layout"
	}
}