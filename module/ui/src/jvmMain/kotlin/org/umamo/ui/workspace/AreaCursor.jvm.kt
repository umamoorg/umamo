package org.umamo.ui.workspace

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/**
 * Desktop actual: the AWT system move cursor (the same four-way cursor the viewport uses while
 * panning), shown when hovering a leaf area's drag corner.
 *
 * デスクトップ実装：AWT の移動カーソルを返す。
 *
 * @return PointerIcon The AWT-backed move cursor.
 */
actual fun areaMovePointerIcon(): PointerIcon = PointerIcon(Cursor(Cursor.MOVE_CURSOR))
