package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The AreaScope.spaceState key the Sources space parks its view state under. */
internal const val SOURCES_VIEW_STATE_KEY = "sources.view"

/**
 * The Sources space's search, filter, and refresh state, shared between its area-header controls and
 * its body (sibling subtrees, so it lives on the hosting AreaScope via spaceState).  Lifetime follows
 * the leaf area, like the outliner's; in-memory on purpose.
 */
internal class SourcesViewState {
	/** The name-search query; blank shows the whole table. */
	var query by mutableStateOf("")

	/** The kinds of row the table shows, each toggled on its own; every kind by default. */
	var filters by mutableStateOf(SourcesFilter.entries.toSet())

	/** Whether every kind is shown, so the filter chip reads as unfiltered. */
	val isUnfiltered: Boolean get() = filters.size == SourcesFilter.entries.size

	/**
	 * Shows or hides one kind of row.
	 *
	 * @param SourcesFilter filter The kind.
	 * @param Boolean       shown  Whether to show it.
	 */
	fun setShown(filter: SourcesFilter, shown: Boolean) {
		filters = if (shown) filters + filter else filters - filter
	}

	/** Bumped by the header's Refresh, so the file-presence probe runs again over every source. */
	var refreshSerial by mutableStateOf(0)
}