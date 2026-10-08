package org.umamo.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.ui.viewport.ViewportOverlayState

/**
 * The registry from a work-surface area id to its live [ViewportOverlayState]: each 2D viewport and UV
 * editor space registers its area's state for the area's lifetime, and the shell's overlay commands
 * resolve the hovered area here at dispatch time.  One hub serves both surfaces, so the resolver is a
 * single stateFor(hoveredArea) lookup with no per-space branch, and a future work surface joins by
 * registering its own state.
 */
internal class AreaOverlayHub {
	private val statesByArea = mutableMapOf<String, ViewportOverlayState>()

	/**
	 * Registers one area's overlay state (replacing any prior registration for the id).
	 *
	 * @param String areaId The work-surface leaf's area id.
	 * @param ViewportOverlayState state The area's live overlay state.
	 */
	fun register(areaId: String, state: ViewportOverlayState) {
		statesByArea[areaId] = state
	}

	/**
	 * Removes one area's registration (the area died or switched space).
	 *
	 * @param String areaId The work-surface leaf's area id.
	 */
	fun unregister(areaId: String) {
		statesByArea.remove(areaId)
	}

	/**
	 * The live overlay state of an area, or null when none is registered under the id.
	 *
	 * @param String areaId The work-surface leaf's area id.
	 * @return ViewportOverlayState? The area's overlay state, or null.
	 */
	fun stateFor(areaId: String): ViewportOverlayState? = statesByArea[areaId]
}

/**
 * The shell's area overlay hub, or null outside an editor shell (previews, tests).  Work surfaces register
 * their areas; the overlay commands resolve the hovered area at dispatch.
 */
internal val LocalAreaOverlayHub = staticCompositionLocalOf<AreaOverlayHub?> { null }