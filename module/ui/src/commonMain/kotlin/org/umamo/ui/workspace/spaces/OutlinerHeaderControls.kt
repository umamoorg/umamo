package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.Checkbox
import org.umamo.ui.kit.FilterPopupChip
import org.umamo.ui.kit.FilterSectionLabel
import org.umamo.ui.kit.OverflowRowScope
import org.umamo.ui.kit.SEARCH_FIELD_MIN_WIDTH
import org.umamo.ui.kit.SearchField
import org.umamo.ui.kit.button.ButtonGroup
import org.umamo.ui.kit.button.ButtonGroupItem
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.workspace.AreaScope

/**
 * The outliner's area-header controls (mounted via SpaceDescriptor.headerContent): the name search
 * field centered in the header's flexible middle and the filter dropdown chip right-aligned at its
 * end. Reads and writes the area's shared OutlinerViewState so the body's tree filtering reacts
 * live; renders nothing without an open document (unlike viewport2DHeaderControls, which renders its
 * chips disabled rather than vanishing). Keeping a large rig (hundreds of deformers) navigable is the
 * role Cubism's per-panel search served before the panels were unified here.
 *
 * アウトライナーのエリアヘッダ内容。中央に名前検索フィールド、右端に絞り込みチップ。エリア共有の
 * OutlinerViewState を読み書きし、本体のツリーが即座に反応する。ドキュメント未オープン時は何も
 * 描画しない。
 *
 * @param AreaScope scope The hosting area's scope carrying the shared view state.
 */
internal fun OverflowRowScope.outlinerHeaderControls(scope: AreaScope) {
	val viewState = scope.spaceState(OUTLINER_VIEW_STATE_KEY) { OutlinerViewState() }
	// Flexible gaps on both sides center the search field in the slot region; the filter chip after the
	// second gap right-aligns. The center sits left of true center by half the chip's width - accepted,
	// since exact centering would need the strip to weight against the placed items too.
	flexibleSpace()
	item("search", minWidth = SEARCH_FIELD_MIN_WIDTH) {
		// Each item gates itself on the document: an item that renders nothing measures zero, so the
		// whole strip disappears without the builder needing a CompositionLocal it cannot read.
		if (LocalPuppet.current != null) {
			SearchField(value = viewState.query, onValueChange = { newQuery -> viewState.query = newQuery })
		}
	}
	flexibleSpace()
	item("filter") {
		if (LocalPuppet.current != null) {
			FilterDropdownButton(viewState)
		}
	}
}

/**
 * The outliner's filter dropdown: the shared [FilterPopupChip] holding this panel's kind toggles plus
 * the restriction toggles - a Blender-style row of icon buttons controlling whether the pointer / eye
 * indicator columns render on the rows (lit = shown). Mutates the shared [viewState] directly.
 *
 * @param OutlinerViewState viewState The area's shared search / filter state.
 */
@Composable
private fun FilterDropdownButton(viewState: OutlinerViewState) {
	FilterPopupChip(
		contentDescription = stringResource(Res.string.common_filters),
		icon = if (viewState.isUnfiltered) LocalUmamoIcons.filterUnfiltered else LocalUmamoIcons.filterFiltered,
	) {
		FilterSectionLabel(stringResource(Res.string.outliner_restriction_toggles))
		// Blender's restriction-toggle row: one butted segment per indicator column, lit while
		// the column is shown.
		ButtonGroup(
			items =
				listOf(
					ButtonGroupItem(
						icon = LocalUmamoIcons.selectable,
						selected = viewState.showSelectableColumn,
						onClick = { viewState.showSelectableColumn = !viewState.showSelectableColumn },
						contentDescription = stringResource(Res.string.outliner_column_selectable),
					),
					ButtonGroupItem(
						icon = LocalUmamoIcons.eyeVisible,
						selected = viewState.showVisibilityColumn,
						onClick = { viewState.showVisibilityColumn = !viewState.showVisibilityColumn },
						contentDescription = stringResource(Res.string.outliner_column_visibility),
					),
				),
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
		)
		FilterSectionLabel(stringResource(Res.string.common_filters))
		Checkbox(
			checked = viewState.showParts,
			onCheckedChange = { checked -> viewState.showParts = checked },
			label = stringResource(Res.string.outliner_filter_parts),
		)
		Checkbox(
			checked = viewState.showDrawables,
			onCheckedChange = { checked -> viewState.showDrawables = checked },
			label = stringResource(Res.string.outliner_filter_drawables),
		)
		Checkbox(
			checked = viewState.showDeformers,
			onCheckedChange = { checked -> viewState.showDeformers = checked },
			label = stringResource(Res.string.outliner_filter_deformers),
		)
	}
}