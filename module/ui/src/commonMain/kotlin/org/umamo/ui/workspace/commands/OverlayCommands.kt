package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.action.CommandSpaces
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.ViewportOverlayState
import org.umamo.ui.workspace.AreaOverlayHub

/**
 * The overlay visibility commands, dispatching to the work surface the pointer last touched: each 2D
 * viewport and UV editor registers its area's overlay state into the shared hub, and every command flips
 * the hovered area's flag through it (Blender's hovered-area routing, the same rule as the view commands) -
 * one lookup, no per-space branch, and a no-op when the pointer is over no work surface.  They apply
 * whenever a viewport is present (a document open on a platform with a renderer); without one they hide
 * from the palette instead of registering as no-ops.
 *
 * The popover rows write the same state directly; these are the palette's and the keymap's path onto it.
 *
 * @param AreaOverlayHub areaOverlays The work surfaces' per-area overlay state registry.
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @param Boolean viewportPresent Whether a viewport / render service exists (gates availability).
 * @return List<Command> The commands to register.
 */
internal fun overlayCommands(areaOverlays: AreaOverlayHub, routing: CommandRouting, viewportPresent: Boolean): List<Command> {
	val hasViewport = CommandAvailability { viewportPresent }

	/**
	 * The overlay state of the area the pointer last touched, or null when none is registered under it -
	 * then the command is a no-op.
	 *
	 * @return ViewportOverlayState? The hovered area's overlay state, or null.
	 */
	fun hoveredOverlays(): ViewportOverlayState? = routing.hovered()?.areaId?.let { areaId -> areaOverlays.stateFor(areaId) }
	return listOf(
		// The Show Overlays master (Blender's Shift+Alt+Z): every overlay of the area at once, each row's own
		// flag kept for when it comes back.
		Command("view.overlay.all", title = Res.string.cmd_view_overlay_all, availability = hasViewport, spaces = CommandSpaces.WorkSurfaces) {
			hoveredOverlays()?.let { state -> state.showOverlays = !state.showOverlays }
		},
		Command("view.overlay.grid", title = Res.string.cmd_view_overlay_grid, availability = hasViewport, spaces = CommandSpaces.WorkSurfaces) {
			hoveredOverlays()?.let { state -> state.showGrid = !state.showGrid }
		},
		// The world axes are the 2D viewport's alone - a UV editor's surface has none - so the command is scoped
		// to it, as the catalog offers no row for it there.
		Command("view.overlay.axes", title = Res.string.cmd_view_overlay_axes, availability = hasViewport, spaces = CommandSpaces.Viewport2D) {
			hoveredOverlays()?.let { state -> state.showAxes = !state.showAxes }
		},
		Command("view.overlay.cursor", title = Res.string.cmd_view_overlay_cursor, availability = hasViewport, spaces = CommandSpaces.WorkSurfaces) {
			hoveredOverlays()?.let { state -> state.showCursor = !state.showCursor }
		},
		Command("view.overlay.info", title = Res.string.cmd_view_overlay_info, availability = hasViewport, spaces = CommandSpaces.WorkSurfaces) {
			hoveredOverlays()?.let { state -> state.showInfo = !state.showInfo }
		},
	)
}