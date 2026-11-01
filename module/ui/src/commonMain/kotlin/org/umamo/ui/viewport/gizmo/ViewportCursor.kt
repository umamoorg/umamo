package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset

/**
 * Warps the OS pointer to an absolute screen position mid-gesture - Blender's cursor-wrap for modal
 * transforms, so a Grab / Scale / Rotate is not interrupted when the pointer reaches the viewport edge.
 * Desktop teleports the cursor via AWT Robot; touch platforms have no cursor and no-op.
 *
 * Returns where the cursor actually landed (read back from the OS), not the requested target, since the
 * platform may round to integer pixels, clamp at a screen edge, or rescale the request (HiDPI).  Null
 * means no warp happened (no cursor, or the platform denied it), and the transform freezes at the edge.
 *
 * @param Float screenX The requested absolute screen x in pixels.
 * @param Float screenY The requested absolute screen y in pixels.
 * @return Offset? The absolute screen position the cursor actually landed at, or null when no warp
 *   happened (platforms or configurations without warp support).
 */
expect fun warpViewportCursor(screenX: Float, screenY: Float): Offset?