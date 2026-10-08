package org.umamo.ui.workspace.spaces

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState

/*
 * The overlays popover's catalog: the sections and the rows under them, each row naming the surfaces it
 * applies to.  Both work surfaces share the one catalog and hide what they cannot honor - a UV editor has
 * no world axes - so there is one popover to maintain, as there is one header.  A row joins here when the
 * thing it toggles can be drawn or hidden.
 */

/**
 * A section of the overlays popover, in display order.
 *
 * @property StringResource label The section heading.
 */
internal enum class OverlaySection(val label: StringResource) {
	Guides(Res.string.overlay_section_guides),
	Text(Res.string.overlay_section_text),
	Geometry(Res.string.overlay_section_geometry),
}

/**
 * One toggle row of the overlays popover: its section, the surfaces it is offered on, and its label.  Each
 * row reads and writes one flag of the area's [ViewportOverlayState].
 *
 * @property OverlaySection section The section the row sits under.
 * @property Set<OverlaySurface> surfaces The work surfaces that offer the row.
 * @property StringResource label The row's label.
 */
internal enum class OverlayToggle(
	val section: OverlaySection,
	val surfaces: Set<OverlaySurface>,
	val label: StringResource,
) {
	Grid(OverlaySection.Guides, OverlaySurface.entries.toSet(), Res.string.overlay_row_grid),
	Axes(OverlaySection.Guides, setOf(OverlaySurface.Viewport2D), Res.string.overlay_row_axes),
	Cursor(OverlaySection.Guides, OverlaySurface.entries.toSet(), Res.string.overlay_row_cursor),
	Info(OverlaySection.Text, OverlaySurface.entries.toSet(), Res.string.overlay_row_info),
	Wireframe(OverlaySection.Geometry, setOf(OverlaySurface.Viewport2D), Res.string.overlay_row_wireframe),
	;

	/**
	 * Whether this row's flag is on in [state] - the flag itself, not its effect under the master: the row
	 * shows what comes back when the master returns.
	 *
	 * @param ViewportOverlayState state The area's overlay state.
	 * @return Boolean The flag.
	 */
	fun isOn(state: ViewportOverlayState): Boolean =
		when (this) {
			Grid -> state.showGrid
			Axes -> state.showAxes
			Cursor -> state.showCursor
			Info -> state.showInfo
			Wireframe -> state.showWireframe
		}

	/**
	 * Sets this row's flag in [state].
	 *
	 * @param ViewportOverlayState state The area's overlay state.
	 * @param Boolean on The new flag.
	 */
	fun set(state: ViewportOverlayState, on: Boolean) {
		when (this) {
			Grid -> state.showGrid = on
			Axes -> state.showAxes = on
			Cursor -> state.showCursor = on
			Info -> state.showInfo = on
			Wireframe -> state.showWireframe = on
		}
	}
}

/**
 * The rows a surface's popover offers, in catalog order.
 *
 * @param OverlaySurface surface The work surface.
 * @return List<OverlayToggle> The rows offered on it.
 */
internal fun overlayRowsFor(surface: OverlaySurface): List<OverlayToggle> = OverlayToggle.entries.filter { row -> surface in row.surfaces }