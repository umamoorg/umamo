package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.kit.chip.ChipToggle
import org.umamo.ui.kit.chip.FilterSectionLabel
import org.umamo.ui.kit.chip.PopupChip
import org.umamo.ui.kit.field.NumberField
import org.umamo.ui.kit.field.PropertyCheckboxRow
import org.umamo.ui.kit.field.PropertyFieldRow
import org.umamo.ui.kit.field.formatDecimals
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.viewport.ViewportOverlayState
import org.umamo.ui.viewport.ViewportSettings
import kotlin.math.roundToInt

/**
 * The popover's content width: a narrow Properties section, so its half-and-half rows read exactly like the
 * panel's.  The width is given rather than hugged because a Row of two equal weights has an intrinsic width
 * of twice its most demanding half - "General Information" beside its box would widen the panel to twice
 * its own length.
 */
private val OVERLAYS_POPOVER_WIDTH = 300.dp

/** The inset of the rows from the panel's edges, the section headings' own. */
private val OVERLAYS_ROW_INSET = 8.dp

/** The reset icon's size, and the space held for it while the area follows the application's grid. */
private val GRID_RESET_SIZE = 20.dp

/** The gap between a grid field and the reset icon beside it. */
private val GRID_RESET_GAP = 4.dp

/** The fractional places the Scale field shows and commits: the kit field's default, named so the edit guard compares at the same places. */
private const val GRID_SCALE_DECIMALS = 2

/** The commit clamp of the Opacity field, in percent: 0 draws nothing while the row stays on, 100 is the palette as it is. */
private val WIREFRAME_OPACITY_PERCENT_RANGE = 0..100

/** The Opacity field's chevron and scrub step, in percent. */
private const val WIREFRAME_OPACITY_PERCENT_STEP = 5

/**
 * The overlays control both work-surface headers mount at their trailing end, Blender's two-part Viewport
 * Overlays control as ONE chip: the glyph half is the Show Overlays toggle (lit while the area's overlays
 * show) and the chevron half opens the popover of per-overlay rows under their section headings - the kit
 * [PopupChip] with a [ChipToggle] on its glyph.  Both halves write the area's [ViewportOverlayState]
 * directly - a per-area view choice, not a session operation, so no registry dispatch (the view.overlay.*
 * commands are the separate, hovered-area-routed path onto the same state).
 *
 * The rows are the editor's property rows, the Properties area's two-column grid: a toggle sits in the
 * right half beside its box, a field's label is right-aligned in the left half, and every heading, toggle,
 * and field carries its description as a hover tooltip.  Under the Grid row sit the area's grid fields: an
 * edit gives the area a grid of its own, and the reset beside them, shown only then, returns it to
 * following the application's grid - no checkbox to flip, the edit is the choice.  A section's value fields
 * follow its rows: the Opacity field under the Wireframe row on a 2D viewport, alone under the Geometry
 * heading on a UV editor, where it fades the islands.  The rows and the fields stay enabled while the master
 * is off: each row's flag is what comes back when the master returns, so the rigger can set up the set they
 * want before switching it on.  A section with no row and no field for this surface is left out.
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
		panelWidth = OVERLAYS_POPOVER_WIDTH,
	) {
		val rows = overlayRowsFor(state.surface)
		val fields = overlayFieldsFor(state.surface)
		for (section in OverlaySection.entries) {
			val sectionRows = rows.filter { row -> row.section == section }
			val sectionFields = fields.filter { field -> field.section == section }
			if (sectionRows.isEmpty() && sectionFields.isEmpty()) {
				continue
			}
			Tooltip(text = stringResource(section.description)) {
				FilterSectionLabel(stringResource(section.label))
			}
			Column(
				modifier = Modifier.fillMaxWidth().padding(horizontal = OVERLAYS_ROW_INSET),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				for (row in sectionRows) {
					PropertyCheckboxRow(
						checked = row.isOn(state),
						onCheckedChange = { checked -> row.set(state, checked) },
						label = stringResource(row.label),
						description = stringResource(row.description),
					)
					if (row == OverlayToggle.Grid) {
						GridGeometryFields(state)
					}
				}
				for (field in sectionFields) {
					when (field) {
						OverlayField.WireframeOpacity -> WireframeOpacityField(state, field)
					}
				}
			}
		}
	}
}

/**
 * The Opacity field: the area's wireframe opacity as a whole percent, 0 to 100.  A commit of the percent the
 * field already shows is no edit, so leaving the field hands nothing back; a differing one writes the area's
 * opacity, 0 included, which draws no wireframe while the row stays as set.  The field ends where the grid
 * fields do, leaving the reset icon's slot empty, so the popover's fields share one right edge.
 *
 * @param ViewportOverlayState state The area's overlay state.
 * @param OverlayField field The field's catalog entry, for its label and description.
 */
@Composable
private fun WireframeOpacityField(state: ViewportOverlayState, field: OverlayField) {
	val shownPercent = (state.wireframeOpacity * 100f).roundToInt()
	PropertyFieldRow(label = stringResource(field.label), description = stringResource(field.description), trailingGutter = GRID_RESET_GAP + GRID_RESET_SIZE) {
		NumberField(
			value = shownPercent,
			onValueChange = { percent ->
				if (percent != shownPercent) {
					state.wireframeOpacity = percent / 100f
				}
			},
			range = WIREFRAME_OPACITY_PERCENT_RANGE,
			step = WIREFRAME_OPACITY_PERCENT_STEP,
			unitSuffix = stringResource(Res.string.unit_percent),
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/**
 * The grid geometry fields under the Grid row, Scale and Subdivisions on both surfaces: the scale is world
 * units on a 2D viewport and texels on a UV editor.  The fields show the grid the area draws; an edit gives
 * the area a grid of its own, and the reset beside Scale, shown only then, returns it to following the
 * application's grid.
 *
 * @param ViewportOverlayState state The area's overlay state.
 */
@Composable
private fun GridGeometryFields(state: ViewportOverlayState) {
	val grid = state.grid
	val own = state.gridGeometry != null
	// A field commits on focus loss as well as on Enter, so leaving it - the popover closing on Escape, a
	// click elsewhere - hands back the value it already showed.  Only a value that differs is an edit;
	// the same one must not give the area a grid of its own that merely equals the application's.  The
	// Scale field commits what it shows, rounded to its places, so a scale carrying more places than that
	// (a file's, a setting's) is the same value when its rounding comes back: the guard compares what the
	// field shows, not the floats.
	GridGeometryRow(
		label = stringResource(Res.string.overlay_grid_scale),
		description = stringResource(Res.string.overlay_grid_scale_description),
		resettable = own,
		onReset = { state.gridGeometry = null },
	) { modifier ->
		NumberField(
			value = grid.scale,
			onValueChange = { scale ->
				if (formatDecimals(scale, GRID_SCALE_DECIMALS) != formatDecimals(grid.scale, GRID_SCALE_DECIMALS)) {
					state.gridGeometry = grid.copy(scale = scale)
				}
			},
			range = ViewportSettings.GRID_SCALE_RANGE,
			decimals = GRID_SCALE_DECIMALS,
			modifier = modifier,
		)
	}
	GridGeometryRow(
		label = stringResource(Res.string.overlay_grid_subdivisions),
		description = stringResource(Res.string.overlay_grid_subdivisions_description),
		resettable = false,
		onReset = { state.gridGeometry = null },
	) { modifier ->
		NumberField(
			value = grid.subdivisions,
			onValueChange = { subdivisions ->
				if (subdivisions != grid.subdivisions) {
					state.gridGeometry = grid.copy(subdivisions = subdivisions)
				}
			},
			range = ViewportSettings.GRID_SUBDIVISIONS_RANGE,
			modifier = modifier,
		)
	}
}

/**
 * One grid field row, a property field row whose control half holds the field and, at its end, the reset
 * icon while the row carries it, else a spacer of the icon's size so the field keeps its width either way.
 *
 * @param String label The field's label.
 * @param String description What the field sets, the label's tooltip.
 * @param Boolean resettable Whether the reset icon shows on this row.
 * @param Function onReset What the reset icon does.
 * @param Function field The number field, given the modifier that fills the half up to the icon.
 */
@Composable
private fun GridGeometryRow(
	label: String,
	description: String,
	resettable: Boolean,
	onReset: () -> Unit,
	field: @Composable (Modifier) -> Unit,
) {
	PropertyFieldRow(label = label, description = description) {
		Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			field(Modifier.weight(1f))
			Spacer(modifier = Modifier.width(GRID_RESET_GAP))
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
}