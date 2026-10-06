package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.edit.UvSnapKind
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.editableGeometryOrNotice
import org.umamo.ui.viewport.gizmo.handleSelectLinkedRequest

/**
 * Collects the keymap commands the UV Edit overlay executes for its area: Mirror U / V, Select Linked, and
 * the Shift+S snaps.  Each request carries the area its command resolved at dispatch, and only that area's
 * overlay runs it - every open UV editor collects the same flows, and an ungated request would run once per
 * editor.  The area check comes BEFORE the empty-surface notice, so a request over a surface holding none of
 * the edit's meshes answers once, from the asking area, rather than acting on coordinates nobody can see.
 * Every request acts on the shown meshes alone, the same set a modal transform captures; the two cursor
 * snaps that read no mesh still run over an empty surface.  The bodies are the plain handlers
 * (UvSessionRequestHandlers.kt, gizmo/GizmoSelectionInput.kt, and the session's mirror); this is only the
 * routing.
 *
 * Runs until its caller's effect is cancelled.  Everything that changes while it runs is read through a
 * State holder at request time, never captured by value.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session whose request flows to collect.
 * @param State<List<GizmoMeshGeometry>> geometries The shown meshes' display geometry.
 * @param State<UvEditFrame> frame The shown surface's frame.
 * @param State<ViewportCamera> camera The area camera.
 * @param State<IntSize> size The area size in pixels.
 * @param State<Offset> areaPointer Where the pointer last was in the area, tracked by the host.
 */
internal suspend fun collectUvEditGizmoRequests(
	areaId: String,
	session: EditorSession,
	geometries: State<List<GizmoMeshGeometry>>,
	frame: State<UvEditFrame>,
	camera: State<ViewportCamera>,
	size: State<IntSize>,
	areaPointer: State<Offset>,
) {
	coroutineScope {
		// Mirror U / V.  It routes through the overlay rather than straight to the session because the axis a
		// mirror reflects about is a property of the SHOWN surface - reflecting a source layer's art about the
		// atlas page's axis would be a different operation - and only this overlay knows which surface it is
		// showing, and which of the selected meshes are on it.
		launch {
			session.uvMirrorRequests.collect { request ->
				if (session.mode.value != EditorMode.Edit || request.areaId != areaId) {
					return@collect
				}
				val editable = editableGeometryOrNotice(session, geometries.value, "notice.uv.noEditableGeometry") ?: return@collect
				session.mirrorSelectedUvs(request.mirrorU, frame.value.asUvFrame(), editable.mapTo(HashSet()) { geometry -> geometry.drawableId })
			}
		}
		// Select Linked (Blender's L / Ctrl+L).  UV islands are topology islands (UVs share the vertex index
		// space), so the shared flood runs verbatim over the display geometry.
		launch {
			session.selectLinkedRequests.collect { request ->
				if (session.mode.value != EditorMode.Edit || request.areaId != areaId) {
					return@collect
				}
				val editable = editableGeometryOrNotice(session, geometries.value, "notice.uv.noEditableGeometry") ?: return@collect
				handleSelectLinkedRequest(session, editable, request.fromSelection, areaPointer.value, camera.value, size.value)
			}
		}
		// The UV snap pie (Shift+S over the UV editor).  The executor owns the shown surface's frame and the
		// covered display geometry, so it performs the snap over the texture coordinates here.
		launch {
			session.uvSnapRequests.collect { request ->
				if (session.mode.value != EditorMode.Edit || request.areaId != areaId) {
					return@collect
				}
				val editable =
					if (request.kind.readsShownMeshes()) {
						editableGeometryOrNotice(session, geometries.value, "notice.uv.noEditableGeometry") ?: return@collect
					} else {
						geometries.value
					}
				handleUvSnapRequest(session, editable, frame.value, request.kind)
			}
		}
	}
}

/**
 * Whether a snap reads the shown meshes, so has nothing to act on over a surface holding none of them.  The
 * cursor-to-pixels and cursor-to-grid snaps round the UV cursor over the shown surface alone.
 *
 * @return Boolean True when the snap needs the shown meshes.
 */
private fun UvSnapKind.readsShownMeshes(): Boolean =
	when (this) {
		UvSnapKind.CursorToPixels, UvSnapKind.CursorToGrid -> false
		UvSnapKind.CursorToSelected,
		UvSnapKind.SelectionToPixels,
		UvSnapKind.SelectionToCursor,
		UvSnapKind.SelectionToCursorOffset,
		UvSnapKind.SelectionToGrid,
		-> true
	}

/**
 * Drops a UV operator latched in [areaId], or a select tool armed there, while the area's surface holds none
 * of the edit's meshes, with the notice the requests answer with.  The overlay parts that would begin, drive,
 * and resolve either are not mounted over such a surface, so left in place an operator would turn the area's
 * pan and zoom off until Escape, and begin from a fresh gesture state once the area showed the meshes again;
 * an armed tool would hold navigation off the same way, with no crosshair to show it.  A latch or tool
 * another area holds is left alone, and so is everything outside Edit mode, where this overlay has no say.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session owning the latches.
 */
internal fun dropUvEditLatchesWithNothingToEdit(areaId: String, session: EditorSession) {
	if (session.mode.value != EditorMode.Edit) {
		return
	}
	val operatorHere = session.activeUvOperator.value?.areaId == areaId
	val toolHere = session.activeSelectTool.value?.areaId == areaId
	if (!operatorHere && !toolHere) {
		return
	}
	if (operatorHere) {
		session.clearUvOperator()
	}
	if (toolHere) {
		session.clearSelectTool()
	}
	session.emitNotice("notice.uv.noEditableGeometry", NoticePlacement.NearCursor)
}