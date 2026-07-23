package org.umamo.ui.theme

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Android actual: touch has no hover pointer, so there is no cursor to reshape and the art is never
 * rasterized.  (A mouse connected to a tablet still works; Android does not take a custom cursor bitmap
 * through Compose here.)
 *
 * Android 実装：タッチにはホバーカーソルが無いため既定を返す。
 *
 * @param UmamoCursor cursor The cursor definition (unused on Android).
 * @return PointerIcon The default pointer icon.
 */
actual fun umamoPointerIcon(cursor: UmamoCursor): PointerIcon = PointerIcon.Default

/**
 * Android actual: touch has no cursor to hide, so the default pointer icon is returned.
 *
 * Android 実装：タッチにはカーソルが無いため既定を返す。
 *
 * @return PointerIcon The default pointer icon.
 */
actual fun hiddenPointerIcon(): PointerIcon = PointerIcon.Default
