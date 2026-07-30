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
 * @property InterfaceLayout initialSavedLayout The layout already persisted at startup (loaded or seeded).
 * @property Function save Performs the actual write (the settings-backed saveLayout).
 */
internal class LayoutSavePacer(
	initialSavedLayout: InterfaceLayout,
	private val save: (InterfaceLayout) -> Unit,
) {
	// A depth counter rather than a boolean: the shared shell targets touch tablets, where two
	// fingers can in principle hold two splitters at once - a boolean would resume writes when the
	// FIRST drag released.  Clamped at zero so an unmatched release (the disposal guard's defensive
	// signal) can never wedge the counter negative.
	private var activeDragCount: Int = 0
	private var lastSavedLayout: InterfaceLayout = initialSavedLayout

	/**
	 * The debounced-path write: persists [layout] unless a drag is holding writes or this exact
	 * instance is already on disk.
	 *
	 * @param InterfaceLayout layout The layout the debounce settled on.
	 */
	fun saveDebounced(layout: InterfaceLayout) {
		if (activeDragCount > 0 || layout === lastSavedLayout) {
			return
		}
		save(layout)
		lastSavedLayout = layout
	}

	/**
	 * Tracks the splitter-drag gestures: while any is active, debounced writes are held; the release
	 * of the LAST one commits the latest layout immediately (skipped when nothing changed since the
	 * last write).
	 *
	 * @param Boolean active True at a drag start, false at a drag end.
	 * @param InterfaceLayout latestLayout The most recently published layout.
	 */
	fun setDragActive(active: Boolean, latestLayout: InterfaceLayout) {
		activeDragCount =
			if (active) {
				activeDragCount + 1
			} else {
				maxOf(0, activeDragCount - 1)
			}
		if (activeDragCount == 0 && latestLayout !== lastSavedLayout) {
			save(latestLayout)
			lastSavedLayout = latestLayout
		}
	}
}
