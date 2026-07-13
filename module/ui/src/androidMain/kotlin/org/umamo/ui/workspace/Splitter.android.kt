package org.umamo.ui.workspace

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Android actual: touch has no hover cursor, so the splitter uses the default pointer icon. (A
 * connected mouse on a tablet still works; Android does not expose dedicated resize cursors here.)
 *
 * Android 実装：タッチにはホバーカーソルが無いため既定を返す。
 *
 * @param SplitOrientation orientation The split's orientation (unused on Android).
 * @return PointerIcon The default pointer icon.
 */
actual fun splitterPointerIcon(orientation: SplitOrientation): PointerIcon = PointerIcon.Default
