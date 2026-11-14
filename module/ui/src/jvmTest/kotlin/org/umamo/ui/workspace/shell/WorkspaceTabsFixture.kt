package org.umamo.ui.workspace.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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

	/** The registry holding the workspace table and the chrome's, which has the tab navigation. */
	val commands =
		CommandRegistry().also { registry ->
			registry.registerAll(
				workspaceCommands(controller, ShellOverlayState()) { fixtureWorkspaceNames() } +
					chromeCommands(ShellOverlayState(), AreaDragController(), SplitterDragCancelController(), RowDragCancelController(), controller) {},
			)
		}

	/** The workspaces' names, in tab order. */
	val names: List<String> get() = controller.layout.workspaces.map { workspace -> workspace.name ?: workspace.id }

	/**
	 * Mounts the strip.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Keymap keymap The keymap the tab menu's accelerators are read from; unbound unless a case binds one.
	 */
	@OptIn(ExperimentalTestApi::class)
	fun mount(test: ComposeUiTest, keymap: Keymap = Keymap(emptyMap())) {
		test.setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalCommands provides commands, LocalKeymap provides keymap) {
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