package org.umamo.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.ui.viewport.ViewportOverlayState

/**
 * The registry from a work-surface area id to its live [ViewportOverlayState]: each 2D viewport and UV
 * editor space registers its area's state for the area's lifetime, and the shell's overlay and snap
 * commands resolve the hovered area here at dispatch time.  One hub serves both surfaces, so the resolver
 * is a single forArea(hoveredArea) lookup with no per-space branch, and a future work surface joins by
 * registering its own state.
 */
internal typealias AreaOverlayHub = AreaRegistry<ViewportOverlayState>

/**
 * The shell's area overlay hub, or null outside an editor shell (previews, tests).  Work surfaces register
 * their areas; the overlay commands resolve the hovered area at dispatch.
 */
internal val LocalAreaOverlayHub = staticCompositionLocalOf<AreaOverlayHub?> { null }