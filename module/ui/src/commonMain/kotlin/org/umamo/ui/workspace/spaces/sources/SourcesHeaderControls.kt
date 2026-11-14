package org.umamo.ui.workspace.spaces.sources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.CommandIconButton
import org.umamo.ui.action.ObserveForCommands
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.kit.chip.FilterPopupChip
import org.umamo.ui.kit.chip.FilterSectionLabel
import org.umamo.ui.kit.container.OverflowRowScope
import org.umamo.ui.kit.field.Checkbox
import org.umamo.ui.kit.field.SEARCH_FIELD_MIN_WIDTH
import org.umamo.ui.kit.field.SearchField
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.artwork.LocalSourceWatch
import org.umamo.ui.model.documentIsOpen
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.workspace.AreaScope

/**
 * The Sources space's area-header controls: the table's search and filter, and its artwork operations, each its
 * registry command's button, so the palette and the button share one path and one answer to when it applies.
 *
 * Each button observes the model for its changes alone: whether an add or a reload applies follows the document,
 * and the commands that say so cannot be observed.  Match and Reload keep hover text of their own, which explains
 * what the operation does to the table rather than naming it.
 *
 * @param AreaScope scope The hosting area's scope carrying the shared view state.
 */
internal fun OverflowRowScope.sourcesHeaderControls(scope: AreaScope) {
	val viewState = scope.spaceState(SOURCES_VIEW_STATE_KEY) { SourcesViewState() }
	item("add") {
		if (documentIsOpen()) {
			// Grayed with its command, which a puppet that cannot take artwork turns away.
			ObserveForCommands(LocalPuppet.current)
			CommandIconButton("file.importArtwork", appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small))
		}
	}
	flexibleSpace()
	item("search", minWidth = SEARCH_FIELD_MIN_WIDTH) {
		if (documentIsOpen()) {
			SearchField(value = viewState.query, onValueChange = { newQuery -> viewState.query = newQuery })
		}
	}
	flexibleSpace()
	item("match") {
		if (documentIsOpen()) {
			// Every file is read and the bindings the files no longer resolve move to their confident
			// matches as one step; the rest keep their proposals on the rows that need review.
			CommandIconButton(
				"sources.matchAutomatically",
				appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
				description = stringResource(Res.string.sources_match_automatically),
			)
		}
	}
	item("reload") {
		if (documentIsOpen()) {
			// One button: re-probe every file's presence, then reload the present ones as one undo step
			// (the command itself says when nothing changed).  The alert glyph is the watcher's: files
			// changed on disk and await this press (notify mode, or a file edited while the document was closed).
			// The command knows neither, so the button wears the alert over the command's own glyph.
			val pending = LocalSourceWatch.current?.pending?.collectAsState()?.value.orEmpty()
			ObserveForCommands(LocalPuppet.current)
			CommandIconButton(
				"document.reloadArtwork",
				appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
				icon = LocalUmamoIcons.refreshAlert.takeIf { pending.isNotEmpty() },
				description = stringResource(if (pending.isEmpty()) Res.string.sources_reload else Res.string.sources_reload_pending),
			) { dispatch ->
				viewState.refreshSerial++
				dispatch()
			}
		}
	}
	item("filter") {
		if (documentIsOpen()) {
			FilterDropdownButton(viewState)
		}
	}
}

/**
 * The filter chip: one checkbox per kind of row, each toggled on its own like the outliner's, in the
 * shared [FilterPopupChip].
 *
 * @param SourcesViewState viewState The area's shared view state.
 */
@Composable
private fun FilterDropdownButton(viewState: SourcesViewState) {
	FilterPopupChip(
		contentDescription = stringResource(Res.string.common_filters),
		icon = if (viewState.isUnfiltered) LocalUmamoIcons.filterUnfiltered else LocalUmamoIcons.filterFiltered,
	) {
		FilterSectionLabel(stringResource(Res.string.common_filters))

		for (filter in SourcesFilter.entries) {
			Checkbox(
				checked = filter in viewState.filters,
				onCheckedChange = { checked -> viewState.setShown(filter, checked) },
				label =
					stringResource(
						when (filter) {
							SourcesFilter.Bound -> Res.string.sources_filter_bound
							SourcesFilter.Unbound -> Res.string.sources_filter_unbound
							SourcesFilter.Missing -> Res.string.sources_filter_missing
							SourcesFilter.NeedsReview -> Res.string.sources_filter_needs_review
						},
					),
			)
		}
	}
}