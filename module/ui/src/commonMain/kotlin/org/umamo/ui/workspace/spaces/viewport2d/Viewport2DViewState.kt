package org.umamo.ui.workspace.spaces.viewport2d

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState
import org.umamo.ui.workspace.PersistentSpaceState
import org.umamo.ui.workspace.spaces.OVERLAYS_MEMBER
import org.umamo.ui.workspace.spaces.overlaysJsonOrNull
import org.umamo.ui.workspace.spaces.restoreOverlays

/** The AreaScope.spaceState key the 2D viewport parks its view state under, and its member in an area block (UMA § 7.3). */
internal const val VIEWPORT_VIEW_STATE_KEY = "viewport"

/**
 * The 2D viewport's per-area view state, shared between its area-header controls and its body (they render
 * as sibling subtrees, so this lives on the hosting AreaScope via spaceState rather than in a body-local
 * remember).  It holds the area's overlay visibility; the camera is the render service's and is saved beside
 * this block as `cameras.viewport`.  Two viewports each get their own instance, the instance lives as long as
 * the open document does, and a saved document carries it (UMA § 7.3).
 */
internal class Viewport2DViewState : PersistentSpaceState {
	/** Which overlays this area shows. */
	val overlays = ViewportOverlayState(OverlaySurface.Viewport2D)

	/**
	 * The 2D viewport's member of its area block.
	 *
	 * @return JsonObject The member, every known key named.
	 */
	override fun toJson(): JsonObject =
		buildJsonObject {
			put(OVERLAYS_MEMBER, overlays.overlaysJsonOrNull())
		}

	/**
	 * Takes the 2D viewport state a document was saved with.
	 *
	 * @param JsonObject tree The member as the file held it.
	 */
	override fun restore(tree: JsonObject) {
		overlays.restoreOverlays(tree[OVERLAYS_MEMBER] as? JsonObject)
	}
}