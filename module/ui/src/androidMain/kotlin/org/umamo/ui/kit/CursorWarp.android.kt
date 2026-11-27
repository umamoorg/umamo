package org.umamo.ui.kit

import androidx.compose.ui.geometry.Offset

/**
 * Android actual: touch has no cursor to warp, so a gesture past an edge carries on without one.
 * Always returns null so the caller does not compensate its gesture anchor.
 *
 * @param Float screenX Unused - there is no cursor to move.
 * @param Float screenY Unused - there is no cursor to move.
 * @return Offset? Always null - no warp happens.
 */
actual fun warpCursor(screenX: Float, screenY: Float): Offset? = null