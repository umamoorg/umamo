package org.umamo.ui.workspace

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
 * clearing the selection.  Several row-dragging panels (outliner, parameters) across several areas
 * may exist, but one pointer means at most one in-flight drag anywhere, so a single shared slot
 * suffices for all of them.  Holds null whenever no row drag is in flight.  Only the dragging panel
 * should write it.
 *
 * パネルの行ドラッグとシェルの Escape 優先順位をつなぐ仲介。ドラッグ中だけキャンセル関数を預け、
 * シェルは選択解除より先に Escape をここへ回す。ポインタは一つなのでスロットも一つで足りる。
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
