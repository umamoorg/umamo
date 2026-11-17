package org.umamo.ui.workspace.spaces.viewport2d

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.transform.beginBoxSelect
import org.umamo.edit.transform.beginObjectOperator
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.action.recordingRegistryOf
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.shellCommandTables
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The viewport toolbar is the viewport tool family as the registry lists it: a button per tool, in the family's
 * order, each lit while its tool is armed or in flight and running its command when picked.
 */
@OptIn(ExperimentalTestApi::class)
class ViewportToolbarTest {
	/**
	 * Mounts the toolbar over [commands], with [session] as the open document.
	 *
	 * @param EditorSession? session The open document's session, or null for none.
	 * @param CommandRegistry commands The registry the toolbar lists its tools from: the shell's tables over
	 *   [session] unless a case hands in its own.
	 */
	private fun ComposeUiTest.mountToolbar(
		session: EditorSession?,
		commands: CommandRegistry = CommandRegistry().also { registry -> registry.registerAll(shellCommandTables(session)) },
	) {
		setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalCommands provides commands, LocalKeymap provides defaultKeymap(), LocalEditorSession provides session) {
					ViewportToolbarOverlay()
				}
			}
		}
		waitForIdle()
	}

	/**
	 * The tools whose buttons are lit, by accessible name.
	 *
	 * @return List<String> The lit tools.
	 */
	private fun ComposeUiTest.litTools(): List<String> = TOOLS.filter { tool -> onAllNodes(isSelected() and hasAnyDescendant(hasContentDescription(tool))).fetchSemanticsNodes().isNotEmpty() }

	@Test
	fun theStripIsTheToolFamilyTopToBottom() =
		runComposeUiTest {
			mountToolbar(commandFixtureSession(EditorMode.Object))

			val tops = TOOLS.map { tool -> onNodeWithContentDescription(tool).assertIsEnabled().getUnclippedBoundsInRoot().top.value }

			assertEquals(tops.sorted(), tops, "the select tools, then Grab, Rotate, Scale")
			assertEquals(TOOLS.size, tops.toSet().size, "one button apiece")
		}

	/** A tool armed or begun anywhere - its key, the palette - is the one the strip lights. */
	@Test
	fun theArmedToolIsTheOneLit() =
		runComposeUiTest {
			val session = commandFixtureSession(EditorMode.Object)
			mountToolbar(session)

			assertEquals(emptyList(), litTools())

			runOnIdle { session.beginBoxSelect("area") }
			waitForIdle()

			assertEquals(listOf("Box Select"), litTools())

			runOnIdle {
				session.clearSelectTool()
				session.beginObjectOperator(MeshOperatorKind.Rotate, "area")
			}
			waitForIdle()

			assertEquals(listOf("Rotate Vertices"), litTools())
		}

	@Test
	fun aButtonRunsItsToolsCommand() =
		runComposeUiTest {
			val dispatched = ArrayList<String>()
			val session = commandFixtureSession(EditorMode.Object)
			mountToolbar(session, recordingRegistryOf(shellCommandTables(session), dispatched))

			for (tool in TOOLS) {
				onNodeWithContentDescription(tool).performClick()
			}

			waitForIdle()

			assertEquals(listOf("mesh.boxSelect", "mesh.circleSelect", "mesh.grab", "mesh.rotate", "mesh.scale"), dispatched)
		}

	/** With no open document the strip still shows its tools, grayed: the chrome reads the same before the first file opens. */
	@Test
	fun withNoDocumentTheToolsAreShownGrayed() =
		runComposeUiTest {
			mountToolbar(session = null)

			for (tool in TOOLS) {
				onNodeWithContentDescription(tool).assertIsNotEnabled()
			}
		}

	private companion object {
		/** The tools' accessible names, their commands' titles, in the strip's order. */
		val TOOLS = listOf("Box Select", "Circle Select", "Grab Vertices", "Rotate Vertices", "Scale Vertices")
	}
}