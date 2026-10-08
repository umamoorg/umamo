package org.umamo.ui.viewport

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/*
 * What the 2D viewport and the UV editor draw OVER the rigger's art, per area: ViewportOverlayColors.kt
 * holds the colors those overlays draw in, and this file holds whether each of them is shown.  The state
 * is an area's (two viewports may show different overlays, as in Blender), parked on the hosting AreaScope
 * by the space body, written into the document's editor state as the area block's `overlays` member
 * (docs/format/UMA.md § 7.3), and read by the overlays themselves through LocalAreaOverlays.
 */

/** Which work surface an overlay state belongs to; decides which overlays exist for it (a UV editor has no world axes). */
enum class OverlaySurface {
	Viewport2D,
	UvEditor,
}

/**
 * One area's overlay visibility: the Show Overlays master plus one flag per overlay.  The master gates
 * every overlay's EFFECT while leaving each flag as the rigger set it, so switching it back on restores the
 * set they had (Blender's overlays toggle).  Consumers read the effective values, never the raw flags, so
 * nothing downstream has to know a master exists.
 *
 * The wireframe flag is saved with the rest; the renderer reads it once the Object-mode wireframe exists.
 *
 * @param OverlaySurface surface The work surface this state belongs to.
 */
class ViewportOverlayState(val surface: OverlaySurface) {
	/** The Show Overlays master: false hides every overlay of the area at once. */
	var showOverlays by mutableStateOf(true)

	/** The backdrop grid lines. */
	var showGrid by mutableStateOf(true)

	/** The world axis lines (the 2D viewport only). */
	var showAxes by mutableStateOf(true)

	/** The 2D cursor marker (the UV cursor in a UV editor). */
	var showCursor by mutableStateOf(true)

	/** The informational text: the active-mesh label and the zoom readout. */
	var showInfo by mutableStateOf(true)

	/** The Object-mode wireframe of every shown mesh (the 2D viewport only); off by default, as in Blender. */
	var showWireframe by mutableStateOf(false)

	/** Whether the grid lines draw: the flag under the master. */
	val effectiveGrid: Boolean
		get() = showOverlays && showGrid

	/** Whether the world axes draw: the flag under the master. */
	val effectiveAxes: Boolean
		get() = showOverlays && showAxes

	/** Whether the cursor marker draws: the flag under the master. */
	val effectiveCursor: Boolean
		get() = showOverlays && showCursor

	/** Whether the informational text shows: the flag under the master. */
	val effectiveInfo: Boolean
		get() = showOverlays && showInfo

	/** Whether the Object-mode wireframe draws: the flag under the master. */
	val effectiveWireframe: Boolean
		get() = showOverlays && showWireframe

	/** Whether every flag sits at its default: what a fresh area shows, and what a save writes as nothing. */
	val isAtDefaults: Boolean
		get() = showOverlays && showGrid && showAxes && showCursor && showInfo && !showWireframe

	/**
	 * Returns every flag to its default.
	 */
	fun reset() {
		showOverlays = true
		showGrid = true
		showAxes = true
		showCursor = true
		showInfo = true
		showWireframe = false
	}
}

/**
 * The hosting area's overlay state, provided by the space body around its overlay stack; null outside an
 * area (a standalone shell, previews, tests), where every overlay shows.  Static: the instance is the
 * area's for its life and is never swapped, and its flags are Compose state, so a flip recomposes its readers.
 */
val LocalAreaOverlays = staticCompositionLocalOf<ViewportOverlayState?> { null }