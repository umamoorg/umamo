package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.editableGeometryOrNotice
import org.umamo.ui.viewport.gizmo.handleSelectLinkedRequest

/** The notice a request answers with when every mesh in the edit sits behind a hidden ancestor, so nothing could be projected. */
private const val NO_EDITABLE_GEOMETRY_NOTICE = "notice.edit.noEditableGeometry"

/**
 * Collects the keymap commands the Edit overlay executes for its area: Select Linked, Alt+Q switch
 * object, Rip, and the Shift+S snaps.  Each request carries the area its command resolved at dispatch,
 * and only that area's overlay runs it - every open 2D viewport collects the same flows, and an ungated
 * request would run once per viewport.  The area check comes BEFORE the empty-geometry notice, so a
 * request over nothing to edit answers once, from the asking area.  The bodies are the plain handlers in
 * SessionRequestHandlers.kt and gizmo/GizmoSelectionInput.kt; this is only the routing.
 *
 * Runs until its caller's effect is cancelled.  Everything that changes while it runs is read through
 * a State holder at request time, never captured by value.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session whose request flows to collect.
 * @param PuppetViewportService service The render service (the Alt+Q pick).
 * @param State<List<EditMeshGeometry>> geometries The session meshes' live geometry.
 * @param State<ViewportCamera> camera The area camera.
 * @param State<IntSize> size The area size in pixels.
 * @param State<Offset> areaPointer Where the pointer last was in the area, tracked by the host.
 * @param Function onOverlapRequest Opens the overlap-picker popup for an Alt+Q over 2+ stacked candidates.
 */
internal suspend fun collectEditGizmoRequests(
	areaId: String,
	session: EditorSession,
	service: PuppetViewportService,
	geometries: State<List<EditMeshGeometry>>,
	camera: State<ViewportCamera>,
	size: State<IntSize>,
	areaPointer: State<Offset>,
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
) {
	coroutineScope {
		// Select Linked (Blender's L / Ctrl+L).
		launch {
			session.selectLinkedRequests.collect { request ->
				if (request.areaId != areaId) {
					return@collect
				}
				val editable = editableGeometryOrNotice(session, geometries.value, NO_EDITABLE_GEOMETRY_NOTICE) ?: return@collect
				handleSelectLinkedRequest(
					session,
					editable.map { it.gizmo },
					request.fromSelection,
					areaPointer.value,
					camera.value,
					size.value,
				)
			}
		}
		// Alt+Q: switch the edited mesh to the drawable under the pointer (or the overlap picker for a
		// stack).  Needs no geometry and no camera, only the pointer, so it still works in the very state
		// the others have to decline.
		launch {
			session.switchObjectRequests.collect { requestedAreaId ->
				if (requestedAreaId != areaId) {
					return@collect
				}
				handleSwitchEditDrawableRequest(session, service, areaId, areaPointer.value, onOverlapRequest)
			}
		}
		// Rip (Blender's V): duplicate the covered vertices, re-point the pointer-side triangles, auto-grab.
		launch {
			session.ripRequests.collect { requestedAreaId ->
				if (requestedAreaId != areaId) {
					return@collect
				}
				val editable = editableGeometryOrNotice(session, geometries.value, NO_EDITABLE_GEOMETRY_NOTICE) ?: return@collect
				handleRipRequest(session, editable, areaId, areaPointer.value, camera.value, size.value)
			}
		}
		// The geometry-dependent Shift+S snaps for Edit mode.  Only the pointer's own area executes: every
		// open 2D viewport composes this collector, and an ungated request would commit once per viewport.
		// The handler ignores the area - a snap acts on the model - so the payload's id is purely the election.
		launch {
			session.snapRequests.collect { request ->
				if (request.areaId != areaId) {
					return@collect
				}
				val editable = editableGeometryOrNotice(session, geometries.value, NO_EDITABLE_GEOMETRY_NOTICE) ?: return@collect
				handleEditSnapRequest(session, editable, request.kind)
			}
		}
	}
}