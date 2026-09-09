package org.umamo.ui.workspace.spaces

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.Checkbox
import org.umamo.ui.kit.FilterPopupChip
import org.umamo.ui.kit.FilterSectionLabel
import org.umamo.ui.kit.OverflowRowScope
import org.umamo.ui.kit.SEARCH_FIELD_MIN_WIDTH
import org.umamo.ui.kit.SearchField
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.workspace.AreaScope

/**
 * The Sources space's area-header controls: Add Artwork… (the file.addArtwork command, so the palette
 * and the button share one path), the name search centered in the flexible middle, the filter chip,
 * and Refresh, which re-probes whether each file is still on disk.  Reads and writes the area's
 * shared SourcesViewState; renders nothing without an open document.
 *
 * @param AreaScope scope The hosting area's scope carrying the shared view state.
 */
internal fun OverflowRowScope.sourcesHeaderControls(scope: AreaScope) {
	val viewState = scope.spaceState(SOURCES_VIEW_STATE_KEY) { SourcesViewState() }
	item("add") {
		if (LocalPuppet.current != null) {
			val commands = LocalCommands.current
			IconButton(
				icon = LocalUmamoIcons.addFile,
				onClick = { commands.invoke("file.addArtwork") },
				contentDescription = stringResource(Res.string.sources_add_artwork),
				appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
			)
		}
	}
	flexibleSpace()
	item("search", minWidth = SEARCH_FIELD_MIN_WIDTH) {
		if (LocalPuppet.current != null) {
			SearchField(value = viewState.query, onValueChange = { newQuery -> viewState.query = newQuery })
		}
	}
	flexibleSpace()
	item("reload") {
		if (LocalPuppet.current != null) {
			// One button: re-probe every file's presence, then reload the present ones as one undo step
			// (the command itself says when nothing changed).  refreshAlert waits for the watcher that
			// can tell a file changed before anyone asks.
			val commands = LocalCommands.current
			IconButton(
				icon = LocalUmamoIcons.refresh,
				onClick = {
					viewState.refreshSerial++
					commands.invoke("document.reloadArtwork")
				},
				contentDescription = stringResource(Res.string.sources_reload),
				appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
			)
		}
	}
	item("filter") {
		if (LocalPuppet.current != null) {
			FilterDropdownButton(viewState)
		}
	}
}

/**
 * The filter chip: one of the three views, as exclusive checkboxes in the shared [FilterPopupChip].
 *
 * @param SourcesViewState viewState The area's shared view state.
 */
@Composable
private fun FilterDropdownButton(viewState: SourcesViewState) {
	FilterPopupChip(
		contentDescription = stringResource(Res.string.common_filters),
		icon = if (viewState.filter == SourcesFilter.All) LocalUmamoIcons.filterUnfiltered else LocalUmamoIcons.filterFiltered,
	) {
		FilterSectionLabel(stringResource(Res.string.common_filters))
		for (filter in SourcesFilter.entries) {
			Checkbox(
				checked = viewState.filter == filter,
				onCheckedChange = { checked -> viewState.filter = if (checked) filter else SourcesFilter.All },
				label =
					stringResource(
						when (filter) {
							SourcesFilter.All -> Res.string.sources_filter_all
							SourcesFilter.Unbound -> Res.string.sources_filter_unbound
							SourcesFilter.Missing -> Res.string.sources_filter_missing
							SourcesFilter.NeedsReview -> Res.string.sources_filter_needs_review
						},
					),
			)
		}
	}
}