package org.umamo.ui.action

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.menu_file_new
import org.umamo.ui.resources.menu_file_save
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The buttons that stand for one command, over commands made up for each case: a button is its command's - the
 * title or the glyph, the pick, when it is grayed, and when it is lit - and reads the command's live state again
 * whenever its caller recomposes.
 */
@OptIn(ExperimentalTestApi::class)
class CommandControlsTest {
	/**
	 * Mounts [content] over [commands].
	 *
	 * @param CommandRegistry commands The registry the buttons read and dispatch through.
	 * @param Function content The buttons under test.
	 */
	private fun ComposeUiTest.mount(commands: CommandRegistry, content: @Composable () -> Unit) {
		setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalCommands provides commands) {
					Column { content() }
				}
			}
		}
		waitForIdle()
	}

	/**
	 * How many nodes are lit: selected, and holding the button described as [description].
	 *
	 * @param String description The button's accessible label.
	 * @return Int The count.
	 */
	private fun ComposeUiTest.litButtons(description: String): Int = onAllNodes(isSelected() and hasAnyDescendant(hasContentDescription(description))).fetchSemanticsNodes().size

	@Test
	fun aCommandButtonIsLabeledByItsTitleAndRunsItsCommand() =
		runComposeUiTest {
			var runCount = 0
			val commands = CommandRegistry().also { registry -> registry.register(Command("test.save", title = Res.string.menu_file_save, handler = { runCount++ })) }
			mount(commands) { CommandButton("test.save") }

			onNodeWithText("Save").assertIsEnabled().performClick()
			waitForIdle()

			assertEquals(1, runCount)
		}

	/** A button whose command nothing has registered draws nothing, and appears when its command is registered. */
	@Test
	fun aButtonWithNoCommandDrawsNothingUntilItIsRegistered() =
		runComposeUiTest {
			val commands = CommandRegistry()
			mount(commands) {
				CommandButton("test.save")
				CommandIconButton("test.new")
			}

			assertEquals(0, onAllNodes(hasContentDescription("New")).fetchSemanticsNodes().size)

			runOnIdle {
				commands.register(Command("test.save", title = Res.string.menu_file_save, handler = {}))
				commands.register(Command("test.new", title = Res.string.menu_file_new, icon = LocalUmamoIcons.cursor, handler = {}))
			}
			waitForIdle()

			onNodeWithText("Save").assertExists()
			onNodeWithContentDescription("New").assertExists()
		}

	/**
	 * Whether a command applies cannot be observed, so a button reads it again whenever its caller recomposes - and is
	 * not skipped for being handed the same command id as last time.
	 */
	@Test
	fun aButtonIsGrayedWithItsCommandAndReadsItAgainWhenItsCallerRecomposes() =
		runComposeUiTest {
			var applies by mutableStateOf(false)
			val commands =
				CommandRegistry().also { registry ->
					registry.register(Command("test.save", title = Res.string.menu_file_save, availability = CommandAvailability { applies }, handler = {}))
					registry.register(Command("test.new", title = Res.string.menu_file_new, availability = CommandAvailability { applies }, icon = LocalUmamoIcons.cursor, handler = {}))
				}
			mount(commands) {
				ObserveForCommands(applies)
				CommandButton("test.save")
				CommandIconButton("test.new")
			}

			onNodeWithText("Save").assertIsNotEnabled()
			onNodeWithContentDescription("New").assertIsNotEnabled()

			runOnIdle { applies = true }
			waitForIdle()

			onNodeWithText("Save").assertIsEnabled()
			onNodeWithContentDescription("New").assertIsEnabled()
		}

	/** An icon button is lit while its command is the one in effect, read again whenever its caller recomposes. */
	@Test
	fun anIconButtonIsLitWhileItsCommandIsInEffect() =
		runComposeUiTest {
			var armed by mutableStateOf(false)
			var runCount = 0
			val tool = Command("test.tool", title = Res.string.menu_file_new, icon = LocalUmamoIcons.cursor, state = CommandState { armed }, handler = { runCount++ })
			val commands = CommandRegistry().also { registry -> registry.register(tool) }
			mount(commands) {
				ObserveForCommands(armed)
				CommandIconButton(tool)
			}

			assertEquals(0, litButtons("New"))

			runOnIdle { armed = true }
			waitForIdle()

			assertEquals(1, litButtons("New"))

			onNodeWithContentDescription("New").performClick()
			waitForIdle()

			assertEquals(1, runCount)
		}

	/** A command whose state takes an argument reads as lit the same way: the button says so as well as showing it. */
	@Test
	fun anIconButtonIsLitForACommandWhoseStateTakesAnArgument() =
		runComposeUiTest {
			val tool = Command("test.tool", title = Res.string.menu_file_new, icon = LocalUmamoIcons.cursor, stateFor = { argument -> argument == null }, handler = {})
			val commands = CommandRegistry().also { registry -> registry.register(tool) }
			mount(commands) { CommandIconButton(tool) }

			assertEquals(1, litButtons("New"))
		}

	/**
	 * A button may say more than its command knows: a glyph and hover text of its own, and something done around the
	 * dispatch.  When it is grayed and what it runs are still the command's.
	 */
	@Test
	fun anIconButtonCanWearItsOwnFaceAndBracketItsDispatch() =
		runComposeUiTest {
			val order = ArrayList<String>()
			val commands =
				CommandRegistry().also { registry ->
					registry.register(Command("test.reload", title = Res.string.menu_file_new, icon = LocalUmamoIcons.cursor, handler = { order.add("run") }))
				}
			mount(commands) {
				CommandIconButton("test.reload", icon = LocalUmamoIcons.selection, description = "Files changed on disk") { dispatch ->
					order.add("before")
					dispatch()
				}
			}

			assertEquals(0, onAllNodes(hasContentDescription("New")).fetchSemanticsNodes().size, "the button's own description replaces the title")

			onNodeWithContentDescription("Files changed on disk").performClick()
			waitForIdle()

			assertEquals(listOf("before", "run"), order)
		}
}