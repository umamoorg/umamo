package org.umamo.ui.workspace

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * The hover cursor for a leaf area's drag corners - a four-way move cursor signalling that the corner
 * grabs the area to join it into a neighbour.  expect/actual because the system move cursor is a
 * desktop (java.awt) concept; touch platforms have no hover pointer and return the default.
 *
 * 葉エリアのドラッグ用コーナーのホバーカーソル（四方向の移動カーソル）。デスクトップ概念のため expect/actual。
 *
 * @return PointerIcon The move cursor to show on a corner hover.
 */
expect fun areaMovePointerIcon(): PointerIcon
