package org.umamo.ui.workspace.area

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A coordination seam between an in-flight divider (splitter) drag and the editor shell's Escape
 * precedence, mirroring [org.umamo.ui.workspace.rowdrag.RowDragCancelController].
 *
 * A divider drag keeps its [SplitterDragSession] in the dragged SplitContainer's own remembered state,
 * which the shell cannot see - unlike an area CORNER drag, whose state lives in the shell-level
 * [AreaDragController] and which `area.dragCancel` can abort.  Without this seam Escape mid-drag would
 * fall past every arm of the modal ladder to the bottom, which in Object mode clears the selection while
 * the user is merely resizing a panel.
 *
 * Nested splits mean many SplitContainers may be composed at once, but one pointer means at most one
 * drag in flight, so a single shared slot serves them all.  Unlike the row-drag seam the owner clears
 * it by identity, because a sibling container recomposing must not blank the dragging one's callback.
 * Holds null whenever no divider drag is in flight.
 *
 * @property Function cancel Cancels the in-flight divider drag, or null when none is in flight.
 */
class SplitterDragCancelController {
	var cancel: (() -> Unit)? by mutableStateOf(null)
}

/**
 * Supplies the [SplitterDragCancelController] the shell shares with the area tree beneath it.  Defaults
 * to a standalone instance so an area tree hosted without the shell still composes (its divider drags
 * simply cannot be cancelled from the keyboard).
 */
val LocalSplitterDragCancel = staticCompositionLocalOf { SplitterDragCancelController() }