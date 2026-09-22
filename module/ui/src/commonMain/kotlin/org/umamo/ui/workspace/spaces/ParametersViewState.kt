package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.workspace.PersistentSpaceState
import org.umamo.ui.workspace.booleanOf
import org.umamo.ui.workspace.stringArrayOrNull
import org.umamo.ui.workspace.stringListOf

/** The AreaScope.spaceState key the parameters panel parks its view state under, and its member in an area block (UMA §7.3). */
internal const val PARAMETERS_VIEW_STATE_KEY = "parameters"

/**
 * The parameters panel's view state, parked on the hosting AreaScope via spaceState.  Two parameters areas
 * each get their own instance, and the instance lives as long as the open document does.  A saved document
 * carries the group folds, the open range editors, and the selection filter (UMA §7.3); the search query
 * and the two in-place rename slots are gestures in flight and are not.
 */
internal class ParametersViewState : PersistentSpaceState {
	/**
	 * The name / id search query; blank shows every parameter the other filters leave.  Deliberately not
	 * carried by a saved document, for the reason [OutlinerViewState.query] is not: a file that reopened
	 * to last week's filtered list reads as a broken panel.
	 */
	var query by mutableStateOf("")

	/**
	 * Parameter islands whose range editor is open, keyed by the island's primary parameter id (a pad
	 * keys on its horizontal member, so the state survives the param <-> pair row-identity change on
	 * link / unlink). Multiple islands may be open at once, so range editors on different parameters
	 * never contend for one shared slot.
	 */
	val openRangeEditors = mutableStateMapOf<ParameterId, Boolean>()

	/**
	 * Which parameter groups are expanded, keyed by group id; an absent entry falls back to the group's
	 * imported initiallyOpen. Parked here rather than in a remember(puppet) so it survives every model
	 * edit - a link, an undo, a rename each mint a new PuppetModel instance that would otherwise reset it
	 * and collapse an open group. A newly created group has no entry and is minted initiallyOpen = true,
	 * so it defaults open.
	 */
	val expandedGroups = mutableStateMapOf<ParameterGroupId, Boolean>()

	/**
	 * The group whose header is being renamed in place, or null. Parked here (not remember(puppet), not
	 * body-local) because the New Group header button - a sibling subtree - opens rename on the group it
	 * just created, and a rename commit mints a new PuppetModel that must not yank this out mid-edit.
	 */
	var renamingGroupId: ParameterGroupId? by mutableStateOf(null)

	/**
	 * The parameter whose name is being renamed in place, or null. Parked here for the same reasons as
	 * [renamingGroupId]: the Add Parameter header button (a sibling subtree) opens rename on the parameter
	 * it just created, and every rename / create commit mints a new PuppetModel that must not yank the
	 * in-place editor out mid-edit. At most one of this and [renamingGroupId] is non-null at a time.
	 */
	var renamingParameterId: ParameterId? by mutableStateOf(null)

	/**
	 * When true, the panel shows only the parameters that affect the current selection (a drawable's own
	 * keyform axes plus every parent deformer up its chain; a deformer's own plus its ancestors'; a part's
	 * draw-order axes plus its member drawables' effective sets). Off by default. With nothing selected the
	 * filter is inert (the whole list shows), so the panel is never mysteriously empty.
	 */
	var showOnlySelected: Boolean by mutableStateOf(false)

	/**
	 * The parameters panel's member of its area block.
	 *
	 * @return JsonObject The member, every known key named.
	 */
	override fun toJson(): JsonObject =
		buildJsonObject {
			// UMA §7.3: a group the rigger opened or closed; any other group follows its initiallyOpen (UMA §4.3).
			put("expandedGroups", stringArrayOrNull(expandedGroups.filterValues { open -> open }.keys.map { groupId -> groupId.raw }))
			put("collapsedGroups", stringArrayOrNull(expandedGroups.filterValues { open -> !open }.keys.map { groupId -> groupId.raw }))
			put("openRangeEditors", stringArrayOrNull(openRangeEditors.filterValues { open -> open }.keys.map { parameterId -> parameterId.raw }))
			put("onlySelected", if (showOnlySelected) JsonPrimitive(true) else JsonNull)
		}

	/**
	 * Takes the parameters state a document was saved with.
	 *
	 * @param JsonObject tree The member as the file held it.
	 */
	override fun restore(tree: JsonObject) {
		expandedGroups.clear()
		stringListOf(tree, "expandedGroups")?.forEach { raw -> expandedGroups[ParameterGroupId(raw)] = true }
		stringListOf(tree, "collapsedGroups")?.forEach { raw -> expandedGroups[ParameterGroupId(raw)] = false }
		openRangeEditors.clear()
		stringListOf(tree, "openRangeEditors")?.forEach { raw -> openRangeEditors[ParameterId(raw)] = true }
		showOnlySelected = booleanOf(tree, "onlySelected") ?: false
	}
}