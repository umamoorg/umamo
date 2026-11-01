package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.PuppetViewportService

/**
 * One 2D viewport area's lattice hover: the warp whose drawn lattice the free pointer is over, published to the
 * render service so that area alone draws it in the hover color ([PuppetViewportService.setLatticeHover]).  It
 * resolves by the same rule a click does ([selectableLatticeUnder]), so the lattice that lights up is the one a click
 * there takes in Object mode, and the one Alt+Q switches to in Pose mode.  Edit mode draws no lattice layer, so nothing resolves
 * there and the hover stays clear.
 *
 * Both gizmo overlays' pointer loops hold one per area: they hand it every event their idle branch sees, and clear it
 * whenever the area goes inert or a gesture takes the pointer.  The overlay clears it again as it leaves, so a mode
 * switch never strands a highlight.  It publishes only on a change, so a pointer moving along one lattice renders
 * nothing new.
 *
 * @param String areaId The area.
 * @param PuppetViewportService service The render service (the lattice picks and the hover).
 * @param Function model The model the selectable flags live in, read per event.
 */
internal class LatticeHover(
	private val areaId: String,
	private val service: PuppetViewportService,
	private val model: () -> PuppetModel,
) {
	// The warp last published for this area, or null; what a new resolve is compared against.
	private var hovered: DeformerId? = null

	/**
	 * Follows one idle pointer event: leaving the area or holding a button clears the hover, since a press starts
	 * a click, a drag, or a pan rather than a look; any other event resolves the lattice under the pointer anew, a
	 * wheel zoom and a release included, since each can change what is under a pointer that has not moved.
	 *
	 * @param PointerEvent event The pointer event.
	 * @param PointerInputChange change Its first change, the pointer's.
	 */
	fun track(event: PointerEvent, change: PointerInputChange) {
		if (event.type == PointerEventType.Exit || change.pressed) {
			clear()

			return
		}

		publish(selectableLatticeUnder(service, areaId, change.position, model()))
	}

	/** Clears the hover, publishing only when one was shown. */
	fun clear() {
		publish(null)
	}

	/**
	 * Publishes [deformerId] as the area's hover when it differs from the one shown.
	 *
	 * @param DeformerId? deformerId The hovered warp, or null for none.
	 */
	private fun publish(deformerId: DeformerId?) {
		if (deformerId == hovered) {
			return
		}

		hovered = deformerId
		service.setLatticeHover(areaId, deformerId)
	}
}