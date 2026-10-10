package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import kotlinx.coroutines.CoroutineDispatcher
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.viewport.GridConfig
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry

/**
 * Collects the keymap commands the UV Object overlay executes for its area: the Shift+S snaps, which move
 * placed art tiles in this mode (handleUvObjectSnapRequest).  Each request carries the area its command
 * resolved at dispatch, and only that area's overlay runs it - every open UV editor collects the same flow,
 * and an ungated request would run once per editor.  A request made in Edit mode is the Edit overlay's
 * (collectUvEditGizmoRequests), so the two never both run one.
 *
 * Runs until its caller's effect is cancelled.  Everything that changes while it runs is read through a State
 * holder at request time, never captured by value.  A snap that decodes art suspends the collection until it
 * lands, so the next request reads the document that snap left.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session whose request flow to collect.
 * @param State<UvPlacementSurface?> surface The shown atlas page, or null over a source layer.
 * @param State<List<GizmoMeshGeometry>> geometries The shown islands' display geometry.
 * @param State<UvEditFrame> frame The shown surface's frame.
 * @param ViewportOverlayState? overlays The area's overlay state, whose grid a grid snap rounds to; null (no
 *   area state) rounds to the UV editor's built-in grid.
 * @param CoroutineDispatcher computeDispatcher Where a snap decodes the tiles' art, off the UI thread.
 */
internal suspend fun collectUvObjectGizmoRequests(
	areaId: String,
	session: EditorSession,
	surface: State<UvPlacementSurface?>,
	geometries: State<List<GizmoMeshGeometry>>,
	frame: State<UvEditFrame>,
	overlays: ViewportOverlayState?,
	computeDispatcher: CoroutineDispatcher,
) {
	session.uvSnapRequests.collect { request ->
		if (session.mode.value != EditorMode.Object || request.areaId != areaId) {
			return@collect
		}
		handleUvObjectSnapRequest(
			session = session,
			surface = surface.value,
			geometries = geometries.value,
			frame = frame.value,
			kind = request.kind,
			grid = overlays?.grid ?: GridConfig.applicationDefault(OverlaySurface.UvEditor),
			computeDispatcher = computeDispatcher,
		)
	}
}