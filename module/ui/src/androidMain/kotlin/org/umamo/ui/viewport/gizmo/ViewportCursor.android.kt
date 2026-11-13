package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset

/**
 * Android actual: touch has no cursor to warp, so a modal transform simply stops at the viewport edge.
 * Always returns null so the caller does not compensate its gesture anchor.
 *
 * @param Float screenX Unused - there is no cursor to move.
 * @param Float screenY Unused - there is no cursor to move.
 * @return Offset? Always null - no warp happens.
 */
actual fun warpViewportCursor(screenX: Float, screenY: Float): Offset? = null