package org.umamo.ui.workspace

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tab strip's press handling, driven with real pointer input: the double-click that opens the inline
 * rename editor, and the reorder drag that shares its gesture.
 *
 * The strip's gesture is a long-lived pointerInput coroutine per tab slot, so what these pin is which
 * workspace a slot's gesture believes it belongs to after the list underneath it has changed.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceTabsInteractionTest {
	/** The strip's state, shared between a case's composition and its assertions. */
	private class Harness {
		var workspaces by mutableStateOf(
			listOf(workspace("alpha", "Alpha"), workspace("bravo", "Bravo"), workspace("charlie", "Charlie")),
		)
		var activeId by mutableStateOf("alpha")
	}

	/**
	 * Mounts the tab strip over [harness], wired the way the shell wires it: selection and reorder write
	 * back to the harness state, and the context menu's commands resolve against an empty registry.
	 *
	 * @param ComposeUiTest test    The running UI test.
	 * @param Harness       harness The state this case shares with its composition.
	 */
	private fun mount(test: ComposeUiTest, harness: Harness) {
		test.setContent {
			UmamoTheme {
				CompositionLocalProvider(
					LocalCommands provides CommandRegistry(),
					LocalKeymap provides Keymap(emptyMap()),
				) {
					WorkspaceTabs(
						workspaces = harness.workspaces,
						activeId = harness.activeId,
						onSelect = { workspaceId -> harness.activeId = workspaceId },
						onCreate = {},
						onDuplicate = { _, _ -> },
						onDelete = {},
						onReorder = { fromIndex, toIndex ->
							val reordered = harness.workspaces.toMutableList()
							reordered.add(toIndex, reordered.removeAt(fromIndex))
							harness.workspaces = reordered
						},
						onRename = { _, _ -> },
					)
				}
			}
		}
	}

	/**
	 * The names of every tab currently showing the inline rename editor.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @return List The editor's text for each open editor (empty when none is open).
	 */
	private fun openEditors(test: ComposeUiTest): List<String> =
		listOf("Alpha", "Bravo", "Charlie").filter { name ->
			test.onAllNodes(hasSetTextAction() and hasText(name)).fetchSemanticsNodes().isNotEmpty()
		}

	/** Two quick presses on a tab open that tab's rename editor. */
	@Test
	fun doubleClickOpensTheClickedTabsEditor() =
		runComposeUiTest {
			val harness = Harness()
			mount(this, harness)
			onNodeWithText("Bravo").performMouseInput {
				click()
				advanceEventTime(100L)
				click()
			}
			waitForIdle()
			assertEquals(listOf("Bravo"), openEditors(this))
		}

	/**
	 * After the order changes underneath a slot, a double-click there renames the workspace the slot now
	 * shows.  The slot's gesture outlives the change (same index, same tab count), so a gesture that captured
	 * its workspace when it started would open the editor on the tab that used to sit there.
	 */
	@Test
	fun doubleClickAfterAReorderRenamesTheTabNowInThatSlot() =
		runComposeUiTest {
			val harness = Harness()
			mount(this, harness)
			// A first click starts slot 0's gesture while it still holds Alpha.
			onNodeWithText("Alpha").performMouseInput { click() }
			waitForIdle()
			mainClock.advanceTimeBy(1_000L)
			harness.workspaces = listOf(harness.workspaces[1], harness.workspaces[0], harness.workspaces[2])
			waitForIdle()
			onNodeWithText("Bravo").performMouseInput {
				click()
				advanceEventTime(100L)
				click()
			}
			waitForIdle()
			assertEquals(listOf("Bravo"), openEditors(this), "the editor opens on the tab that was double-clicked")
		}

	/** A press that became a reorder drag never pairs with the click after it as a double-click. */
	@Test
	fun aClickRightAfterADragIsNotADoubleClick() =
		runComposeUiTest {
			val harness = Harness()
			mount(this, harness)
			onNodeWithText("Alpha").performMouseInput {
				press()
				// Past the reorder hold gate, then a nudge too small to change the order.
				advanceEventTime(150L)
				moveBy(Offset(2f, 0f))
				release()
				advanceEventTime(50L)
				click()
			}
			waitForIdle()
			assertEquals(emptyList(), openEditors(this), "no rename editor opens")
			assertEquals(1, onAllNodesWithText("Alpha").fetchSemanticsNodes().size)
		}

	private companion object {
		/**
		 * A named workspace over a single viewport leaf; the name keeps the label user data, so the tab shows
		 * it verbatim instead of resolving a localized title.
		 *
		 * @param String id   The workspace id.
		 * @param String name The display name.
		 * @return Workspace The workspace.
		 */
		fun workspace(id: String, name: String): Workspace =
			Workspace(id = id, root = LeafArea("leaf-$id", SpaceKind.Viewport2D), name = name)
	}
}