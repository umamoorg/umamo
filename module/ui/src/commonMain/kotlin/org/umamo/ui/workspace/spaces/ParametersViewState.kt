package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId

/** The AreaScope.spaceState key the parameters panel parks its view state under. */
internal const val PARAMETERS_VIEW_STATE_KEY = "parameters.view"

/**
 * The parameters panel's view state, parked on the hosting AreaScope via spaceState. Lifetime follows
 * the leaf area: it survives switching the space away and back, two parameters areas each get their
 * own instance, and it resets when the leaf closes. In-memory on purpose - not a settings key; the
 * native UMA format is the intended future persistence home.
 */
internal class ParametersViewState {
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
}
