package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon

/**
 * Android actual: touch has no hover cursor, so panning uses the default pointer icon.
 *
 * Android 実装：タッチにはホバーカーソルが無いため既定を返す。
 *
 * @return PointerIcon The default pointer icon.
 */
actual fun grabPanPointerIcon(): PointerIcon = PointerIcon.Default

/**
 * Android actual: touch has no cursor to warp, so a modal transform simply stops at the viewport edge.
 * Always returns null so the caller does not compensate its gesture anchor.
 *
 * Android 実装：タッチにはカーソルが無いため何もしない。
 *
 * @param Float screenX Unused - there is no cursor to move.
 * @param Float screenY Unused - there is no cursor to move.
 * @return Offset? Always null - no warp happens.
 */
actual fun warpViewportCursor(screenX: Float, screenY: Float): Offset? = null

/**
 * Android actual: touch has no cursor to hide, so the default pointer icon is returned.
 *
 * Android 実装：タッチにはカーソルが無いため既定を返す。
 *
 * @return PointerIcon The default pointer icon.
 */
actual fun hiddenPointerIcon(): PointerIcon = PointerIcon.Default
