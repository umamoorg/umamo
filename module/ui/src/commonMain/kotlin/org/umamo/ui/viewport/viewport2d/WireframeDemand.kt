package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState

/**
 * Which 2D viewport areas of one document ask for the wireframe, reduced to the one fact the overlay publish
 * needs: whether any does.  The mesh overlay is one value for every area, so the wireframe is derived and
 * uploaded while at least one area shows it and not at all otherwise; each area then draws it or not per
 * frame through its own render options.  Written from the UI thread by [WireframeDemandPublisher], read by
 * the publish coroutine through [wanted].
 */
internal class WireframeDemand {
	private val wantingAreas = HashSet<String>()
	private val wantedBacking = MutableStateFlow(false)

	/** Whether any registered area asks for the wireframe. */
	val wanted: StateFlow<Boolean>
		get() = wantedBacking

	/**
	 * Records one area's ask.
	 *
	 * @param String areaId The area.
	 * @param Boolean wanted Whether the area asks for the wireframe.
	 */
	fun set(areaId: String, wanted: Boolean) {
		if (wanted) {
			wantingAreas.add(areaId)
		} else {
			wantingAreas.remove(areaId)
		}
		wantedBacking.value = wantingAreas.isNotEmpty()
	}

	/**
	 * Drops one area's ask (the area died or switched space).
	 *
	 * @param String areaId The area.
	 */
	fun remove(areaId: String) {
		set(areaId, false)
	}
}

/**
 * Keeps [demand] told whether this area asks for the wireframe: its overlay state's effective flag (the row
 * under the Show Overlays master), re-sent on a flip, and withdrawn as the area leaves.  Only a 2D viewport
 * asks, as only it draws one; a null state (a standalone shell) asks for nothing, the row's default being
 * off.
 *
 * @param WireframeDemand demand The document's demand register.
 * @param String areaId The area.
 * @param ViewportOverlayState? state The area's overlay state, or null for none.
 */
@Composable
internal fun WireframeDemandPublisher(demand: WireframeDemand, areaId: String, state: ViewportOverlayState?) {
	val wanted = state != null && state.surface == OverlaySurface.Viewport2D && state.effectiveWireframe
	LaunchedEffect(demand, areaId, wanted) {
		demand.set(areaId, wanted)
	}
	DisposableEffect(demand, areaId) {
		onDispose { demand.remove(areaId) }
	}
}