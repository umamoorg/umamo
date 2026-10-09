package org.umamo.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.ui.viewport.CameraController

/**
 * The registry from a camera-bearing area id to its live [CameraController]: each 2D viewport and UV
 * editor space registers its ops for its area's lifetime, and the shell's view commands resolve the
 * hovered area here at dispatch time.  Both surfaces drive the same per-area render camera, so one hub
 * serves them uniformly - the resolver is a single forArea(hoveredArea) lookup with no per-space branch,
 * and a future camera-bearing space joins simply by registering its own controller.
 */
internal typealias AreaCameraHub = AreaRegistry<CameraController>

/**
 * The shell's area camera hub, or null outside an editor shell (previews, tests).  Camera-bearing
 * spaces register their areas; the view commands resolve the hovered area at dispatch.
 */
internal val LocalAreaCameraHub = staticCompositionLocalOf<AreaCameraHub?> { null }