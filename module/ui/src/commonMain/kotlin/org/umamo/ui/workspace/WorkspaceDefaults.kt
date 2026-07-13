package org.umamo.ui.workspace

import kotlin.random.Random

/**
 * Mints a fresh, unique area id. Uses a random token rather than a global counter so there is no
 * shared mutable state to make tests order-dependent or to reset across runs; uniqueness within a
 * single layout (the only scope that matters) is overwhelmingly assured. Never reused once a leaf is
 * closed, so it is safe as a stable Compose key.
 *
 * 新しい一意なエリア id を生成する。グローバルカウンタを避け乱数トークンを使う。
 *
 * @return String A new area id.
 */
fun newAreaId(): String = "area-" + Random.nextLong().toULong().toString(16)

/**
 * Mints a fresh, unique workspace id.  Like [newAreaId] it uses a random token rather than a counter so
 * there is no shared mutable state, and like an area id it is locale-free and never reused - the
 * persisted layout keys on it, and a user-created workspace's display name lives separately in
 * [Workspace.name] so the id never bakes in a language.
 *
 * 新しい一意なワークスペース id を生成する（ロケール非依存・再利用なし）。
 *
 * @return String A new workspace id.
 */
fun newWorkspaceId(): String = "ws-" + Random.nextLong().toULong().toString(16)

/**
 * Deep-copies an area tree, minting a fresh id for every leaf.  Duplicating a workspace must not reuse
 * the source's leaf ids: those ids are stable Compose keys and the handles the GL-identity machinery
 * keeps a per-area surface alive by, so two workspaces sharing a leaf id would collide.  The space kind
 * and split orientation/ratio are preserved so the copy looks identical.
 *
 * エリアツリーを深くコピーし、各葉に新しい id を割り当てる（複製時の id 衝突を防ぐ）。
 *
 * @param AreaNode node The tree to clone.
 * @return AreaNode A structurally identical tree with all-fresh leaf ids.
 */
fun cloneAreaTree(node: AreaNode): AreaNode =
	when (node) {
		is LeafArea -> LeafArea(newAreaId(), node.space)
		is SplitNode -> node.copy(first = cloneAreaTree(node.first), second = cloneAreaTree(node.second))
	}

/**
 * Resolves a unique display name from [base] given the [existing] workspaces: returns [base] unchanged
 * when no other workspace already uses it, otherwise appends the lowest free " N" suffix ("Workspace",
 * "Workspace 2", "Workspace 3", …).  Only user names ([Workspace.name]) are considered taken; built-ins
 * resolve their title from id and never collide with a stored name.
 *
 * 既存の名前と衝突しない表示名を返す（必要なら「 2」「 3」…を付す）。
 *
 * @param String base The desired base name.
 * @param List existing The current workspaces to avoid colliding with.
 * @return String A name not already used by any existing workspace.
 */
fun uniqueWorkspaceName(base: String, existing: List<Workspace>): String {
	val takenNames = existing.mapNotNull { workspace -> workspace.name }.toSet()
	if (base !in takenNames) {
		return base
	}
	var candidateSuffix = 2
	while ("$base $candidateSuffix" in takenNames) {
		candidateSuffix++
	}
	return "$base $candidateSuffix"
}

/**
 * The seeded default layout used when no saved layout exists. Three workspaces, matching the agreed
 * defaults: "Modelling" is a single 2D viewport; "Texture" is a UV editor beside a 2D viewport;
 * "Physics" is a single 2D viewport.  Modelling is active.  Each built-in seeds name = null so its tab
 * stays localized.
 *
 * @return InterfaceLayout The default three-workspace layout.
 */
fun defaultLayout(): InterfaceLayout {
	val modelling =
		Workspace(
			id = "modelling",
			root = LeafArea(newAreaId(), SpaceKind.Viewport2D),
		)
	val texture =
		Workspace(
			id = "texture",
			root =
				SplitNode(
					orientation = SplitOrientation.Horizontal,
					ratio = 0.5f,
					first = LeafArea(newAreaId(), SpaceKind.UvEditor),
					second = LeafArea(newAreaId(), SpaceKind.Viewport2D),
				),
		)
	val physics =
		Workspace(
			id = "physics",
			root = LeafArea(newAreaId(), SpaceKind.Viewport2D),
		)
	return InterfaceLayout(
		activeWorkspaceId = "modelling",
		workspaces = listOf(modelling, texture, physics),
	)
}
