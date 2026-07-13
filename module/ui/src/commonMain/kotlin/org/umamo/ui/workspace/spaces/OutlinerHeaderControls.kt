package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.Checkbox
import org.umamo.ui.kit.DropdownChip
import org.umamo.ui.kit.Surface
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.TextField
import org.umamo.ui.kit.button.ButtonGroup
import org.umamo.ui.kit.button.ButtonGroupItem
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.workspace.AreaScope

/**
 * The outliner's area-header controls (mounted via SpaceDescriptor.headerContent): the name search
 * field centered in the header's flexible middle and the filter dropdown chip right-aligned at its
 * end. Reads and writes the area's shared OutlinerViewState so the body's tree filtering reacts
 * live; renders nothing without an open document, matching Viewport2DHeaderControls. Keeping a
 * large rig (hundreds of deformers) navigable is the role Cubism's per-panel search served before
 * the panels were unified here.
 *
 * アウトライナーのエリアヘッダ内容。中央に名前検索フィールド、右端に絞り込みチップ。エリア共有の
 * OutlinerViewState を読み書きし、本体のツリーが即座に反応する。ドキュメント未オープン時は何も
 * 描画しない。
 *
 * @param AreaScope scope The hosting area's scope carrying the shared view state.
 */
@Composable
internal fun RowScope.OutlinerHeaderControls(scope: AreaScope) {
	if (LocalPuppet.current == null) {
		return
	}
	val viewState = scope.spaceState(OUTLINER_VIEW_STATE_KEY) { OutlinerViewState() }
	// Weight spacers on both sides center the search field in the slot region; the filter chip after
	// the second spacer right-aligns. The center sits left of true center by half the chip's width -
	// accepted, since exact centering would need a custom Layout for one visual nicety.
	Spacer(modifier = Modifier.weight(1f))
	// Fixed width rather than weight: the field must leave room for the other header controls in a
	// narrow area.
	Box(modifier = Modifier.width(160.dp)) {
		TextField(
			value = viewState.query,
			onValueChange = { newQuery -> viewState.query = newQuery },
			modifier = Modifier.fillMaxWidth(),
			placeholder = stringResource(Res.string.search_hint),
		)
		// A clear (X) affordance at the field's trailing edge, shown only when there is text to clear.
		if (viewState.query.isNotEmpty()) {
			IconButton(
				icon = LocalUmamoIcons.close,
				onClick = { viewState.query = "" },
				contentDescription = stringResource(Res.string.search_clear),
				modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
				size = DpSize(18.dp, 18.dp),
				glyphSize = 20.dp,
			)
		}
	}
	Spacer(modifier = Modifier.weight(1f))
	FilterDropdownButton(viewState)
}

/**
 * The filter dropdown: a funnel-and-chevron [DropdownChip] (the same face as the editor-mode chip)
 * opening a popup of kind toggles, plus the restriction toggles - a Blender-style row of icon buttons
 * controlling whether the pointer / eye indicator columns render on the rows (lit = shown). The popup
 * stays open while toggling (only an outside click or Esc dismisses it) so several filters can be
 * flipped at once. Mutates the shared [viewState] directly.
 *
 * @param OutlinerViewState viewState The area's shared search / filter state.
 */
@Composable
private fun FilterDropdownButton(viewState: OutlinerViewState) {
	val colors = LocalUmamoColors.current
	var open by remember { mutableStateOf(false) }
	DropdownChip(
		expanded = open,
		onExpandRequest = { open = true },
		contentDescription = stringResource(Res.string.outliner_filters),
		icon = LocalUmamoIcons.filterFiltered,
	) {
		Popup(
			popupPositionProvider = BelowAnchorPositionProvider,
			onDismissRequest = { open = false },
			properties = PopupProperties(focusable = true),
		) {
			Surface(color = colors.menuBackground, shape = LocalUmamoShapes.current.medium) {
				Column(modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 4.dp)) {
					Text(
						text = stringResource(Res.string.outliner_restriction_toggles),
						style = LocalUmamoTypography.current.labelSmall,
						color = colors.textMuted,
						modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
					)
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
					Text(
						text = stringResource(Res.string.outliner_filters),
						style = LocalUmamoTypography.current.labelSmall,
						color = colors.textMuted,
						modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
					)
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
		}
	}
}
