package org.umamo.ui.properties

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.ui.workspace.PersistentSpaceState
import org.umamo.ui.workspace.finiteFloatOf
import org.umamo.ui.workspace.stringArrayOrNull
import org.umamo.ui.workspace.stringListOf
import org.umamo.ui.workspace.stringOf

/** The AreaScope.spaceState key the Properties panel parks its view state under, and its member in an area block (UMA §7.3). */
internal const val PROPERTIES_VIEW_STATE_KEY = "properties"

/**
 * The Properties panel's per-area view state, shared between its header search box and its body (they
 * render as sibling subtrees, so this lives on the hosting AreaScope via spaceState rather than a
 * body-local remember).  Two Properties areas each get their own instance, and the instance lives as long as
 * the open document does.  A saved document carries the tab, the folded sections, and the relation lists'
 * heights (UMA §7.3), and not the search query.
 */
internal class PropertiesViewState : PersistentSpaceState {
	/** The header search query; blank shows every section. */
	var query by mutableStateOf("")

	/** The tab the user last selected; clamped to a visible tab at render time. */
	var activeTab by mutableStateOf(PropertyTabId.Document)

	/**
	 * Per-section expanded state, keyed by section id.  An absent entry defaults to expanded, so a fresh
	 * panel opens with everything unfolded; the map only records deviations the user toggled.
	 */
	val expandedSections = mutableStateMapOf<String, Boolean>()

	/**
	 * Each relation list's dragged height, keyed by the list's name ("maskedBy").  Held here rather than in the
	 * list because the list leaves the composition on every tab change, section fold, and selection change, and
	 * a height that reset each time would not be worth dragging.  An absent entry is the list's default.
	 */
	val listHeights = mutableStateMapOf<String, Dp>()

	/**
	 * The Properties panel's member of its area block.
	 *
	 * @return JsonObject The member, every known key named.
	 */
	override fun toJson(): JsonObject =
		buildJsonObject {
			put("tab", if (activeTab == PropertyTabId.Document) JsonNull else JsonPrimitive(propertyTabWireName(activeTab)))
			put("collapsedSections", stringArrayOrNull(expandedSections.filterValues { open -> !open }.keys))
			// UMA §7.3: keyed by a fixed list name, not an object id, so an object is safe under the merge (UMA §7.5).
			// A null for the whole member clears every height at once when none is held.
			put("listHeights", if (listHeights.isEmpty()) JsonNull else buildJsonObject { listHeights.keys.sorted().forEach { listName -> put(listName, JsonPrimitive(listHeights.getValue(listName).value)) } })
		}

	/**
	 * Takes the Properties state a document was saved with.
	 *
	 * @param JsonObject tree The member as the file held it.
	 */
	override fun restore(tree: JsonObject) {
		activeTab = stringOf(tree, "tab")?.let { wireName -> PropertyTabId.entries.firstOrNull { tab -> propertyTabWireName(tab) == wireName } } ?: PropertyTabId.Document
		expandedSections.clear()
		stringListOf(tree, "collapsedSections")?.forEach { sectionId -> expandedSections[sectionId] = false }
		listHeights.clear()
		(tree["listHeights"] as? JsonObject)?.forEach { (listName, height) ->
			finiteFloatOf(height)?.takeIf { value -> value > 0f }?.let { value -> listHeights[listName] = value.dp }
		}
	}
}

/**
 * The view state of the Properties area a row is rendering in, or null outside one (previews, tests), where a row
 * keeps such state to itself.
 */
internal val LocalPropertiesViewState = compositionLocalOf<PropertiesViewState?> { null }

/**
 * A Properties tab's name in the editor entry.
 *
 * @param PropertyTabId tab The tab.
 * @return String The wire name.
 */
internal fun propertyTabWireName(tab: PropertyTabId): String =
	// UMA §7.3: properties `tab`.
	when (tab) {
		PropertyTabId.Document -> "document"
		PropertyTabId.Object -> "object"
		PropertyTabId.Data -> "data"
	}