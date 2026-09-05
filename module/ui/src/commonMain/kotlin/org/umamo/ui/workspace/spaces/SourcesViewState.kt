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

	/** Which rows the table shows. */
	var filter by mutableStateOf(SourcesFilter.All)

	/** Bumped by the header's Refresh, so the file-presence probe runs again over every source. */
	var refreshSerial by mutableStateOf(0)
}