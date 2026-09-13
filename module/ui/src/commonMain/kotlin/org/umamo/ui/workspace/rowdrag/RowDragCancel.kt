package org.umamo.ui.workspace.rowdrag

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A coordination seam between an in-flight panel row drag and the editor shell's Escape precedence,
 * mirroring [org.umamo.ui.kit.InlineEditController].  A panel's drag state is remembered per panel
 * instance and invisible to the shell, yet Escape must reach it: a row drag almost always coexists
 * with a non-empty selection (the press that starts the drag already selected the row), so without
 * this seam the shell's clear-selection Escape branch fires instead of cancelling the drag.  While a
 * drag is in flight the owning panel parks its cancel callback here; the shell checks it before
 * clearing the selection.  Several row-dragging panels (outliner, parameters, sources) across several
 * areas may exist, but one pointer means at most one in-flight drag anywhere, so a single shared slot
 * suffices for all of them.  Holds null whenever no row drag is in flight.  Only the dragging panel
 * should write it.
 *
 * @property Function cancel Cancels the in-flight row drag, or null when none is in flight.
 */
class RowDragCancelController {
	var cancel: (() -> Unit)? by mutableStateOf(null)
}

/**
 * Supplies the [RowDragCancelController] the shell shares with the row-dragging panels under it.
 * Defaults to a standalone instance so a panel hosted without the shell still composes (its drags
 * simply cannot be cancelled from the keyboard).
 */
val LocalRowDragCancel = staticCompositionLocalOf { RowDragCancelController() }

/**
 * Parks this controller's cancel on the shell's seam for as long as a drag is in flight, so Escape
 * aborts the drag instead of falling through to the shell's clear-selection branch.  [RowDragController.isDragging]
 * is snapshot state, so the effect re-keys on drag start and end; disposal also covers the space
 * closing mid-drag.  Every row-dragging space calls this once beside its controller.
 */
@Composable
fun RowDragController<*>.parkCancelOnSeam() {
	val seam = LocalRowDragCancel.current
	DisposableEffect(isDragging) {
		if (isDragging) {
			seam.cancel = { cancel() }
		}
		onDispose {
			seam.cancel = null
		}
	}
}