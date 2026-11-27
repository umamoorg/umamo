package org.umamo.ui.workspace.spaces.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.umamo.runtime.model.ArtSourceId
import org.umamo.ui.workspace.PersistentSpaceState
import org.umamo.ui.workspace.editorstate.foldDeviationsOf
import org.umamo.ui.workspace.editorstate.restoreFoldStates
import org.umamo.ui.workspace.editorstate.stringArrayOrNull
import org.umamo.ui.workspace.editorstate.stringListOf

/** The AreaScope.spaceState key the Sources space parks its view state under, and its member in an area block (UMA §7.3). */
internal const val SOURCES_VIEW_STATE_KEY = "sources"

/**
 * The Sources space's search, filter, open-row, and refresh state, shared between its area-header controls and
 * its body (sibling subtrees, so it lives on the hosting AreaScope via spaceState).  It lives as long as the open
 * document does, like the outliner's, and a saved document carries its filters and open rows (UMA §7.3).
 */
internal class SourcesViewState : PersistentSpaceState {
	/** The name-search query; blank shows the whole table. */
	var query by mutableStateOf("")

	/**
	 * Whether a search is running.  While one is, every row shows open and a press on a row or its chevron
	 * folds nothing.
	 */
	val searching: Boolean get() = query.isNotBlank()

	/** Each row's open state by node id; an absent id follows [sourcesOpensByDefault]. */
	val expanded = mutableStateMapOf<String, Boolean>()

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

	/**
	 * Where each artwork file stood the last time it was asked about; a file with no entry has not been
	 * answered for yet and reads unknown.  Replaced whole when every answer of a round is in, so a file
	 * keeps its last answer while the next is on its way.  Kept here, not in the body, so a Sources space
	 * opened again in its area shows what it last knew; never saved with the document, since a file's
	 * presence is a fact about the disk now.
	 */
	var presenceBySource: Map<ArtSourceId, SourcePresence> by mutableStateOf(emptyMap())

	/**
	 * Whether the row [nodeId] is open.
	 *
	 * @param String nodeId The Sources node id.
	 * @return Boolean True when open.
	 */
	fun isOpen(nodeId: String): Boolean = expanded[nodeId] ?: sourcesOpensByDefault(nodeId)

	/**
	 * Opens the row [nodeId] if it is closed, and closes it if it is open.  Does nothing while a search
	 * runs: every row shows open then, so a press has nothing to fold and must not write a fold the rigger
	 * cannot see change.
	 *
	 * @param String nodeId The Sources node id.
	 */
	fun toggleFold(nodeId: String) {
		if (searching) {
			return
		}

		expanded[nodeId] = !isOpen(nodeId)
	}

	/**
	 * Opens the row [nodeId] when it is closed, and writes nothing when it is open already.  A fold written
	 * for a row that is open by default adds a key the map did not hold, and everything keyed on the map is
	 * built again for a table that looks the same.
	 *
	 * Unlike [toggleFold] this writes during a search too.  It is what a drop opens the row it lands in
	 * with, and the row has to be open once the search is gone.
	 *
	 * @param String nodeId The Sources node id.
	 */
	fun open(nodeId: String) {
		if (!isOpen(nodeId)) {
			expanded[nodeId] = true
		}
	}

	/**
	 * The Sources space's member of its area block.
	 *
	 * @return JsonObject The member, every known key named.
	 */
	override fun toJson(): JsonObject {
		val (opened, closed) = foldDeviationsOf(expanded, ::sourcesOpensByDefault)

		return buildJsonObject {
			put("expanded", opened)
			put("collapsed", closed)
			// UMA §7.3: `hidden` names the row kinds filtered out.
			put("hidden", stringArrayOrNull(SourcesFilter.entries.filter { filter -> filter !in filters }.map(::sourcesFilterWireName)))
		}
	}

	/**
	 * Takes the Sources state a document was saved with.
	 *
	 * @param JsonObject tree The member as the file held it.
	 */
	override fun restore(tree: JsonObject) {
		restoreFoldStates(expanded, tree, "expanded", "collapsed")
		val hidden = stringListOf(tree, "hidden").orEmpty()
		filters = SourcesFilter.entries.filter { filter -> sourcesFilterWireName(filter) !in hidden }.toSet()
	}
}

/**
 * Whether a Sources row is open when nothing is recorded for it: files and the unbound group are, layers and
 * tiles are not.
 *
 * @param String nodeId The Sources node id.
 * @return Boolean True when open by default.
 */
internal fun sourcesOpensByDefault(nodeId: String): Boolean = nodeId.startsWith("source:") || nodeId == SOURCES_UNBOUND_GROUP_ID

/**
 * A row-kind filter's name in the editor entry.
 *
 * @param SourcesFilter filter The filter.
 * @return String The wire name.
 */
internal fun sourcesFilterWireName(filter: SourcesFilter): String =
	// UMA §7.3: sources `hidden`.
	when (filter) {
		SourcesFilter.Bound -> "bound"
		SourcesFilter.Unbound -> "unbound"
		SourcesFilter.Missing -> "missing"
		SourcesFilter.NeedsReview -> "needsReview"
	}