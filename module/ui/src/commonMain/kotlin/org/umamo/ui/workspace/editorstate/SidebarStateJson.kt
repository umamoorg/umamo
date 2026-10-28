package org.umamo.ui.workspace.editorstate

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.ui.workspace.sidebar.SIDEBAR_DEFAULT_WIDTH
import org.umamo.ui.workspace.sidebar.SIDEBAR_MAX_WIDTH
import org.umamo.ui.workspace.sidebar.SIDEBAR_MIN_WIDTH
import org.umamo.ui.workspace.sidebar.SidebarState

/*
 * The `sidebar` member of a work surface's block, the `viewport` member of an area block
 * (docs/format/UMA.md § 7.3): how an area's SidebarState is written into the editor state and read back,
 * beside the other editor-state serializers.  Extension functions rather than members of the state, as the
 * overlay serializers are.
 */

/** The member name a work surface's block holds its sidebar under (UMA § 7.3). */
internal const val SIDEBAR_MEMBER = "sidebar"

/** The member holding whether the sidebar is open, once the rigger chose. */
private const val OPEN_MEMBER = "open"

/** The member holding the panel stack's width in density-independent pixels. */
private const val WIDTH_MEMBER = "width"

/** The member holding the tab the rigger chose. */
private const val TAB_MEMBER = "tab"

/** The member listing the panels opened against their own default. */
private const val EXPANDED_PANELS_MEMBER = "expandedPanels"

/** The member listing the panels folded against their own default. */
private const val COLLAPSED_PANELS_MEMBER = "collapsedPanels"

/**
 * This state as its surface block's `sidebar` member: a JSON null while everything sits at its default, else
 * an object naming EVERY member, the value where it deviates and a JSON null where it does not, so a member
 * can go back to its default in a file saved as a merge patch (UMA § 7.5).  The open choice is written once
 * the rigger made one, even when it matches the application's default; a tab or panel id that names nothing
 * registered right now is written as it was read.
 *
 * @return JsonElement The member value.
 */
internal fun SidebarState.sidebarJsonOrNull(): JsonElement {
	if (isAtDefaults) {
		return JsonNull
	}

	val (expandedPanels, collapsedPanels) = recordedFoldsOf(expandedByPanel) { panelId -> panelId }

	return buildJsonObject {
		put(OPEN_MEMBER, JsonPrimitive(visibility.choice))
		put(WIDTH_MEMBER, dpOrNull(width, SIDEBAR_DEFAULT_WIDTH))
		put(TAB_MEMBER, JsonPrimitive(activeTabId))
		put(EXPANDED_PANELS_MEMBER, expandedPanels)
		put(COLLAPSED_PANELS_MEMBER, collapsedPanels)
	}
}

/**
 * Takes the sidebar a document was saved with, starting from the defaults so an absent member means its
 * default.  Tolerant like every editor-state reader (UMA § 7.1): a member of the wrong type is skipped, a
 * width that is not above zero is skipped and one out of range is held to the range, and ids that name
 * nothing registered are kept.
 *
 * @param JsonObject? tree The member as the file held it, or null when it is absent or not an object.
 */
internal fun SidebarState.restoreSidebar(tree: JsonObject?) {
	reset()

	if (tree == null) {
		return
	}

	visibility.choice = booleanOf(tree, OPEN_MEMBER)
	width = positiveDpOf(tree[WIDTH_MEMBER], SIDEBAR_MIN_WIDTH, SIDEBAR_MAX_WIDTH) ?: SIDEBAR_DEFAULT_WIDTH
	activeTabId = stringOf(tree, TAB_MEMBER)
	restoreFoldStates(expandedByPanel, tree, EXPANDED_PANELS_MEMBER, COLLAPSED_PANELS_MEMBER)
}