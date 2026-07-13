package org.umamo.ui.workspace

import kotlinx.serialization.Serializable

/**
 * One workspace: a named, top-level tab holding an independent area tree. The [id] is a stable,
 * locale-free identity - built-in workspaces use known ids ("modelling", "texture") that the tab
 * strip resolves to a localized title, so the persisted layout never bakes in a display language.
 * (User-created/renamed workspaces, when added, will carry a literal user name - user data, not
 * localized, like part names.)
 *
 * 1 つのワークスペース（最上位タブ）。独立したエリアツリーを持つ。id はロケール非依存の安定識別子。
 *
 * @property String id The stable workspace identity (also the title-resolution key for built-ins).
 * @property AreaNode root The root of this workspace's area tree.
 * @property String? name The user-supplied display name, or null for a built-in whose title is
 *   resolved from [id] (so a built-in's tab label stays localized).  User-created and duplicated
 *   workspaces carry a literal name here - user data, shown verbatim, never localized.
 */
@Serializable
data class Workspace(
	val id: String,
	val root: AreaNode,
	val name: String? = null,
)

/**
 * The whole persisted interface layout: which workspace is active and the ordered set of workspaces.
 * Serialized as one object under the interface.layout settings key. An empty [workspaces] list is the
 * "no layout yet - seed defaults" signal (matches defaultSettings.json's `interface.layout.workspaces: []`).
 *
 * 永続化されるインターフェイスレイアウト全体。interface.layout キーに 1 オブジェクトとして保存する。
 *
 * @property String activeWorkspaceId The id of the workspace currently shown.
 * @property List workspaces The workspaces, in tab order.
 */
@Serializable
data class InterfaceLayout(
	val activeWorkspaceId: String,
	val workspaces: List<Workspace>,
) {
	/**
	 * The active workspace, or the first one if the active id no longer resolves (defensive against a
	 * stale id), or null only when there are no workspaces at all.
	 *
	 * @return Workspace? The workspace to display.
	 */
	fun activeWorkspace(): Workspace? = workspaces.firstOrNull { workspace -> workspace.id == activeWorkspaceId } ?: workspaces.firstOrNull()

	/**
	 * Returns a copy with the active workspace's root replaced - the update path for tree edits (ratio
	 * drags, splits, closes) that the shell applies after the area tree produces a new root.
	 *
	 * @param AreaNode newRoot The replacement root for the active workspace.
	 * @return InterfaceLayout The updated layout (unchanged if there is no active workspace).
	 */
	fun withActiveRoot(newRoot: AreaNode): InterfaceLayout {
		val active = activeWorkspace() ?: return this
		return copy(workspaces = workspaces.map { workspace -> if (workspace.id == active.id) workspace.copy(root = newRoot) else workspace })
	}

	/**
	 * The index of the active workspace in tab order, or 0 if its id no longer resolves - the same
	 * defensive fallback as [activeWorkspace], so navigation and tab highlighting agree.
	 *
	 * @return Int The active workspace's index (0 when empty or the id is stale).
	 */
	fun activeIndex(): Int = workspaces.indexOfFirst { workspace -> workspace.id == activeWorkspaceId }.coerceAtLeast(0)

	/**
	 * Moves the active selection [delta] tabs along the strip, wrapping around either end (so Next from
	 * the last tab lands on the first).  A no-op when there are no workspaces.
	 *
	 * アクティブ選択を delta 個ずらす（端で巻き戻る）。ワークスペースが無ければ何もしない。
	 *
	 * @param Int delta How many tabs to move (negative is leftward / previous).
	 * @return InterfaceLayout The layout with the active id shifted.
	 */
	fun withActiveShiftedBy(delta: Int): InterfaceLayout {
		if (workspaces.isEmpty()) {
			return this
		}
		val count = workspaces.size
		// Kotlin's % can yield a negative result for a leftward delta; the extra + count and second % fold it back.
		val shiftedIndex = ((activeIndex() + delta) % count + count) % count
		return copy(activeWorkspaceId = workspaces[shiftedIndex].id)
	}

	/**
	 * Inserts [workspace] at [atIndex] (clamped into range), optionally making it the active tab.  The
	 * insertion path for new and duplicated workspaces.
	 *
	 * workspace を atIndex に挿入し、必要ならアクティブにする（新規・複製の挿入経路）。
	 *
	 * @param Workspace workspace The workspace to insert.
	 * @param Int atIndex The target index (clamped to 0..size).
	 * @param Boolean activate Whether to switch the active tab to the inserted workspace.
	 * @return InterfaceLayout The layout with the workspace inserted.
	 */
	fun withWorkspaceInserted(workspace: Workspace, atIndex: Int, activate: Boolean): InterfaceLayout {
		val updated = workspaces.toMutableList()
		updated.add(atIndex.coerceIn(0, updated.size), workspace)
		return copy(
			workspaces = updated,
			activeWorkspaceId = if (activate) workspace.id else activeWorkspaceId,
		)
	}

	/**
	 * Removes the workspace with [id].  Refuses to remove the last workspace (the strip never goes
	 * empty), and if the removed workspace was active, moves the active selection to the tab that slides
	 * into its slot (or the new last tab).
	 *
	 * id のワークスペースを削除する。最後の 1 つは削除を拒否し、アクティブを隣のタブへ移す。
	 *
	 * @param String id The workspace id to remove.
	 * @return InterfaceLayout The layout with the workspace removed (unchanged if it was the last or absent).
	 */
	fun withWorkspaceRemoved(id: String): InterfaceLayout {
		if (workspaces.size <= 1) {
			return this
		}
		val removedIndex = workspaces.indexOfFirst { workspace -> workspace.id == id }
		if (removedIndex < 0) {
			return this
		}
		val remaining = workspaces.filterNot { workspace -> workspace.id == id }
		val nextActiveId =
			if (activeWorkspaceId == id) {
				remaining[removedIndex.coerceAtMost(remaining.size - 1)].id
			} else {
				activeWorkspaceId
			}
		return copy(workspaces = remaining, activeWorkspaceId = nextActiveId)
	}

	/**
	 * Moves the workspace at [fromIndex] so it ends up at [toIndex] in tab order, leaving the active
	 * selection unchanged (reordering re-arranges tabs, it does not switch which one is shown).
	 *
	 * fromIndex のワークスペースを toIndex の位置へ移動する（アクティブ選択は変えない）。
	 *
	 * @param Int fromIndex The current index of the workspace to move.
	 * @param Int toIndex The destination index in the final ordering.
	 * @return InterfaceLayout The reordered layout (unchanged if fromIndex is out of range).
	 */
	fun withWorkspacesReordered(fromIndex: Int, toIndex: Int): InterfaceLayout {
		if (fromIndex !in workspaces.indices) {
			return this
		}
		val updated = workspaces.toMutableList()
		val moved = updated.removeAt(fromIndex)
		updated.add(toIndex.coerceIn(0, updated.size), moved)
		return copy(workspaces = updated)
	}

	/**
	 * Sets the display [name] of the workspace with [id] (user data, shown verbatim, overriding any
	 * built-in localized title).  A no-op if no workspace has that id.  The caller is responsible for
	 * rejecting a blank name; this method stores whatever it is given.
	 *
	 * id のワークスペースの表示名を name に設定する（ユーザーデータ。組み込みの既定タイトルを上書きする）。
	 *
	 * @param String id The workspace id to rename.
	 * @param String name The new display name.
	 * @return InterfaceLayout The layout with the workspace renamed.
	 */
	fun withWorkspaceRenamed(id: String, name: String): InterfaceLayout =
		copy(workspaces = workspaces.map { workspace -> if (workspace.id == id) workspace.copy(name = name) else workspace })
}
