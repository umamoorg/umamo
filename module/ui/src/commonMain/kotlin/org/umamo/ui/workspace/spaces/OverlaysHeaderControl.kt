package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.chip.ChipToggle
import org.umamo.ui.kit.chip.FilterSectionLabel
import org.umamo.ui.kit.chip.PopupChip
import org.umamo.ui.kit.field.Checkbox
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.viewport.ViewportOverlayState

/**
 * The overlays control both work-surface headers mount at their trailing end, Blender's two-part Viewport
 * Overlays control as ONE chip: the glyph half is the Show Overlays toggle (lit while the area's overlays
 * show) and the chevron half opens the popover of per-overlay rows under their section headings - the kit
 * [PopupChip] with a [ChipToggle] on its glyph.  Both halves write the area's [ViewportOverlayState]
 * directly - a per-area view choice, not a session operation, so no registry dispatch (the view.overlay.*
 * commands are the separate, hovered-area-routed path onto the same state).
 *
 * The rows stay enabled while the master is off: each row's flag is what comes back when the master
 * returns, so the rigger can set up the set they want before switching it on.  A section with no row for
 * this surface is left out.
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
			}
		}
	}
}