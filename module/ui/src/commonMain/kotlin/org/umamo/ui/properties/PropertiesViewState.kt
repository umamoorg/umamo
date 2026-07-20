package org.umamo.ui.properties

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The AreaScope.spaceState key the Properties panel parks its view state under. */
internal const val PROPERTIES_VIEW_STATE_KEY = "properties.view"

/**
 * The Properties panel's per-area view state, shared between its header search box and its body (they
 * render as sibling subtrees, so this lives on the hosting AreaScope via spaceState rather than a
 * body-local remember).  Lifetime follows the leaf area: it survives switching the space away and back,
 * two Properties areas each get their own instance, and it resets when the leaf closes.  In-memory on
 * purpose - not a settings key (matching OutlinerViewState).
 */
internal class PropertiesViewState {
	/** The header search query; blank shows every section. */
	var query by mutableStateOf("")

	/** The tab the user last selected; clamped to a visible tab at render time. */
	var activeTab by mutableStateOf(PropertyTabId.Document)

	/**
	 * Per-section expanded state, keyed by section id.  An absent entry defaults to expanded, so a fresh
	 * panel opens with everything unfolded; the map only records deviations the user toggled.
	 */
	val expandedSections = mutableStateMapOf<String, Boolean>()
}
