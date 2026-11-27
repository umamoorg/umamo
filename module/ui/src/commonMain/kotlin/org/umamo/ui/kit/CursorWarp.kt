package org.umamo.ui.kit

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset

/**
 * Warps the OS pointer to an absolute screen position mid-gesture, so a gesture that runs past an edge can carry on
 * from the other side.  Desktop teleports the cursor through AWT Robot; touch platforms have no cursor and no-op.
 *
 * Returns where the cursor actually landed (read back from the OS), not the requested target, since the
 * platform may round to integer pixels, clamp at a screen edge, or rescale the request (HiDPI).  Null
 * means no warp happened (no cursor, or the platform denied it).
 *
 * @param Float screenX The requested absolute screen x in pixels.
 * @param Float screenY The requested absolute screen y in pixels.
 * @return Offset? The absolute screen position the cursor actually landed at, or null when no warp
 *   happened (platforms or configurations without warp support).
 */
expect fun warpCursor(screenX: Float, screenY: Float): Offset?

/**
 * Supplies the cursor warp a control uses while a drag crosses its seam: [warpCursor] by default, and a recorder
 * under test, so a test never moves the real pointer.
 */
val LocalCursorWarp = staticCompositionLocalOf<(Float, Float) -> Offset?> { { screenX, screenY -> warpCursor(screenX, screenY) } }