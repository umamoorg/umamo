package org.umamo.ui.viewport

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.render.ViewportCamera

/**
 * The idle-state select-tool kind of an area-owned select tool, as a stable key for the
 * recomposition-gated cancel backstop each overlay runs (`LaunchedEffect(selectToolKind) { marquee.cancel() }`).
 *
 * Keyed on the tool KIND, not the whole tool value: resizing a circle brush makes a new Circle(radius),
 * which must NOT re-fire and wipe the in-flight stroke mid-paint.  The three overlays derived this
 * identically before it moved here.
 *
 * @param ActiveSelectTool? ownedSelectTool The select tool this area owns, or null.
 * @return Int A kind ordinal: 0 none, 1 armed box, 2 circle.
 */
internal fun selectToolKind(ownedSelectTool: ActiveSelectTool?): Int =
	when (ownedSelectTool) {
		null -> 0
		is ActiveSelectTool.BoxArmed -> 1
		is ActiveSelectTool.Circle -> 2
	}

/**
 * The idle mesh-element selection pointer branch shared by the Edit overlay and the UV editor: a primary
 * click picks the element under the cursor per the select mode (Shift / Ctrl toggle, plain replaces), an
 * empty primary drag rubber-bands a box, a sub-threshold click on empty canvas clears, and Shift+RightClick
 * places the space's cursor.  Only primary-driven events are consumed, so middle-drag pan and wheel zoom
 * fall through to the navigation layer beneath.
 *
 * The one behavioral seam is [placeCursor]: the 2D viewport places its world-space cursor, the UV editor
 * its texture-space one.  The geometry source differs too, but that is just the caller passing its own
 * projected [geometries].  Object mode is NOT a caller - its idle branch selects whole drawables with
 * click-pick semantics and its own idle-box state, a different interaction.
 *
 * @param PointerEvent event The full pointer event (buttons and modifiers).
 * @param PointerInputChange change The event's first change (position and consumption).
 * @param EditorSession session The session owning the mesh selection and the armed tool latch.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' gizmo geometry (the hit-test domain).
 * @param MarqueeSelectController<MeshSelection> marquee The box / circle machinery for the rubber-band.
 * @param Boolean boxArmed True while Blender's B armed-box tool owns the pointer (always boxes, no pick).
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 * @param Function placeCursor Places the space's cursor at a Shift+RightClick, given the world-space point.
 */
internal fun handleIdleMeshSelectionEvent(
	event: PointerEvent,
	change: PointerInputChange,
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	marquee: MarqueeSelectController<MeshSelection>,
	boxArmed: Boolean,
	camera: ViewportCamera,
	size: IntSize,
	placeCursor: (Float, Float) -> Unit,
) {
	when (event.type) {
		PointerEventType.Press ->
			if (event.buttons.isSecondaryPressed && event.keyboardModifiers.isShiftPressed) {
				// Shift+RightClick places the space's cursor at the pointer (Blender's gesture); the Cursor
				// pivot mode and the snap / mirror commands anchor on it.
				val (worldX, worldY) = screenToWorld(change.position.x, change.position.y, camera, size)
				placeCursor(worldX, worldY)
				change.consume()
			} else if (boxArmed && event.buttons.isSecondaryPressed) {
				session.clearSelectTool()
				change.consume()
			} else if (event.buttons.isPrimaryPressed) {
				val current = session.meshSelection.value
				val hit = hitTestMeshes(current.selectMode, geometries, change.position, camera, size)
				// Armed Box-select ignores the hit and always boxes (Blender's B), so a press on an element
				// still starts a box rather than selecting it.
				if (!boxArmed && hit != null) {
					val modifiers = event.keyboardModifiers
					val updated =
						when {
							// Shift and Ctrl both toggle membership (Blender-style): a second modified click on
							// a selected element deselects it.
							modifiers.isShiftPressed || modifiers.isCtrlPressed || modifiers.isMetaPressed ->
								MeshSelectionOps.toggle(current, hit.drawableId, hit.element)
							else -> MeshSelectionOps.replace(current, hit.drawableId, hit.element)
						}
					session.setMeshSelection(updated)
				} else {
					marquee.beginBox(change.position)
				}
				change.consume()
			}

		PointerEventType.Move ->
			if (marquee.dragBox(change.position)) {
				change.consume()
			}

		PointerEventType.Release -> {
			val boxRelease = marquee.releaseBox(change.position, event.keyboardModifiers.isShiftPressed, camera, size)
			if (boxRelease != BoxRelease.None) {
				if (boxRelease == BoxRelease.Click && !boxArmed && !session.meshSelection.value.isEmpty) {
					// A sub-threshold click clears the selection - but not while armed, where a bare click just
					// disarms the tool (below) rather than wiping the selection.
					session.setMeshSelection(MeshSelectionOps.clear(session.meshSelection.value))
				}
				// Armed Box-select is one-shot: disarm after the drag (or a bare click).
				if (boxArmed) {
					session.clearSelectTool()
				}
				change.consume()
			}
		}

		else -> {}
	}
}
