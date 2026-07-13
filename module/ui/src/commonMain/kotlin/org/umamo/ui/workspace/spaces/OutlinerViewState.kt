package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The AreaScope.spaceState key the outliner parks its view state under. */
internal const val OUTLINER_VIEW_STATE_KEY = "outliner.view"

/**
 * The outliner's search and filter state, shared between its area-header controls and its body (they
 * render as sibling subtrees, so this lives on the hosting AreaScope via spaceState rather than in a
 * body-local remember). Lifetime follows the leaf area: it survives switching the space away and
 * back, two outliner areas each get their own instance, and it resets when the leaf closes. In-memory
 * on purpose - not a settings key; the native UMA format's outliner-state tracking is the intended
 * future persistence home.
 */
internal class OutlinerViewState {
	/** The name-search query; blank shows the whole tree. */
	var query by mutableStateOf("")

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
}
