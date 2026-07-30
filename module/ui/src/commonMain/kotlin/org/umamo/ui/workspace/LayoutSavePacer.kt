package org.umamo.ui.workspace

/**
 * Paces the layout disk writes around splitter drags: while a drag is held nothing is written (a
 * mid-drag pause would otherwise let the debounce fire on the UI thread mid-gesture), and the drag's
 * release commits the latest layout immediately - exactly one write per drag.  Structural edits
 * (splits, workspace CRUD, imports) still arrive through the debounced path and write as before.
 *
 * Duplicate writes are suppressed by remembering the last-saved layout INSTANCE: the controller
 * publishes a fresh immutable layout per edit, so reference equality is exactly "nothing changed
 * since the last write" (a commit followed by the still-pending debounce of the same instance writes
 * once, and a zero-movement drag writes nothing).
 *
 * スプリッタードラッグ中はレイアウト保存を保留し、離した瞬間に最新レイアウトを 1 回だけ書き込む。
 * 構造編集は従来どおりデバウンス経由で保存される。
 *
 * @property InterfaceLayout initialSavedLayout The layout already persisted at startup (loaded or seeded).
 * @property Function save Performs the actual write (the settings-backed saveLayout).
 */
internal class LayoutSavePacer(
	initialSavedLayout: InterfaceLayout,
	private val save: (InterfaceLayout) -> Unit,
) {
	private var dragActive: Boolean = false
	private var lastSavedLayout: InterfaceLayout = initialSavedLayout

	/**
	 * The debounced-path write: persists [layout] unless a drag is holding writes or this exact
	 * instance is already on disk.
	 *
	 * @param InterfaceLayout layout The layout the debounce settled on.
	 */
	fun saveDebounced(layout: InterfaceLayout) {
		if (dragActive || layout === lastSavedLayout) {
			return
		}
		save(layout)
		lastSavedLayout = layout
	}

	/**
	 * Tracks the splitter-drag gesture: while active, debounced writes are held; on release the
	 * latest layout is committed immediately (skipped when nothing changed since the last write).
	 *
	 * @param Boolean active True at drag start, false at drag end.
	 * @param InterfaceLayout latestLayout The most recently published layout.
	 */
	fun setDragActive(active: Boolean, latestLayout: InterfaceLayout) {
		dragActive = active
		if (!active && latestLayout !== lastSavedLayout) {
			save(latestLayout)
			lastSavedLayout = latestLayout
		}
	}
}
