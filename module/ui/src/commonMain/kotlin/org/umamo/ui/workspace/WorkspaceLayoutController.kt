package org.umamo.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Owns the shell's workspace layout state and every operation that rewrites it: the area-tree
 * structural edits, the workspace CRUD behind the tab strip and its context menu, and the import
 * paths.  Each write publishes the new layout through [onLayoutChange] (the persistence hook), and a
 * write that disposes or re-homes the focused area subtree also bumps [structuralEditCount], which
 * the shell's focus-reclaim effect watches - a join/split (or a workspace switch/delete invoked from
 * a focusable popup) would otherwise leave focus null and silently kill all keyboard shortcuts until
 * the next click.  (Reorder and rename are exempt: a reorder is a pointer drag that never holds
 * focus, and a rename ends in the inline editor's own close-time reclaim.)
 *
 * シェルのワークスペース配置状態と、それを書き換える全操作（エリアツリー編集・タブの CRUD・
 * インポート）を持つ。書き込みは onLayoutChange で永続化され、フォーカスを壊しうる編集は
 * structuralEditCount を進めてシェルの再フォーカス処理を起こす。
 */
internal class WorkspaceLayoutController(
	initialLayout: InterfaceLayout,
	private val onLayoutChange: (InterfaceLayout) -> Unit,
) {
	/** The live layout every shell chrome piece renders from. */
	var layout: InterfaceLayout by mutableStateOf(initialLayout)
		private set

	/** Bumped on every focus-hazardous edit; the shell's refocus effect keys on it. */
	var structuralEditCount: Int by mutableStateOf(0)
		private set

	/**
	 * Publishes [newLayout]: swaps the live state, notifies the persistence hook, and bumps the
	 * structural counter when the edit can strand focus.
	 *
	 * @param InterfaceLayout newLayout The layout to publish.
	 * @param Boolean structural Whether the edit may have disposed the focused subtree.
	 */
	private fun publish(newLayout: InterfaceLayout, structural: Boolean) {
		layout = newLayout
		onLayoutChange(newLayout)
		if (structural) {
			structuralEditCount++
		}
	}

	/**
	 * Applies a structural edit to the active workspace's area tree and republishes the layout.
	 *
	 * @param AreaCommand command The structural edit.
	 */
	fun applyAreaCommand(command: AreaCommand) {
		val root = layout.activeWorkspace()?.root ?: return
		publish(layout.withActiveRoot(reduce(root, command)), structural = true)
	}

	/**
	 * Replaces the active workspace's root from a ratio drag (the tree's onNodeChange path).
	 *
	 * @param AreaNode newRoot The updated tree.
	 */
	fun updateActiveRoot(newRoot: AreaNode) {
		publish(layout.withActiveRoot(newRoot), structural = false)
	}

	/**
	 * Activates the workspace [workspaceId] (a tab click; focus stays where it is).
	 *
	 * @param String workspaceId The workspace to activate.
	 */
	fun setActiveWorkspace(workspaceId: String) {
		publish(layout.copy(activeWorkspaceId = workspaceId), structural = false)
	}

	/**
	 * Moves the active selection [delta] tabs along the strip (wrapping), backing workspace.prev/next.
	 *
	 * @param Int delta Tabs to move by (negative = left).
	 */
	fun switchBy(delta: Int) {
		publish(layout.withActiveShiftedBy(delta), structural = true)
	}

	/**
	 * Appends a new single-viewport workspace named from [suggestedName] (deduped) and activates it.
	 *
	 * @param String suggestedName The localized base name to dedupe from.
	 */
	fun create(suggestedName: String) {
		val workspace =
			Workspace(
				id = newWorkspaceId(),
				root = LeafArea(newAreaId(), SpaceKind.Viewport2D),
				name = uniqueWorkspaceName(suggestedName, layout.workspaces),
			)
		publish(layout.withWorkspaceInserted(workspace, layout.workspaces.size, activate = true), structural = true)
	}

	/**
	 * Inserts a copy of [sourceId] (fresh ids via [cloneAreaTree], deduped [suggestedName]) after it.
	 *
	 * @param String sourceId The workspace to copy.
	 * @param String suggestedName The name to dedupe from.
	 */
	fun duplicate(sourceId: String, suggestedName: String) {
		val sourceIndex = layout.workspaces.indexOfFirst { workspace -> workspace.id == sourceId }
		if (sourceIndex < 0) {
			return
		}
		val source = layout.workspaces[sourceIndex]
		val copy =
			Workspace(
				id = newWorkspaceId(),
				root = cloneAreaTree(source.root),
				name = uniqueWorkspaceName(suggestedName, layout.workspaces),
			)
		publish(layout.withWorkspaceInserted(copy, sourceIndex + 1, activate = true), structural = true)
	}

	/**
	 * Removes [targetId] (refused when it is the last workspace) and republishes the layout.
	 *
	 * @param String targetId The workspace to remove.
	 */
	fun delete(targetId: String) {
		publish(layout.withWorkspaceRemoved(targetId), structural = true)
	}

	/**
	 * Moves the workspace at [fromIndex] to [toIndex] in tab order and republishes the layout.
	 *
	 * @param Int fromIndex The dragged tab's index.
	 * @param Int toIndex The destination index.
	 */
	fun reorder(fromIndex: Int, toIndex: Int) {
		publish(layout.withWorkspacesReordered(fromIndex, toIndex), structural = false)
	}

	/**
	 * Sets the display name of [workspaceId] from an inline rename and republishes the layout.
	 *
	 * @param String workspaceId The workspace to rename.
	 * @param String newName The new display name.
	 */
	fun rename(workspaceId: String, newName: String) {
		publish(layout.withWorkspaceRenamed(workspaceId, newName), structural = false)
	}

	/**
	 * Resets the active workspace's area tree to its default: a built-in id ("modelling"/"texture"/
	 * "physics") goes back to its seeded tree, while a user-created workspace collapses to a single 2D
	 * viewport.  Only the active workspace is touched; the others keep their layouts.  Gated behind a
	 * confirm by its command.
	 */
	fun resetActive() {
		val active = layout.activeWorkspace() ?: return
		// defaultLayout() mints fresh area ids on every call, so the built-in root is already collision-free.
		val builtinRoot = defaultLayout().workspaces.firstOrNull { workspace -> workspace.id == active.id }?.root
		val resetRoot = builtinRoot ?: LeafArea(newAreaId(), SpaceKind.Viewport2D)
		publish(layout.withActiveRoot(resetRoot), structural = true)
	}

	/**
	 * Appends [imported] as a fresh tab (re-minted ids, deduped name) and activates it; the import path.
	 *
	 * @param Workspace imported The parsed workspace to append.
	 * @param String fallbackName The localized base name used when the import carries none.
	 */
	fun appendImported(imported: Workspace, fallbackName: String) {
		val copy =
			Workspace(
				id = newWorkspaceId(),
				root = cloneAreaTree(imported.root),
				name = uniqueWorkspaceName(imported.name ?: fallbackName, layout.workspaces),
			)
		publish(layout.withWorkspaceInserted(copy, layout.workspaces.size, activate = true), structural = true)
	}

	/**
	 * Replaces the whole layout with [imported] (import-overwrite); gated behind a confirm by its command.
	 *
	 * @param InterfaceLayout imported The parsed layout to apply.
	 */
	fun applyImported(imported: InterfaceLayout) {
		publish(imported, structural = true)
	}
}
