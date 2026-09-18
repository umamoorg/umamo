package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.umamo.ui.workspace.PersistentSpaceState
import org.umamo.ui.workspace.foldDeviationsOf
import org.umamo.ui.workspace.restoreFoldStates
import org.umamo.ui.workspace.stringArrayOrNull
import org.umamo.ui.workspace.stringListOf

/** The AreaScope.spaceState key the outliner parks its view state under, and its member in an area block (UMA §7.3). */
internal const val OUTLINER_VIEW_STATE_KEY = "outliner"

/**
 * The outliner's search, filter, and open-branch state, shared between its area-header controls and its body
 * (they render as sibling subtrees, so this lives on the hosting AreaScope via spaceState rather than in a
 * body-local remember).  Two outliner areas each get their own instance, and the instance lives as long as the
 * open document does.  A saved document carries everything here but the search query (UMA §7.3, D32): a file
 * that reopened to last week's filtered list would read as a broken panel.
 */
internal class OutlinerViewState : PersistentSpaceState {
	/** The name-search query; blank shows the whole tree. */
	var query by mutableStateOf("")

	/**
	 * Each branch's open state by stable node id; an absent id follows [outlinerOpensByDefault].  Not keyed on
	 * the puppet: the model changes identity on every edit and undo, and a rename must not fold the tree.
	 */
	val expanded = mutableStateMapOf<String, Boolean>()

	/** Whether part rows are shown. */
	var showParts by mutableStateOf(true)

	/** Whether drawable rows are shown. */
	var showDrawables by mutableStateOf(true)

	/** Whether the Armature deformer hierarchy is shown. */
	var showDeformers by mutableStateOf(true)

	/** Whether the pointer restriction indicator column renders on the rows. */
	var showSelectableColumn by mutableStateOf(true)

	/** Whether the eye restriction indicator column renders on the rows. */
	var showVisibilityColumn by mutableStateOf(true)

	val isUnfiltered: Boolean get() = showParts && showDrawables && showDeformers

	/**
	 * Whether the branch [nodeId] is open.
	 *
	 * @param String nodeId The outliner node id.
	 * @return Boolean True when open.
	 */
	fun isOpen(nodeId: String): Boolean = expanded[nodeId] ?: outlinerOpensByDefault(nodeId)

	/**
	 * The outliner's member of its area block.
	 *
	 * @return JsonObject The member, every known key named.
	 */
	override fun toJson(): JsonObject {
		// UMA §7.3: the fold state is two arrays of node ids, the deviations from "only the root is open".
		val (opened, closed) = foldDeviationsOf(expanded, ::outlinerOpensByDefault)
		return buildJsonObject {
			put("expanded", opened)
			put("collapsed", closed)
			// UMA §7.3: `hidden` names the row kinds filtered out, `hiddenColumns` the restriction columns switched off.
			put("hidden", stringArrayOrNull(listOfNotNull("parts".takeUnless { showParts }, "drawables".takeUnless { showDrawables }, "deformers".takeUnless { showDeformers })))
			put("hiddenColumns", stringArrayOrNull(listOfNotNull("selectable".takeUnless { showSelectableColumn }, "visibility".takeUnless { showVisibilityColumn })))
		}
	}

	/**
	 * Takes the outliner state a document was saved with.
	 *
	 * @param JsonObject tree The member as the file held it.
	 */
	override fun restore(tree: JsonObject) {
		restoreFoldStates(expanded, tree, "expanded", "collapsed")
		val hidden = stringListOf(tree, "hidden").orEmpty()
		showParts = "parts" !in hidden
		showDrawables = "drawables" !in hidden
		showDeformers = "deformers" !in hidden
		val hiddenColumns = stringListOf(tree, "hiddenColumns").orEmpty()
		showSelectableColumn = "selectable" !in hiddenColumns
		showVisibilityColumn = "visibility" !in hiddenColumns
	}
}

/**
 * Whether an outliner branch is open when nothing is recorded for it: only the root is.
 *
 * @param String nodeId The outliner node id.
 * @return Boolean True for the root.
 */
internal fun outlinerOpensByDefault(nodeId: String): Boolean = nodeId == OUTLINER_ROOT_ID