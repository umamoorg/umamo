package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.kit.chip.ChipToggle
import org.umamo.ui.kit.chip.FilterSectionLabel
import org.umamo.ui.kit.chip.PopupChip
import org.umamo.ui.kit.field.Checkbox
import org.umamo.ui.kit.field.FieldRow
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.viewport.OverlaySurface
import org.umamo.ui.viewport.ViewportOverlayState
import org.umamo.ui.viewport.ViewportSettings

/** The label column of a grid field row: narrower than a settings row, since the popover hugs its rows, but wide enough for "Subdivisions". */
private val GRID_LABEL_WIDTH = 96.dp

/** The grid fields' width, the Preferences rows' shape. */
private val GRID_FIELD_WIDTH = 80.dp

/** The reset icon's size, and the space held for it while the area follows the application's grid. */
private val GRID_RESET_SIZE = 20.dp

/**
 * The overlays control both work-surface headers mount at their trailing end, Blender's two-part Viewport
 * Overlays control as ONE chip: the glyph half is the Show Overlays toggle (lit while the area's overlays
 * show) and the chevron half opens the popover of per-overlay rows under their section headings - the kit
 * [PopupChip] with a [ChipToggle] on its glyph.  Both halves write the area's [ViewportOverlayState]
 * directly - a per-area view choice, not a session operation, so no registry dispatch (the view.overlay.*
 * commands are the separate, hovered-area-routed path onto the same state).
 *
 * Under the Grid row sit the area's grid fields: an edit gives the area a grid of its own, and the reset
 * beside them, shown only then, returns it to following the application's grid - no checkbox to flip, the
 * edit is the choice.  The rows and the fields stay enabled while the master is off: each row's flag is
 * what comes back when the master returns, so the rigger can set up the set they want before switching
 * it on.  A section with no row for this surface is left out.
 *
 * @param ViewportOverlayState state The area's overlay state.
 * @param Boolean enabled Whether the control takes input (false renders it disabled, the 2D header's no-document look).
 */
@Composable
internal fun OverlaysHeaderControl(state: ViewportOverlayState, enabled: Boolean = true) {
	PopupChip(
		contentDescription = stringResource(Res.string.header_viewport_overlays),
		icon = LocalUmamoIcons.overlays,
		iconToggle =
			ChipToggle(
				active = state.showOverlays,
				onToggle = { state.showOverlays = !state.showOverlays },
				contentDescription = stringResource(Res.string.header_show_overlays),
			),
		enabled = enabled,
	) {
		val rows = overlayRowsFor(state.surface)
		for (section in OverlaySection.entries) {
			val sectionRows = rows.filter { row -> row.section == section }
			if (sectionRows.isEmpty()) {
				continue
			}
			FilterSectionLabel(stringResource(section.label))
			for (row in sectionRows) {
				Checkbox(
					checked = row.isOn(state),
					onCheckedChange = { checked -> row.set(state, checked) },
					label = stringResource(row.label),
				)
				if (row == OverlayToggle.Grid) {
					GridGeometryFields(state)
				}
			}
		}
	}
}

/**
 * The grid geometry fields under the Grid row: Scale and Subdivisions on a 2D viewport, Subdivisions alone on
 * a UV editor, whose major spacing is the shown image.  The fields show the grid the area draws; an edit gives
 * the area a grid of its own (on a UV editor its subdivisions, over the application's scale), and the reset
 * beside the first field, shown only then, returns it to following the application's grid.
 *
 * @param ViewportOverlayState state The area's overlay state.
 */
@Composable
private fun GridGeometryFields(state: ViewportOverlayState) {
	val grid = state.grid
	val own = state.gridGeometry != null
	val uvEditor = state.surface == OverlaySurface.UvEditor
	if (!uvEditor) {
		GridGeometryRow(label = stringResource(Res.string.overlay_grid_scale), resettable = own, onReset = { state.gridGeometry = null }) {
			NumberField(
				value = grid.scale,
				onValueChange = { scale -> state.gridGeometry = grid.copy(scale = scale) },
				range = ViewportSettings.GRID_SCALE_RANGE,
				modifier = Modifier.width(GRID_FIELD_WIDTH),
			)
		}
	}
	GridGeometryRow(label = stringResource(Res.string.overlay_grid_subdivisions), resettable = own && uvEditor, onReset = { state.gridGeometry = null }) {
		NumberField(
			value = grid.subdivisions,
			onValueChange = { subdivisions -> state.gridGeometry = grid.copy(subdivisions = subdivisions) },
			range = ViewportSettings.GRID_SUBDIVISIONS_RANGE,
			modifier = Modifier.width(GRID_FIELD_WIDTH),
		)
	}
}

/**
 * One grid field row, indented under the Grid checkbox: the label, the field, and the reset icon while the
 * row carries it, else a spacer of the icon's size so the row keeps its width.
 *
 * @param String label The field's label.
 * @param Boolean resettable Whether the reset icon shows on this row.
 * @param Function onReset What the reset icon does.
 * @param Function field The number field.
 */
@Composable
private fun GridGeometryRow(label: String, resettable: Boolean, onReset: () -> Unit, field: @Composable () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 4.dp).height(24.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		FieldRow(label = label, modifier = Modifier.weight(1f), labelWidth = GRID_LABEL_WIDTH, control = field)
		if (resettable) {
			IconButton(
				icon = LocalUmamoIcons.reset,
				onClick = onReset,
				contentDescription = stringResource(Res.string.overlay_grid_follow_application),
				size = DpSize(GRID_RESET_SIZE, GRID_RESET_SIZE),
				appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
			)
		} else {
			Spacer(modifier = Modifier.size(GRID_RESET_SIZE))
		}
	}
}