package org.umamo.ui.workspace.spaces

import org.umamo.ui.kit.OverflowRowScope
import org.umamo.ui.kit.SEARCH_FIELD_MIN_WIDTH
import org.umamo.ui.kit.SearchField
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.properties.PROPERTIES_VIEW_STATE_KEY
import org.umamo.ui.properties.PropertiesViewState
import org.umamo.ui.workspace.AreaScope

/**
 * The Properties panel's area-header controls (mounted via SpaceDescriptor.headerContent): the property
 * search field, centered in the header's flexible middle.  Reads and writes the area's shared
 * [PropertiesViewState] so the body filters live and auto-switches to a tab with matches; renders nothing
 * without an open document, matching the other header controls.
 *
 * @param AreaScope scope The hosting area's scope carrying the shared view state.
 */
internal fun OverflowRowScope.propertiesHeaderControls(scope: AreaScope) {
	val viewState = scope.spaceState(PROPERTIES_VIEW_STATE_KEY) { PropertiesViewState() }
	// Flexible gaps on both sides center the search field within the header slot.
	flexibleSpace()
	item("search", minWidth = SEARCH_FIELD_MIN_WIDTH) {
		// The item gates itself on the document: an item that renders nothing measures zero, so the strip
		// empties without the builder needing a CompositionLocal it cannot read.
		if (LocalPuppet.current != null) {
			SearchField(value = viewState.query, onValueChange = { newQuery -> viewState.query = newQuery })
		}
	}
	flexibleSpace()
}