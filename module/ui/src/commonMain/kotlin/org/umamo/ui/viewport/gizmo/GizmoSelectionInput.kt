package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.MeshTopology
import org.umamo.render.ViewportCamera

/**
 * The idle-state select-tool kind of an area-owned select tool, as a stable key for the
 * recomposition-gated cancel backstop each overlay runs (`LaunchedEffect(selectToolKind) { marquee.cancel() }`).
 *
 * Keyed on the tool KIND, not the whole tool value: resizing a circle brush makes a new Circle(radius),
 * which must NOT re-fire and wipe the in-flight stroke mid-paint.  Derived here once for the three
 * overlays that run the backstop, so they cannot come to key on different things.
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
 * The marquee (box + circle) machinery over mesh elements, shared by the 2D viewport's Edit mode and the UV
 * editor's: the stroke / rubber-band state and event rules are MarqueeSelectController's, and these callbacks
 * bind them to the element domain.  An overlay holds one per area, so every callback reads the session and
 * [geometries] when it runs.
 *
 * @param EditorSession session The session owning the mesh selection, the armed tool, and the gesture flag.
 * @param Function geometries The shown meshes' gizmo geometry (the stamp and box domain), read per call.
 * @param Function previewStroke Publishes the live stroke after every stamp and null when it ends (each
 *   surface hands it to the renderer's mesh overlay, which draws it); defaults to nothing.
 * @return MarqueeSelectController<MeshSelection> The marquee.
 */
internal fun meshMarquee(
	session: EditorSession,
	geometries: () -> List<GizmoMeshGeometry>,
	previewStroke: (MeshSelection?) -> Unit = {},
): MarqueeSelectController<MeshSelection> =
	MarqueeSelectController(
		seedStroke = { session.meshSelection.value },
		stampStroke = { working, erasing, center, radiusPx, stampCamera, stampSize ->
			circleSelection(working, erasing, center, radiusPx, geometries(), stampCamera, stampSize)
		},
		commitStroke = { stroke -> session.setMeshSelection(stroke) },
		previewStroke = previewStroke,
		applyBox = { start, end, additive, boxCamera, boxSize ->
			val selection = session.meshSelection.value
			val insideByDrawable =
				geometries().associate { geometry ->
					geometry.drawableId to elementsInBox(selection.selectMode, geometry, start, end, boxCamera, boxSize)
				}
			session.setMeshSelection(MeshSelectionOps.box(selection, insideByDrawable, additive = additive))
		},
		setCircleRadius = { radiusPx -> session.setCircleRadius(radiusPx) },
		clearTool = { session.clearSelectTool() },
		setGestureActive = { active -> session.setViewportGestureActive(active) },
	)

/**
 * The mesh-element surfaces' pointer flow, shared by the 2D viewport's Edit mode and the UV editor's: the
 * box select (un-armed and armed, one flow - see BoxSelectFlow) with the element domain's press and click.
 * An un-armed primary press on an element selects it per the select mode (Shift / Ctrl toggles, plain
 * replaces) instead of starting a box, and an un-armed sub-threshold click on empty canvas clears the
 * selection.
 *
 * The seams are the geometry and the cursor: the 2D viewport passes its world-posed shapes and places its
 * world 2D cursor, the UV editor its display-mapped texture coordinates and its UV cursor.  Object mode is
 * NOT a user - it selects whole drawables with its own click pick ([ObjectPickController]) over the same
 * box flow.
 *
 * @param EditorSession session The session owning the mesh selection, the armed tool, and the gesture flag.
 * @param MarqueeSelectController<MeshSelection> marquee The box machinery the flow rubber-bands through.
 * @param Function geometries The shown meshes' gizmo geometry (the hit-test domain), read per press.
 * @param Function placeCursor Places the space's cursor at a Shift+RightClick, given the unprojected point.
 */
internal class MeshPickController(
	private val session: EditorSession,
	marquee: MarqueeSelectController<MeshSelection>,
	private val geometries: () -> List<GizmoMeshGeometry>,
	placeCursor: (Float, Float) -> Unit,
) {
	// The box gesture, with the element pick on press and the clear on a sub-threshold click.
	private val boxFlow =
		BoxSelectFlow(
			session = session,
			marquee = marquee,
			placeCursor = placeCursor,
			onClick = { _, _ -> clearSelection() },
			pressSelects = { event, change, camera, size -> selectUnderPress(event, change, camera, size) },
		)

	/**
	 * Handles one pointer event while no transform or circle tool owns the area: the box select, armed or
	 * not, and the element pick.
	 *
	 * @param PointerEvent event The full pointer event (buttons and modifiers).
	 * @param PointerInputChange change The event's first change (position and consumption).
	 * @param Boolean armed True while Box select is armed in this area.
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 */
	fun handleEvent(event: PointerEvent, change: PointerInputChange, armed: Boolean, camera: ViewportCamera, size: IntSize) {
		boxFlow.handleEvent(event, change, armed, camera, size)
	}

	/**
	 * Abandons an in-flight box, dropping the rubber-band without touching the selection; a no-op when none
	 * is in flight, so callers invoke it unconditionally.
	 */
	fun cancel() {
		boxFlow.cancel()
	}

	/**
	 * Selects the element under an un-armed press, per the select mode.
	 *
	 * @param PointerEvent event The press (its modifiers pick toggle or replace).
	 * @param PointerInputChange change The press's change (its position).
	 * @param ViewportCamera camera The area camera.
	 * @param IntSize size The area size in pixels.
	 * @return Boolean True when an element was under the press (then no box starts).
	 */
	private fun selectUnderPress(event: PointerEvent, change: PointerInputChange, camera: ViewportCamera, size: IntSize): Boolean {
		val current = session.meshSelection.value
		val hit = hitTestMeshes(current.selectMode, geometries(), change.position, camera, size) ?: return false
		val modifiers = event.keyboardModifiers
		val updated =
			when {
				// Shift and Ctrl both toggle membership (Blender-style): a second modified click on a selected
				// element deselects it.
				modifiers.isShiftPressed || modifiers.isCtrlPressed || modifiers.isMetaPressed ->
					MeshSelectionOps.toggle(current, hit.drawableId, hit.element)
				else -> MeshSelectionOps.replace(current, hit.drawableId, hit.element)
			}
		session.setMeshSelection(updated)
		return true
	}

	/** An un-armed sub-threshold click on empty canvas clears the selection. */
	private fun clearSelection() {
		val current = session.meshSelection.value
		if (!current.isEmpty) {
			session.setMeshSelection(MeshSelectionOps.clear(current))
		}
	}
}

/**
 * Select Linked (Blender's L / Ctrl+L): flood the mesh connectivity from the element under the
 * pointer (or from every selected element) and union the islands into the selection in the current
 * domain, as one undo step.  Takes the geometry-source-agnostic gizmo records, so the Edit overlay
 * (world-posed shapes) and the UV editor (display-mapped texture coordinates) share the flood - UV
 * islands ARE topology islands, since UVs share the vertex index space.
 *
 * @param EditorSession session The session owning the selection.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' gizmo geometry.
 * @param Boolean fromSelection True to flood from every selected element (Ctrl+L); false to flood
 *   from the element under the pointer (L).
 * @param Offset pointer The pointer in area-local pixels.
 * @param ViewportCamera camera The area camera.
 * @param IntSize size The area size in pixels.
 */
internal fun handleSelectLinkedRequest(
	session: EditorSession,
	geometries: List<GizmoMeshGeometry>,
	fromSelection: Boolean,
	pointer: Offset,
	camera: ViewportCamera,
	size: IntSize,
) {
	var selection = session.meshSelection.value
	if (fromSelection) {
		for (geometry in geometries) {
			val covered = MeshTopology.coveredVertexIndices(selection.elementsOf(geometry.drawableId), geometry.indices)
			if (covered.isEmpty()) {
				continue
			}
			val adjacency = MeshTopology.buildVertexAdjacency(geometry.positions.size / 2, geometry.indices)
			val reached = HashSet<Int>()
			for (seedVertex in covered) {
				if (seedVertex !in reached) {
					reached.addAll(MeshTopology.connectedVertices(adjacency, seedVertex))
				}
			}
			selection = MeshSelectionOps.selectLinked(selection, geometry.drawableId, reached, geometry.indices)
		}
	} else {
		val hit = hitTestMeshes(selection.selectMode, geometries, pointer, camera, size) ?: return
		val geometry = geometries.firstOrNull { candidate -> candidate.drawableId == hit.drawableId } ?: return
		val seedVertex = MeshTopology.coveredVertexIndices(setOf(hit.element), geometry.indices).firstOrNull() ?: return
		val adjacency = MeshTopology.buildVertexAdjacency(geometry.positions.size / 2, geometry.indices)
		selection =
			MeshSelectionOps.selectLinked(selection, hit.drawableId, MeshTopology.connectedVertices(adjacency, seedVertex), geometry.indices)
	}
	session.setMeshSelection(selection)
}