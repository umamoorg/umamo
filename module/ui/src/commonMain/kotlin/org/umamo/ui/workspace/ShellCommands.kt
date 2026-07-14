package org.umamo.ui.workspace

import org.jetbrains.compose.resources.StringResource
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.Cursor2d
import org.umamo.edit.DrawableChange
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MergeTarget
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.PartChange
import org.umamo.edit.PieMenuKind
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.SnapKind
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.snapToGrid
import org.umamo.edit.visibilityOf
import org.umamo.edit.withSelectionVisibility
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.document.DocumentOpenFailure
import org.umamo.ui.model.EditorModeHandle
import org.umamo.ui.model.SelectionHandle
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.CameraController

/*
 * The editor shell's command tables: every command the shell itself registers, grouped by the state
 * the handlers close over (which is also each group's re-registration key).  Each builder returns a
 * plain List<Command> the shell registers through registerAll, so registration and cleanup can never
 * drift apart - the old hand-mirrored unregister lists rotted the moment a command was added to one
 * list and not the other.
 *
 * シェルが登録するコマンド表。ハンドラが閉じ込める状態ごとにまとめ、registerAll で登録・解除が
 * 常に一致するようにする。
 */

/**
 * Registers every command in [commands] and returns the matching cleanup, so a DisposableEffect's
 * onDispose can never fall out of sync with what was registered.
 *
 * @param List<Command> commands The commands to register.
 * @return Function The cleanup unregistering exactly those commands.
 */
internal fun CommandRegistry.registerAll(commands: List<Command>): () -> Unit {
	commands.forEach { command -> register(command) }
	return { commands.forEach { command -> unregister(command.id) } }
}

/**
 * The shell-chrome commands: overlay toggles (palette, preferences, Help), the drag cancels, and
 * workspace tab navigation.  All are real registry commands so a key binding (or a future menu)
 * drives them through the one dispatch point - no key handling hardcoded in the shell.
 *
 * @param ShellOverlayState overlays The overlay flags the toggles flip.
 * @param AreaDragController dragController The area corner/splitter drag state area.dragCancel aborts.
 * @param RowDragCancelController rowDragCancel The panel row-drag seam row.dragCancel invokes.
 * @param WorkspaceLayoutController workspaces The layout state workspace.prev/next shift.
 * @return List<Command> The commands to register.
 */
internal fun shellChromeCommands(
	overlays: ShellOverlayState,
	dragController: AreaDragController,
	rowDragCancel: RowDragCancelController,
	workspaces: WorkspaceLayoutController,
): List<Command> =
	listOf(
		Command("palette.toggle", title = null) { overlays.paletteVisible = !overlays.paletteVisible },
		Command("area.dragCancel", title = null) { dragController.cancelDrag() },
		// The panel row-drag cancel (outliner / parameters rows), dispatched from the shell's Escape
		// precedence (mirroring area.dragCancel).
		Command("row.dragCancel", title = null) { rowDragCancel.cancel?.invoke() },
		// The preferences window opens through the registry like everything else, so the Edit menu, the
		// Ctrl/Cmd+, binding, and the command palette all reach it through the one dispatch point. Titled,
		// so it surfaces in the palette ("Settings") for free.
		Command("edit.preferences", title = Res.string.cmd_preferences) { overlays.settingsVisible = !overlays.settingsVisible },
		// The Help dialogs open through the registry too, so the Help menu and the palette (both titled,
		// so they surface there for free) reach them through the one dispatch point.
		Command("help.about", title = Res.string.menu_about) { overlays.aboutVisible = !overlays.aboutVisible },
		Command("help.credits", title = Res.string.menu_credits) { overlays.creditsVisible = !overlays.creditsVisible },
		// Workspace navigation: titled so they surface in the palette and resolve a shortcut hint in the tab
		// context menu. The tab strip's Previous/Next rows dispatch the same ids, so menu and key share a path.
		Command("workspace.prev", title = Res.string.cmd_workspace_prev) { workspaces.switchBy(-1) },
		Command("workspace.next", title = Res.string.cmd_workspace_next) { workspaces.switchBy(1) },
	)

/**
 * The workspace-management and document-report commands.  New mirrors the "+" create path; Reset and
 * Apply-Layout are destructive, so they raise a confirm dialog rather than acting at once;
 * Append-Workspace adds an imported tab (a non-destructive add).  The desktop app owns the
 * Import/Export file handling and invokes applyLayout / appendWorkspace here with the parsed
 * argument - keeping the live layout state the single source of truth.  document.openFailed is the
 * app document layer's failure report; the shell owns the modal alert so it can route Escape and
 * reclaim focus like every other overlay.
 *
 * @param WorkspaceLayoutController workspaces The layout state the commands rewrite.
 * @param ShellOverlayState overlays The overlay state the confirms and the failure alert go through.
 * @param String newWorkspaceBaseName The localized base name new workspaces are named from (deduped).
 * @return List<Command> The commands to register.
 */
internal fun shellWorkspaceCommands(
	workspaces: WorkspaceLayoutController,
	overlays: ShellOverlayState,
	newWorkspaceBaseName: String,
): List<Command> =
	listOf(
		Command("workspace.new", title = Res.string.workspace_new) { workspaces.create(newWorkspaceBaseName) },
		Command("workspace.reset", title = Res.string.cmd_workspace_reset) {
			overlays.pendingConfirm = ConfirmRequest(Res.string.confirm_reset_workspace) { workspaces.resetActive() }
		},
		Command("workspace.applyLayout", title = null) { argument ->
			(argument as? InterfaceLayout)?.let { imported ->
				overlays.pendingConfirm = ConfirmRequest(Res.string.confirm_import_replace) { workspaces.applyImported(imported) }
			}
		},
		Command("workspace.appendWorkspace", title = null) { argument ->
			(argument as? Workspace)?.let { imported -> workspaces.appendImported(imported, newWorkspaceBaseName) }
		},
		Command("document.openFailed", title = null) { argument ->
			(argument as? DocumentOpenFailure)?.let { failure -> overlays.openFailure = failure }
		},
	)

/**
 * The viewport navigation commands, dispatching to the surface the pointer last touched: each 2D
 * viewport and UV editor registers a per-area camera controller into the shared hub, and every command
 * resolves the hovered area's controller through it (Blender's hovered-area routing, the same rule as
 * the transforms) - one lookup, no per-space branch.  They apply whenever a viewport is present (a
 * document open on a platform with a renderer); without one they hide from the palette instead of
 * registering as no-ops.
 *
 * @param AreaCameraHub cameras The camera-bearing areas' per-area controller registry.
 * @param Function hoveredSurface Resolves the last-touched editor surface at dispatch time.
 * @param Boolean viewportPresent Whether a viewport / render service exists (gates availability).
 * @return List<Command> The commands to register.
 */
internal fun shellViewportCommands(
	cameras: AreaCameraHub,
	hoveredSurface: () -> HoveredSurface?,
	viewportPresent: Boolean,
): List<Command> {
	val hasViewport = CommandAvailability { viewportPresent }

	/**
	 * The camera controller of the area the pointer last touched (2D viewport or UV editor), or null
	 * when none is registered under it - then the command is a no-op.
	 *
	 * @return CameraController? The hovered area's camera controller, or null.
	 */
	fun hoveredCamera(): CameraController? = hoveredSurface()?.areaId?.let { areaId -> cameras.opsFor(areaId) }
	return listOf(
		Command("view.fit", title = Res.string.cmd_view_fit, availability = hasViewport) {
			hoveredCamera()?.fit()
		},
		Command("view.zoomActualSize", title = Res.string.cmd_view_actual_size, availability = hasViewport) {
			hoveredCamera()?.actualSize()
		},
		Command("view.zoomIn", title = Res.string.cmd_view_zoom_in, availability = hasViewport) {
			hoveredCamera()?.zoomIn(coarse = false)
		},
		Command("view.zoomOut", title = Res.string.cmd_view_zoom_out, availability = hasViewport) {
			hoveredCamera()?.zoomOut(coarse = false)
		},
		// Coarse (Shift) variants take a larger zoom step - they are titled so they also surface in the palette.
		Command("view.zoomInCoarse", title = Res.string.cmd_view_zoom_in_coarse, availability = hasViewport) {
			hoveredCamera()?.zoomIn(coarse = true)
		},
		Command("view.zoomOutCoarse", title = Res.string.cmd_view_zoom_out_coarse, availability = hasViewport) {
			hoveredCamera()?.zoomOut(coarse = true)
		},
		// Zoom Region (Blender's Shift+B): arms a drag-a-box-to-frame gesture on the hovered surface.
		Command("view.zoomRegion", title = Res.string.cmd_view_zoom_region, availability = hasViewport) {
			hoveredCamera()?.armZoomRegion()
		},
		// Frame Selected (Blender's numpad-period): fit the camera to the selection's bounds - world
		// bounds in the viewport, covered UV bounds in a hovered UV editor.
		Command("view.frameSelected", title = Res.string.cmd_view_frame_selected, availability = hasViewport) {
			hoveredCamera()?.frameSelected()
		},
	)
}

/**
 * The selection-clear and editor-mode commands.  Escape-to-clear is handled in the modal key ladder
 * (so it yields to an in-flight drag's area.dragCancel), not bound here; mode.toggleEdit is bound to
 * Tab in the presets.
 *
 * @param SelectionHandle? selection The selection handle, or null with no document.
 * @param EditorModeHandle? editorMode The mode handle, or null with no document.
 * @return List<Command> The commands to register.
 */
internal fun shellModeCommands(selection: SelectionHandle?, editorMode: EditorModeHandle?): List<Command> {
	// Selection and mode commands need an open document (its selection / mode holders exist).
	val hasSelection = CommandAvailability { selection != null }
	val hasMode = CommandAvailability { editorMode != null }
	return listOf(
		Command("select.clear", title = Res.string.cmd_select_clear, availability = hasSelection) { selection?.set(SelectionOps.clear()) },
		Command("mode.toggleEdit", title = Res.string.cmd_mode_toggle_edit, availability = hasMode) {
			editorMode?.let { it.set(if (it.mode == EditorMode.Object) EditorMode.Edit else EditorMode.Object) }
		},
		// Explicit set-mode commands for the viewport header's mode dropdown (and the palette).  setMode
		// no-ops when already in the requested mode, so re-selecting the current row records no undo step.
		Command("mode.object", title = Res.string.cmd_mode_object, availability = hasMode) { editorMode?.set(EditorMode.Object) },
		Command("mode.edit", title = Res.string.cmd_mode_edit, availability = hasMode) { editorMode?.set(EditorMode.Edit) },
	)
}

/** The localized palette title for each proportional-editing falloff curve. */
private val FALLOFF_TITLES: Map<ProportionalFalloff, StringResource> =
	mapOf(
		ProportionalFalloff.Smooth to Res.string.cmd_mesh_proportional_falloff_smooth,
		ProportionalFalloff.Sphere to Res.string.cmd_mesh_proportional_falloff_sphere,
		ProportionalFalloff.Root to Res.string.cmd_mesh_proportional_falloff_root,
		ProportionalFalloff.Sharp to Res.string.cmd_mesh_proportional_falloff_sharp,
		ProportionalFalloff.Linear to Res.string.cmd_mesh_proportional_falloff_linear,
		ProportionalFalloff.Constant to Res.string.cmd_mesh_proportional_falloff_constant,
	)

/**
 * The document-scoped session commands: history, visibility, the modal G/S/R operators, select modes
 * and tools, pivots and snaps, the topology operators, proportional editing, and the request-flow
 * commands the overlays collect.  All dispatch through the open document's session (null when no
 * document is open, so they no-op - the same guard pattern as the viewport-camera commands) and are
 * re-registered on a document swap so the handlers close over the current session.
 *
 * @param EditorSession? editorSession The open document's session, or null.
 * @param SelectionHandle? selection The object-selection handle, or null with no document.
 * @param Function activeViewportArea Resolves the pointer's viewport area id at dispatch time (null
 *   when no viewport has been touched yet); the latching commands record it as the gesture's
 *   initiating area, following the armZoomRegion precedent.
 * @param Function hoveredSurface Resolves the last-touched editor surface (area id + space kind) at
 *   dispatch time; the transform commands branch to the UV operators when it names a UV editor,
 *   Blender's hovered-area routing (see HoveredSurface.kt).
 * @return List<Command> The commands to register.
 */
internal fun shellSessionCommands(
	editorSession: EditorSession?,
	selection: SelectionHandle?,
	activeViewportArea: () -> String?,
	hoveredSurface: () -> HoveredSurface?,
): List<Command> {
	// The availability tiers of the document-scoped commands: most need only an open document; the
	// Edit-mode element commands hide in Object mode; the circle radius pair needs a live brush.
	// Each lambda reads live session state at query time, so the palette and the keymap dispatch
	// always see the current context.
	val hasDocument = CommandAvailability { editorSession != null }
	val inEditMode = CommandAvailability { editorSession?.mode?.value == EditorMode.Edit }
	val circleToolLive = CommandAvailability { editorSession?.activeSelectTool?.value is ActiveSelectTool.Circle }
	return listOf(
		Command("edit.undo", title = Res.string.cmd_undo, availability = hasDocument) { editorSession?.undo() },
		Command("edit.redo", title = Res.string.cmd_redo, availability = hasDocument) { editorSession?.redo() },
		// The first real model mutation: flips the selected parts'/drawables' eyeball as one undo step.
		Command("object.toggleVisibility", title = Res.string.cmd_toggle_visibility, availability = hasDocument) {
			val current = selection?.selection
			val active = current?.active
			if (editorSession != null && active != null && !current.isEmpty) {
				// Flip relative to the active target's current state, applied to the whole selection, so a
				// mixed selection toggles consistently rather than each entity independently.
				val newVisible = !editorSession.model.value.visibilityOf(active)
				val change =
					when (active) {
						is SelectionTarget.Part -> PartChange.SetVisibility(active.id, newVisible)
						is SelectionTarget.Drawable -> DrawableChange.SetVisibility(active.id, newVisible)
						// A deformer has no visibility flag; nothing to toggle.
						is SelectionTarget.Deformer -> null
					}
				if (change != null) {
					editorSession.mutate(change) { model -> model.withSelectionVisibility(current.targets, newVisible) }
				}
			}
		},
		// Modal G / S / R operators (Blender G / S / R), dispatched by mode so one binding serves both: in Edit
		// mode they transform the selected mesh vertices of one drawable, in Object mode the whole geometry of
		// the selected drawables. Each latches on the session and the matching gizmo overlay drives the gesture;
		// the session methods no-op / block in the wrong mode or with an ineligible selection, so the keymap
		// binds them context-free.
		Command("mesh.grab", title = Res.string.cmd_mesh_grab, availability = hasDocument) { beginTransform(editorSession, MeshOperatorKind.Grab, activeViewportArea(), hoveredSurface()) },
		Command("mesh.scale", title = Res.string.cmd_mesh_scale, availability = hasDocument) { beginTransform(editorSession, MeshOperatorKind.Scale, activeViewportArea(), hoveredSurface()) },
		Command("mesh.rotate", title = Res.string.cmd_mesh_rotate, availability = hasDocument) { beginTransform(editorSession, MeshOperatorKind.Rotate, activeViewportArea(), hoveredSurface()) },
		Command("mesh.modalCancel", title = null) {
			editorSession?.clearMeshOperator()
			editorSession?.clearObjectOperator()
			editorSession?.clearUvOperator()
		},
		// Edit-mode select modes (Blender 1 / 2 / 3). The session guards them to no-op outside Edit mode,
		// so the keymap can bind bare digits context-free; the availability tier additionally hides them
		// from the palette in Object mode (there is no element domain to switch there).
		Command("mesh.selectMode.vertex", title = Res.string.cmd_mesh_select_mode_vertex, availability = inEditMode) { editorSession?.setMeshSelectMode(MeshSelectMode.Vertex) },
		Command("mesh.selectMode.edge", title = Res.string.cmd_mesh_select_mode_edge, availability = inEditMode) { editorSession?.setMeshSelectMode(MeshSelectMode.Edge) },
		Command("mesh.selectMode.face", title = Res.string.cmd_mesh_select_mode_face, availability = inEditMode) { editorSession?.setMeshSelectMode(MeshSelectMode.Face) },
		// Select All / Invert dispatch by mode so one binding (A / Ctrl+I) serves both: mesh elements in Edit
		// mode, whole selectable entities in Object mode. Each session method no-ops in the wrong mode.
		Command("select.all", title = Res.string.cmd_select_all, availability = hasDocument) {
			if (editorSession?.mode?.value == EditorMode.Edit) {
				editorSession.selectAllMeshElements()
			} else {
				editorSession?.selectAllObjects()
			}
		},
		Command("select.invert", title = Res.string.cmd_select_invert, availability = hasDocument) {
			if (editorSession?.mode?.value == EditorMode.Edit) {
				editorSession.invertMeshSelection()
			} else {
				editorSession?.invertObjectSelection()
			}
		},
		// Box (Blender B) and Circle (Blender C) select tools latch on the session; the arming surface's
		// overlay drives the gesture (both modes in the viewport; Edit mode in the UV editor, whose
		// overlay only composes there). The circle grow / shrink commands (numpad +/-) apply only
		// while a Circle brush is live, so they hide from the palette otherwise.
		Command("mesh.boxSelect", title = Res.string.cmd_mesh_box_select, availability = hasDocument) {
			selectToolArmingArea(editorSession, activeViewportArea, hoveredSurface)?.let { areaId -> editorSession?.beginBoxSelect(areaId) }
		},
		Command("mesh.circleSelect", title = Res.string.cmd_mesh_circle_select, availability = hasDocument) {
			selectToolArmingArea(editorSession, activeViewportArea, hoveredSurface)?.let { areaId -> editorSession?.beginCircleSelect(areaId) }
		},
		Command("mesh.circleSelect.grow", title = Res.string.cmd_mesh_circle_grow, availability = circleToolLive) { editorSession?.growCircleRadius() },
		Command("mesh.circleSelect.shrink", title = Res.string.cmd_mesh_circle_shrink, availability = circleToolLive) { editorSession?.shrinkCircleRadius() },
		// Mirror UVs (the duplicated-and-flipped texture regions workflow, e.g. both eyes sampling one
		// eye texture): axis-aligned reflections about the transform pivot, palette-discoverable and
		// unbound by default - an interactive flip is already S + axis + drag through the pivot.
		Command("uv.mirrorU", title = Res.string.cmd_uv_mirror_u, availability = inEditMode) { editorSession?.mirrorSelectedUvs(mirrorU = true) },
		Command("uv.mirrorV", title = Res.string.cmd_uv_mirror_v, availability = inEditMode) { editorSession?.mirrorSelectedUvs(mirrorU = false) },
		// The transform pivot modes (the Period pie / the palette) and the Shift+S snap operations.  The
		// pivot setters and the arithmetical cursor snaps run right here; the geometry-dependent snaps go
		// through the session's request flow to the active mode's overlay (which owns the world projections).
		Command("transform.pivotPie", title = Res.string.cmd_transform_pivot_pie, availability = hasDocument) { editorSession?.openPieMenu(PieMenuKind.PivotMode) },
		Command("transform.pivot.median", title = Res.string.cmd_transform_pivot_median, availability = hasDocument) {
			editorSession?.setPivotMode(TransformPivotMode.MedianPoint)
			editorSession?.closePieMenu()
		},
		Command("transform.pivot.individual", title = Res.string.cmd_transform_pivot_individual, availability = hasDocument) {
			editorSession?.setPivotMode(TransformPivotMode.IndividualOrigins)
			editorSession?.closePieMenu()
		},
		Command("transform.pivot.active", title = Res.string.cmd_transform_pivot_active, availability = hasDocument) {
			editorSession?.setPivotMode(TransformPivotMode.ActiveElement)
			editorSession?.closePieMenu()
		},
		Command("transform.pivot.cursor", title = Res.string.cmd_transform_pivot_cursor, availability = hasDocument) {
			editorSession?.setPivotMode(TransformPivotMode.Cursor)
			editorSession?.closePieMenu()
		},
		Command("snap.pie", title = Res.string.cmd_snap_pie, availability = hasDocument) { editorSession?.openPieMenu(PieMenuKind.Snap) },
		Command("snap.cursorToWorldOrigin", title = Res.string.cmd_snap_cursor_world_origin, availability = hasDocument) {
			editorSession?.let { live ->
				live.setCursor2d(live.model.value.worldOriginX, live.model.value.worldOriginY)
				live.closePieMenu()
			}
		},
		Command("snap.cursorToGrid", title = Res.string.cmd_snap_cursor_grid, availability = hasDocument) {
			editorSession?.let { live ->
				val model = live.model.value
				// An unplaced cursor snaps from the world origin (its conceptual resting place).
				val cursor = live.cursor2d.value ?: Cursor2d(model.worldOriginX, model.worldOriginY)
				val step = live.gridConfig.value.snapStep
				// Round relative to the world origin, so the snap targets the same lines the grid draws
				// (a major line crosses the origin, not an arbitrary mid-cell point).
				live.setCursor2d(
					snapToGrid(cursor.worldX, model.worldOriginX, step),
					snapToGrid(cursor.worldY, model.worldOriginY, step),
				)
				live.closePieMenu()
			}
		},
		Command("snap.cursorToSelected", title = Res.string.cmd_snap_cursor_selected, availability = hasDocument) {
			editorSession?.requestSnap(SnapKind.CursorToSelected)
			editorSession?.closePieMenu()
		},
		Command("snap.cursorToActive", title = Res.string.cmd_snap_cursor_active, availability = hasDocument) {
			editorSession?.requestSnap(SnapKind.CursorToActive)
			editorSession?.closePieMenu()
		},
		Command("snap.selectionToGrid", title = Res.string.cmd_snap_selection_grid, availability = hasDocument) {
			editorSession?.requestSnap(SnapKind.SelectionToGrid)
			editorSession?.closePieMenu()
		},
		Command("snap.selectionToCursor", title = Res.string.cmd_snap_selection_cursor, availability = hasDocument) {
			editorSession?.requestSnap(SnapKind.SelectionToCursor)
			editorSession?.closePieMenu()
		},
		Command("snap.selectionToCursorOffset", title = Res.string.cmd_snap_selection_cursor_offset, availability = hasDocument) {
			editorSession?.requestSnap(SnapKind.SelectionToCursorOffset)
			editorSession?.closePieMenu()
		},
		Command("snap.selectionToActive", title = Res.string.cmd_snap_selection_active, availability = hasDocument) {
			editorSession?.requestSnap(SnapKind.SelectionToActive)
			editorSession?.closePieMenu()
		},
		// The topology operators (Blender's Shift+D / M / V / J).  Duplicate dispatches by mode like
		// G / S / R and auto-grabs the copies; merge opens its target pie; rip needs the pointer side, so
		// it flows to the Edit overlay; connect cuts between the two selected vertices in place.
		Command("mesh.duplicate", title = Res.string.cmd_mesh_duplicate, availability = hasDocument) {
			val live = editorSession
			if (live != null) {
				if (live.mode.value == EditorMode.Edit) {
					live.duplicateSelectedElements()
					// The auto-grab places the fresh copies; proportional weights would drag the
					// original vertices around them, so the latch opts out (Blender parity).  The
					// duplicate itself never depends on a viewport - only the grab needs one, so a
					// hovered UV editor skips it (an auto-grab of rest positions over fresh topology
					// is meaningless there; the copies stay put for an explicit grab instead).
					if (hoveredSurface()?.kind != SpaceKind.UvEditor) {
						activeViewportArea()?.let { areaId ->
							live.beginMeshOperator(MeshOperatorKind.Grab, areaId, suppressProportional = true)
						}
					}
				} else if (live.duplicateSelectedDrawables().isNotEmpty()) {
					activeViewportArea()?.let { areaId -> live.beginObjectOperator(MeshOperatorKind.Grab, areaId) }
				}
			}
		},
		Command("mesh.merge", title = Res.string.cmd_mesh_merge, availability = inEditMode) { editorSession?.openPieMenu(PieMenuKind.MergeTarget) },
		Command("mesh.merge.atCenter", title = Res.string.cmd_mesh_merge_at_center, availability = inEditMode) {
			editorSession?.mergeSelectedVertices(MergeTarget.AtCenter)
			editorSession?.closePieMenu()
		},
		Command("mesh.merge.atFirst", title = Res.string.cmd_mesh_merge_at_first, availability = inEditMode) {
			editorSession?.mergeSelectedVertices(MergeTarget.AtFirst)
			editorSession?.closePieMenu()
		},
		Command("mesh.merge.atLast", title = Res.string.cmd_mesh_merge_at_last, availability = inEditMode) {
			editorSession?.mergeSelectedVertices(MergeTarget.AtLast)
			editorSession?.closePieMenu()
		},
		Command(
			"mesh.rip",
			title = Res.string.cmd_mesh_rip,
			availability =
				CommandAvailability {
					editorSession?.mode?.value == EditorMode.Edit && editorSession.meshSelection.value.selectMode != MeshSelectMode.Face
				},
		) { editorSession?.requestRip() },
		Command("mesh.connect", title = Res.string.cmd_mesh_connect, availability = inEditMode) { editorSession?.connectSelectedVertices() },
		Command(
			"mesh.vertexSlide",
			title = Res.string.cmd_mesh_vertex_slide,
			availability =
				CommandAvailability {
					editorSession?.mode?.value == EditorMode.Edit &&
						editorSession.meshSelection.value.activeElement?.element is MeshElement.Vertex
				},
		) {
			activeViewportArea()?.let { areaId -> editorSession?.beginMeshOperator(MeshOperatorKind.VertexSlide, areaId) }
		},
		// Proportional editing (Blender's O): the toggle flips it, the falloff commands select the curve
		// (enabling it if off); the Edit overlay reads the state when an operator latches and the wheel
		// resizes the radius mid-gesture.
		Command("mesh.proportional.toggle", title = Res.string.cmd_mesh_proportional_toggle, availability = inEditMode) {
			editorSession?.toggleProportionalEdit()
		},
		Command("mesh.proportional.connectedToggle", title = Res.string.cmd_mesh_proportional_connected, availability = inEditMode) {
			editorSession?.toggleProportionalConnected()
		},
		// Select Linked (Blender's L / Ctrl+L) and the Alt+Q edited-mesh switch: the pointer position and
		// the projected geometry live in the overlays, so these fire session request flows they collect.
		// The executing area resolves HERE, at dispatch, into the request payload (the hovered surface -
		// viewport or UV editor alike), so the collectors gate deterministically on their own area id.
		Command("mesh.selectLinkedAtCursor", title = Res.string.cmd_mesh_select_linked_cursor, availability = inEditMode) {
			editorSession?.requestSelectLinked(fromSelection = false, areaId = hoveredSurface()?.areaId)
		},
		Command("mesh.selectLinked", title = Res.string.cmd_mesh_select_linked, availability = inEditMode) {
			editorSession?.requestSelectLinked(fromSelection = true, areaId = hoveredSurface()?.areaId)
		},
		Command("edit.switchObjectUnderCursor", title = Res.string.cmd_switch_object_under_cursor, availability = inEditMode) {
			editorSession?.requestSwitchObjectUnderCursor()
		},
		// Select Hierarchy: with a target argument (the outliner context menu passes its row) the row's
		// subtree replaces the selection; without one (the palette) the current selection expands in place.
		Command("outliner.selectHierarchy", title = Res.string.cmd_outliner_select_hierarchy, availability = hasDocument) { argument ->
			val live = editorSession
			if (live != null) {
				val seed = (argument as? SelectionTarget)?.let { target -> Selection(setOf(target), target) } ?: live.selection.value
				live.setSelection(SelectionOps.selectHierarchy(seed, live.model.value))
			}
		},
	) +
		// One falloff command per curve, looped over the enum so a new falloff cannot be forgotten here.
		ProportionalFalloff.entries.map { falloff ->
			Command(
				"mesh.proportional.falloff.${falloff.name.lowercase()}",
				title = FALLOFF_TITLES.getValue(falloff),
				availability = inEditMode,
			) { editorSession?.setProportionalFalloff(falloff) }
		}
}

/**
 * Resolves which area a Box / Circle select command arms in: the hovered UV editor in Edit mode (its
 * overlay only composes there - an Object-mode arm would latch a tool nothing can drive or cancel by
 * pointer), else the pointer's active viewport.  A hovered UV editor in Object mode resolves to null
 * rather than falling back to a viewport the pointer is not over.
 *
 * @param EditorSession? session The active session, or null when no document is open.
 * @param Function activeViewportArea Resolves the pointer's viewport area id at dispatch time.
 * @param Function hoveredSurface Resolves the last-touched editor surface at dispatch time.
 * @return String? The arming area id, or null when no surface can host the tool.
 */
private fun selectToolArmingArea(
	session: EditorSession?,
	activeViewportArea: () -> String?,
	hoveredSurface: () -> HoveredSurface?,
): String? {
	val hovered = hoveredSurface()
	if (hovered?.kind == SpaceKind.UvEditor) {
		return if (session?.mode?.value == EditorMode.Edit) {
			hovered.areaId
		} else {
			null
		}
	}
	return activeViewportArea()
}

/**
 * Begins a modal transform on [session] for the surface the pointer last touched: a UV operator when
 * that surface is a UV editor (Blender's hovered-area routing - the key acts where the pointer is),
 * else an Edit-mode mesh operator over the selected vertices or an Object-mode operator over the
 * selected drawables' whole geometry.  The shared G / S / R commands dispatch through here so one
 * binding serves every surface and mode (mirroring select.all / select.invert); each session method
 * guards its own preconditions, so an out-of-mode or ineligible call is a safe no-op.
 *
 * @param EditorSession? session The active session, or null when no document is open.
 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
 * @param String? areaId The initiating viewport's area id, or null when the pointer has never
 *   touched a viewport - then there is no viewport to run the gesture in, so this is a no-op.
 * @param HoveredSurface? hovered The last-touched editor surface, or null before any was touched;
 *   a UV editor here routes the transform to the UV operator latch instead of the viewport pair.
 */
private fun beginTransform(session: EditorSession?, kind: MeshOperatorKind, areaId: String?, hovered: HoveredSurface?) {
	if (session == null) {
		return
	}
	if (hovered?.kind == SpaceKind.UvEditor) {
		session.beginUvOperator(kind, hovered.areaId)
		return
	}
	if (areaId == null) {
		return
	}
	if (session.mode.value == EditorMode.Edit) {
		session.beginMeshOperator(kind, areaId)
	} else {
		session.beginObjectOperator(kind, areaId)
	}
}
