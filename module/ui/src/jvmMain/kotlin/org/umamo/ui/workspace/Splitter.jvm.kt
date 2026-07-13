package org.umamo.ui.workspace

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/**
 * Desktop actual: maps the split orientation to the matching AWT system resize cursor. A Horizontal
 * split is dragged left/right, so it shows the east-west resize cursor; a Vertical split is dragged
 * up/down, so it shows the north-south one.
 *
 * デスクトップ実装：分割の向きを AWT のリサイズカーソルに対応付ける。
 *
 * @param SplitOrientation orientation The split's orientation.
 * @return PointerIcon The AWT-backed resize cursor.
 */
actual fun splitterPointerIcon(orientation: SplitOrientation): PointerIcon =
	when (orientation) {
		SplitOrientation.Horizontal -> PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))
		SplitOrientation.Vertical -> PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))
	}
