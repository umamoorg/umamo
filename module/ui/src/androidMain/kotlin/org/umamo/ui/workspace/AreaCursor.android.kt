package org.umamo.ui.workspace

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Android actual: touch has no hover cursor, so the drag corner uses the default pointer icon. (The
 * join gesture itself still works via press and drag; only the hover affordance is absent.)
 *
 * Android 実装：タッチにはホバーカーソルが無いため既定を返す。
 *
 * @return PointerIcon The default pointer icon.
 */
actual fun areaMovePointerIcon(): PointerIcon = PointerIcon.Default
