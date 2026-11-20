package org.umamo.ui.action

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
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
	 * @param Keymap keymap The keymap the buttons read their accelerators from.
	 * @param Function content The buttons under test.
	 */
	private fun ComposeUiTest.mount(commands: CommandRegistry, keymap: Keymap = defaultKeymap(), content: @Composable () -> Unit) {
		setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalCommands provides commands, LocalKeymap provides keymap) {
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

	/**
	 * A command icon button's hover card carries the key that runs it, read from the keymap.  The key is
	 * never written down beside the button, so a rebind reaches every button at once.
	 */
	@Test
	fun anIconButtonsTooltipCarriesItsCommandsAccelerator() =
		runComposeUiTest {
			val commands = CommandRegistry().also { registry -> registry.register(Command("test.grab", title = Res.string.menu_file_new, icon = LocalUmamoIcons.cursor, handler = {})) }
			mount(commands, Keymap.fromSpecs(mapOf("KeyG" to "test.grab"))) { CommandIconButton("test.grab") }

			hoverIconButton(this, "New")

			onNodeWithText("New", useUnmergedTree = true).assertExists()
			onNodeWithText("G", useUnmergedTree = true).assertExists()
		}

	/** An unbound command's card names the control and stops there: there is no key to show. */
	@Test
	fun anIconButtonsTooltipCarriesNoAcceleratorForAnUnboundCommand() =
		runComposeUiTest {
			val commands = CommandRegistry().also { registry -> registry.register(Command("test.grab", title = Res.string.menu_file_new, icon = LocalUmamoIcons.cursor, handler = {})) }
			mount(commands, Keymap.fromSpecs(mapOf("KeyG" to "test.other"))) { CommandIconButton("test.grab") }

			hoverIconButton(this, "New")

			onAllNodes(isPopup()).assertCountEquals(1)
			onNodeWithText("G", useUnmergedTree = true).assertDoesNotExist()
		}

	/**
	 * Rests the pointer on the icon button labelled [description] and lets the tooltip delay run out.
	 *
	 * @param ComposeUiTest test        The running UI test.
	 * @param String        description The button's accessible label.
	 */
	private fun hoverIconButton(test: ComposeUiTest, description: String) {
		test.onNodeWithContentDescription(description).performMouseInput { moveTo(center) }
		test.mainClock.advanceTimeBy(TOOLTIP_WAIT_MILLIS)
		test.waitForIdle()
	}

	private companion object {
		/** Comfortably past the tooltip's dwell delay. */
		const val TOOLTIP_WAIT_MILLIS = 1_000L
	}
}