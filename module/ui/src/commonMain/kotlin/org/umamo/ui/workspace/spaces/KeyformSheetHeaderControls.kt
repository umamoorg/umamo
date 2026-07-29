package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Checkbox
import org.umamo.ui.kit.FilterPopupChip
import org.umamo.ui.kit.FilterSectionLabel
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.workspace.AreaScope

/**
 * The keyform sheet's area-header controls: the track-kind filter.
 *
 * A sibling subtree of the sheet body rather than part of it, so both read the same [AreaScope] view state
 * - which is why that state lives in its own file at internal visibility rather than private to the body.
 *
 * @param AreaScope scope The hosting area's scope.
 */
@Composable
internal fun RowScope.KeyformSheetHeaderControls(scope: AreaScope) {
	val viewState = scope.spaceState(KEYFORM_SHEET_VIEW_STATE_KEY) { KeyformSheetViewState() }
	Spacer(modifier = Modifier.weight(1f))
	FilterPopupChip(
		contentDescription = stringResource(Res.string.keyform_sheet_filters),
		icon = if (viewState.isUnfiltered) LocalUmamoIcons.filterUnfiltered else LocalUmamoIcons.filterFiltered,
	) {
		FilterSectionLabel(stringResource(Res.string.keyform_sheet_filters))
		Checkbox(
			checked = viewState.showGeometry,
			onCheckedChange = { checked -> viewState.showGeometry = checked },
			label = stringResource(Res.string.keyform_sheet_filter_geometry),
		)
		Checkbox(
			checked = viewState.showChannels,
			onCheckedChange = { checked -> viewState.showChannels = checked },
			label = stringResource(Res.string.keyform_sheet_filter_channels),
		)
		Checkbox(
			checked = viewState.showBlendShapes,
			onCheckedChange = { checked -> viewState.showBlendShapes = checked },
			label = stringResource(Res.string.keyform_sheet_filter_blend_shapes),
		)
	}
	Spacer(modifier = Modifier.width(4.dp))
}
