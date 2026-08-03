package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.CameraController
import org.umamo.ui.workspace.AreaCameraHub
import org.umamo.ui.workspace.SpaceKind

/**
 * The viewport navigation commands, dispatching to the surface the pointer last touched: each 2D
 * viewport and UV editor registers a per-area camera controller into the shared hub, and every command
 * resolves the hovered area's controller through it (Blender's hovered-area routing, the same rule as
 * the transforms) - one lookup, no per-space branch.  They apply whenever a viewport is present (a
 * document open on a platform with a renderer); without one they hide from the palette instead of
 * registering as no-ops.
 *
 * @param AreaCameraHub cameras The camera-bearing areas' per-area controller registry.
 * @param CommandRouting routing Resolves which area the pointer means at dispatch time.
 * @param Boolean viewportPresent Whether a viewport / render service exists (gates availability).
 * @return List<Command> The commands to register.
 */
internal fun viewCommands(cameras: AreaCameraHub, routing: CommandRouting, viewportPresent: Boolean): List<Command> {
	val hasViewport = CommandAvailability { viewportPresent }

	/**
	 * The camera controller of the area the pointer last touched, or null when none is registered under
	 * it - then the command is a no-op.
	 *
	 * @return CameraController? The hovered area's camera controller, or null.
	 * @note Deliberately kind-agnostic: the hub's contract is that a future camera-bearing space joins by
	 *   registering a controller, with no per-space branch to update here.  Hovering a space that has no
	 *   camera (a keyform sheet, an outliner) simply resolves nothing.
	 */
	fun hoveredCamera(): CameraController? = routing.hovered()?.areaId?.let { areaId -> cameras.opsFor(areaId) }
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
 * The context-aware frame command - Blender's Home: Frame All in WHICHEVER editor the pointer is over.
 *
 * Its own table rather than part of [viewCommands] because it is a re-dispatcher, not an operation: it
 * resolves the hovered surface to the command that surface means and invokes THAT through the registry,
 * so each editor keeps owning its own framing.  Ungated on purpose - the camera commands it forwards to
 * carry their own viewport gate, while the keyform sheet needs no renderer at all, so gating this one
 * would kill Home over a sheet on a platform with no render service.
 *
 * @param CommandRegistry commandRegistry The registry the resolved command is invoked through.
 * @param CommandRouting routing Resolves which editor the pointer means at dispatch time.
 * @return List<Command> The commands to register.
 */
internal fun frameCommands(commandRegistry: CommandRegistry, routing: CommandRouting): List<Command> =
	listOf(
		Command("frame.all", title = Res.string.cmd_frame_all) {
			val target = if (routing.isHovering(SpaceKind.KeyformSheet)) "keyform.frameAll" else "view.fit"
			commandRegistry.invoke(target)
		},
	)
