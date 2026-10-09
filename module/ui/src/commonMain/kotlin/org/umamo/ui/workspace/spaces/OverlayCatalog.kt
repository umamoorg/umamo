package org.umamo.ui.workspace.spaces

import org.jetbrains.compose.resources.StringResource
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState

/*
 * The overlays popover's catalog: the sections, the toggle rows under them, and the value fields under them,
 * each naming the surfaces it applies to.  Both work surfaces share the one catalog and hide what they cannot
 * honor - a UV editor has no world axes - so there is one popover to maintain, as there is one header.  A row
 * joins here when the thing it toggles can be drawn or hidden, a field when the thing it sets is a number the
 * area draws by, and each brings a description: every section, row, and field of the popover explains itself
 * on hover.
 */

/**
 * A section of the overlays popover, in display order.  Objects holds the chrome drawn over the art's objects;
 * a Deformers row (the deformer overlays) joins it once a deformer overlay exists to toggle.
 *
 * @property StringResource label The section heading.
 * @property StringResource description What the section's rows have in common, the heading's tooltip.
 */
internal enum class OverlaySection(val label: StringResource, val description: StringResource) {
	Guides(Res.string.overlay_section_guides, Res.string.overlay_section_guides_description),
	Text(Res.string.overlay_section_text, Res.string.overlay_section_text_description),
	Objects(Res.string.overlay_section_objects, Res.string.overlay_section_objects_description),
	Geometry(Res.string.overlay_section_geometry, Res.string.overlay_section_geometry_description),
}

/**
 * One toggle row of the overlays popover: its section, the surfaces it is offered on, its label, and its
 * description.  Each row reads and writes one flag of the area's [ViewportOverlayState].
 *
 * @property OverlaySection section The section the row sits under.
 * @property Set<OverlaySurface> surfaces The work surfaces that offer the row.
 * @property StringResource label The row's label.
 * @property StringResource description What the row draws or hides, the row's tooltip.
 */
internal enum class OverlayToggle(
	val section: OverlaySection,
	val surfaces: Set<OverlaySurface>,
	val label: StringResource,
	val description: StringResource,
) {
	Grid(OverlaySection.Guides, OverlaySurface.entries.toSet(), Res.string.overlay_row_grid, Res.string.overlay_row_grid_description),
	Axes(OverlaySection.Guides, setOf(OverlaySurface.Viewport2D), Res.string.overlay_row_axes, Res.string.overlay_row_axes_description),
	Cursor(OverlaySection.Guides, OverlaySurface.entries.toSet(), Res.string.overlay_row_cursor, Res.string.overlay_row_cursor_description),
	Info(OverlaySection.Text, OverlaySurface.entries.toSet(), Res.string.overlay_row_info, Res.string.overlay_row_info_description),
	SelectionTint(OverlaySection.Objects, setOf(OverlaySurface.Viewport2D), Res.string.overlay_row_selection_tint, Res.string.overlay_row_selection_tint_description),
	Wireframe(OverlaySection.Geometry, setOf(OverlaySurface.Viewport2D), Res.string.overlay_row_wireframe, Res.string.overlay_row_wireframe_description),
	CullHidden(OverlaySection.Geometry, setOf(OverlaySurface.Viewport2D), Res.string.overlay_row_cull_hidden, Res.string.overlay_row_cull_hidden_description),
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
			SelectionTint -> state.showSelectionTint
			Wireframe -> state.showWireframe
			CullHidden -> state.cullHiddenWireframe
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
			SelectionTint -> state.showSelectionTint = on
			Wireframe -> state.showWireframe = on
			CullHidden -> state.cullHiddenWireframe = on
		}
	}
}

/**
 * One value field of the overlays popover: its section, the surfaces it is offered on, its label, and its
 * description.  A field follows its section's rows - under the Wireframe row on a 2D viewport, alone under
 * the Geometry heading on a UV editor - and the header control knows how each is edited.  The grid fields
 * are not here: they are the Grid row's own, with their reset.
 *
 * @property OverlaySection section The section the field sits under.
 * @property Set<OverlaySurface> surfaces The work surfaces that offer the field.
 * @property StringResource label The field's label.
 * @property StringResource description What the field sets, the label's tooltip.
 */
internal enum class OverlayField(
	val section: OverlaySection,
	val surfaces: Set<OverlaySurface>,
	val label: StringResource,
	val description: StringResource,
) {
	WireframeOpacity(OverlaySection.Geometry, OverlaySurface.entries.toSet(), Res.string.overlay_wireframe_opacity, Res.string.overlay_wireframe_opacity_description),
}

/**
 * The value fields a surface's popover offers, in catalog order.
 *
 * @param OverlaySurface surface The work surface.
 * @return List<OverlayField> The fields offered on it.
 */
internal fun overlayFieldsFor(surface: OverlaySurface): List<OverlayField> = OverlayField.entries.filter { field -> surface in field.surfaces }

/**
 * The rows a surface's popover offers, in catalog order.
 *
 * @param OverlaySurface surface The work surface.
 * @return List<OverlayToggle> The rows offered on it.
 */
internal fun overlayRowsFor(surface: OverlaySurface): List<OverlayToggle> = OverlayToggle.entries.filter { row -> surface in row.surfaces }