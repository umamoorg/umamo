package org.umamo.ui.workspace.shell

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.area.AreaDragController
import org.umamo.ui.workspace.area.SplitterDragCancelController
import org.umamo.ui.workspace.commands.chromeCommands
import org.umamo.ui.workspace.commands.fixtureWorkspaceNames
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.workspaceCommands
import org.umamo.ui.workspace.layout.InterfaceLayout
import org.umamo.ui.workspace.layout.LeafArea
import org.umamo.ui.workspace.layout.Workspace
import org.umamo.ui.workspace.layout.WorkspaceLayoutController
import org.umamo.ui.workspace.rowdrag.LocalRowDragCancel
import org.umamo.ui.workspace.rowdrag.RowDragCancelController

/**
 * The tab strip over a real layout and the real workspace commands, wired the way the shell wires it: the strip
 * reads the layout and the tab being renamed, and what is done to a workspace goes through the registry.
 *
 * @param List<String> names The workspaces' names, in tab order; each id is its name in lower case, and the first is
 *   the active workspace.
 */
internal class WorkspaceTabsFixture(names: List<String> = listOf("Alpha", "Bravo", "Charlie")) {
	/** The layout the strip shows and the commands rewrite. */
	val controller =
		WorkspaceLayoutController(
			InterfaceLayout(
				activeWorkspaceId = names.first().lowercase(),
				workspaces = names.map { name -> Workspace(id = name.lowercase(), root = LeafArea("leaf-${name.lowercase()}", SpaceKind.Viewport2D), name = name) },
			),
		) {}

	/** The shell's drag-cancel seam, where a tab drag parks its Escape cancel. */
	val rowDragCancel = RowDragCancelController()

	/** The registry holding the workspace table and the chrome's, which has the tab navigation and the drag cancel. */
	val commands =
		CommandRegistry().also { registry ->
			registry.registerAll(
				workspaceCommands(controller, ShellOverlayState()) { fixtureWorkspaceNames() } +
					chromeCommands(ShellOverlayState(), AreaDragController(), SplitterDragCancelController(), rowDragCancel, controller) {},
			)
		}

	/** Focuses the root, which previews every key through the shell's modal ladder. */
	private val rootFocus = FocusRequester()

	/** The workspaces' names, in tab order. */
	val names: List<String> get() = controller.layout.workspaces.map { workspace -> workspace.name ?: workspace.id }

	/**
	 * Mounts the strip under a root that behaves like the shell's: it holds focus, and the modal ladder previews
	 * every key, so Escape reaches a tab drag the way it does in the app.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Keymap keymap The keymap the tab menu's accelerators are read from; unbound unless a case binds one.
	 */
	@OptIn(ExperimentalTestApi::class)
	fun mount(test: ComposeUiTest, keymap: Keymap = Keymap(emptyMap())) {
		test.setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalCommands provides commands, LocalKeymap provides keymap, LocalRowDragCancel provides rowDragCancel) {
					LaunchedEffect(Unit) { rootFocus.requestFocus() }
					Box(
						modifier =
							Modifier
								.testTag(ROOT_TAG)
								.focusRequester(rootFocus)
								.focusable()
								.onPreviewKeyEvent { event ->
									handleModalKeyLadder(
										event.toShellKeyStroke(),
										ShellModalState(rowDragCancel = rowDragCancel, commandRegistry = commands, keymap = keymap),
									)
								},
					) {
						WorkspaceTabs(
							workspaces = controller.layout.workspaces,
							activeId = controller.layout.activeWorkspaceId,
							renamingId = controller.renamingWorkspaceId,
							onSelect = { workspaceId -> controller.setActiveWorkspace(workspaceId) },
							onReorder = { fromIndex, toIndex -> controller.reorder(fromIndex, toIndex) },
							onRename = { workspaceId, newName -> controller.rename(workspaceId, newName) },
							onRenameCancel = { controller.cancelRename() },
						)
					}
				}
			}
		}
	}

	/**
	 * Presses and releases a key at the focused root, as the shell's window would receive it.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Key key The key.
	 */
	@OptIn(ExperimentalTestApi::class)
	fun pressKey(test: ComposeUiTest, key: Key) {
		test.onNodeWithTag(ROOT_TAG).performKeyInput {
			keyDown(key)
			keyUp(key)
		}
		test.waitForIdle()
	}

	private companion object {
		/** The focused root's test tag. */
		const val ROOT_TAG = "workspace-tabs-root"
	}
}