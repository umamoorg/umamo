package org.umamo.ui.workspace.spaces.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.DisclosureChevron
import org.umamo.ui.kit.button.IconSlot
import org.umamo.ui.kit.field.formatDecimals
import org.umamo.ui.kit.menu.ContextMenuArea
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.workspace.commands.DeleteArtRequest
import org.umamo.ui.workspace.rowdrag.RowCoordinatesHolder
import org.umamo.ui.workspace.rowdrag.RowDragController
import org.umamo.ui.workspace.rowdrag.RowDragRole
import org.umamo.ui.workspace.rowdrag.dragRowOnLongPress
import org.umamo.ui.workspace.rowdrag.rememberRowDragRole
import org.umamo.ui.workspace.rowdrag.rowDropHighlight
import org.umamo.ui.workspace.spaces.ReportRowHover
import org.umamo.ui.workspace.spaces.RowHoverPreviewState

/** The command Delete Art dispatches, naming the tile to take out of the atlas. */
private const val DELETE_ART_COMMAND = "sources.deleteArt"

/**
 * One row with its context menu attached: indent, chevron, icon, label, detail, status, and the
 * trailing chip its kind carries - a tile row's relink chip, a review row's proposal chip, an unbound
 * layer row's ignore chip, a file row's actions chip (the last two mirrored in the row's context menu).
 *
 * A secondary press falls through the row's clickable to the menu, whose entries are the row's own
 * chip's, so a mouse and a pen reach the same actions.  A file row and an unbound layer row have a menu;
 * every other row passes straight through to the bare [SourcesRowBody].
 *
 * Which of the two a row is follows its status, and a status can change under a live row: a drop turns an
 * unbound layer bound.  So the frame holds what has to outlive that change - the hover source, the row's
 * coordinates, and the effect that clears its drag bounds - and reads none of it: the body reads the hover,
 * so resting on a row runs the body alone.
 *
 * @param SourcesRow    row            The row.
 * @param Boolean       expanded       Whether the row's children are shown.
 * @param Boolean       selected       Whether the row's drawable is in the session selection.
 * @param SourcesLabels labels         The space's localized chrome.
 * @param SourcesViewState viewState   The area's view state, which the row folds through.
 * @param Function      onSelect       Selects the given targets.
 * @param Function      onRelink       Rebinds tiles to one layer (null unbinds), retiring the tiles the accepted proposal named.
 * @param RowDragController dragController The space's drag state.
 * @param Function      onDrop         Applies the drop on release.
 * @param Boolean       hoverPreviewsEnabled Whether the space can preview art at all, so a row reports its hover.
 * @param RowHoverPreviewState hoverPreview The space's hover preview state the row reports into.
 */
@Composable
internal fun SourcesRowView(
	row: SourcesRow,
	expanded: Boolean,
	selected: Boolean,
	labels: SourcesLabels,
	viewState: SourcesViewState,
	onSelect: (List<SelectionTarget>) -> Unit,
	onRelink: (List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit,
	dragController: RowDragController<SourcesDragPayload>,
	onDrop: () -> Unit,
	hoverPreviewsEnabled: Boolean,
	hoverPreview: RowHoverPreviewState<String>,
) {
	val node = row.node
	val onToggle = { viewState.toggleFold(node.id) }
	val interaction = remember { MutableInteractionSource() }
	// Held as the state, never read here: a read would run the frame, and the menu's entries with it, on
	// every hover.
	val hovered = interaction.collectIsHoveredAsState()
	// Plain (non-snapshot) holder for the row's layout coordinates, so onGloballyPositioned never forces a
	// recompose; the hover report and the drag read the live window bounds from it on demand.
	val boundsHolder = remember { RowCoordinatesHolder() }
	// Drop this row's drag-hit-test bounds when it leaves the list, so a drop never targets a row that is gone.
	DisposableEffect(node.id) {
		onDispose { dragController.clearBounds(node.id) }
	}
	val sourceKind = node.kind as? SourcesNodeKind.Source
	val layerKind = node.kind as? SourcesNodeKind.Layer
	val commands = LocalCommands.current
	val body: @Composable () -> Unit = {
		SourcesRowBody(
			row = row,
			expanded = expanded,
			selected = selected,
			hovered = { hovered.value },
			interaction = interaction,
			boundsHolder = boundsHolder,
			labels = labels,
			commands = commands,
			onToggle = onToggle,
			onSelect = onSelect,
			onRelink = onRelink,
			dragController = dragController,
			onDrop = onDrop,
			hoverPreviewsEnabled = hoverPreviewsEnabled,
			hoverPreview = hoverPreview,
		)
	}
	when {
		sourceKind != null -> ContextMenuArea(items = sourceFileMenuItems(sourceKind.sourceId, labels, commands), content = body)
		layerKind != null && node.status.isIgnorable ->
			ContextMenuArea(items = layerMenuItems(layerKind.ref, ignored = node.status == SourcesStatus.Ignored, labels = labels, commands = commands), content = body)
		else -> body()
	}
}

/**
 * The row itself, laid out inside whatever wraps it: the one place the row's hover and its part in a
 * drag are read, so either runs this body and nothing above it.
 *
 * @param SourcesRow    row            The row.
 * @param Boolean       expanded       Whether the row's children are shown.
 * @param Boolean       selected       Whether the row's drawable is in the session selection.
 * @param Function      hovered        Whether the pointer is over the row, read here and not by the frame.
 * @param MutableInteractionSource interaction The row's hover source.
 * @param RowCoordinatesHolder     boundsHolder The row's coordinates holder.
 * @param SourcesLabels labels         The space's localized chrome.
 * @param CommandRegistry commands     The registry the row's chips dispatch through.
 * @param Function      onToggle       Folds the row: opens it when closed and closes it when open, and nothing during a search.
 * @param Function      onSelect       Selects the given targets.
 * @param Function      onRelink       Rebinds tiles to one layer (null unbinds), retiring the tiles the accepted proposal named.
 * @param RowDragController dragController The space's drag state.
 * @param Function      onDrop         Applies the drop on release.
 * @param Boolean       hoverPreviewsEnabled Whether the space can preview art at all, so a row reports its hover.
 * @param RowHoverPreviewState hoverPreview The space's hover preview state the row reports into.
 */
@Composable
private fun SourcesRowBody(
	row: SourcesRow,
	expanded: Boolean,
	selected: Boolean,
	hovered: () -> Boolean,
	interaction: MutableInteractionSource,
	boundsHolder: RowCoordinatesHolder,
	labels: SourcesLabels,
	commands: CommandRegistry,
	onToggle: () -> Unit,
	onSelect: (List<SelectionTarget>) -> Unit,
	onRelink: (List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit,
	dragController: RowDragController<SourcesDragPayload>,
	onDrop: () -> Unit,
	hoverPreviewsEnabled: Boolean,
	hoverPreview: RowHoverPreviewState<String>,
) {
	val node = row.node
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val icons = LocalUmamoIcons
	val isHovered = hovered()
	// Report this row's hover so the space can pop, after its rest delay, an art preview beside it.  A
	// space with nothing to preview with hears of no hover at all.
	ReportRowHover(
		state = hoverPreview,
		key = node.id,
		name = node.label,
		hovered = isHovered,
		enabled = hoverPreviewsEnabled && sourcesPreviewSubject(node) != null,
		boundsHolder = boundsHolder,
	)
	// The long-press drag runs in a long-lived pointerInput coroutine that keeps its first closure, and the
	// drop closes over live state; this keeps the drag's release pointed at the latest callback.
	val currentOnDrop by rememberUpdatedState(onDrop)
	val payload = sourcesDragPayload(node)
	// A valid target (a layer under a dragged tile, a tile under a dragged layer) takes the shared drop ring;
	// anything else under the pointer shows nothing, so the rigger sees where a release would bind.  Read as
	// the row's part in the drag, which changes when this row's part does, so a pointer moving over other
	// rows, or over this one, runs nothing here.
	val dragRole by dragController.rememberRowDragRole(node.id) { dragged, _ -> relinkFor(dragged, node) }
	val isDragged = dragRole == RowDragRole.Dragged
	val isDropTarget = dragRole is RowDragRole.Target
	val background =
		when {
			selected -> colors.selection.copy(alpha = 0.75f)
			isHovered -> colors.rowHover
			else -> Color.Transparent
		}
	val borderColor =
		when {
			selected -> colors.selection
			isHovered -> colors.rowHover
			else -> Color.Transparent
		}
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier =
			Modifier
				.fillMaxWidth()
				.height(SOURCES_ROW_HEIGHT)
				// The dragged row fades whole, fill and ring included, so the alpha layer wraps what follows.
				.alpha(if (isDragged) 0.4f else 1f)
				.onGloballyPositioned { coordinates ->
					boundsHolder.coordinates = coordinates
					dragController.reportBounds(node.id, coordinates.boundsInWindow())
				}
				.hoverable(interaction)
				.focusProperties { canFocus = false }
				.clickable(interactionSource = interaction, indication = null) {
					val targets = sourcesSelectionTargets(node)
					if (targets.isNotEmpty()) {
						onSelect(targets)
					} else if (node.children.isNotEmpty()) {
						// A row with nothing to select folds instead.  One with nothing under it either has
						// nothing to fold, and a fold nobody can see would still be saved with the document.
						onToggle()
					}
				}
				// Long-press to pick a layer or tile row up; a file or drawable row (no payload) never lifts.
				.dragRowOnLongPress(dragController, node.id, payload, boundsHolder) { currentOnDrop() }
				.padding(SOURCES_ROW_BAND_INSET)
				.background(background, shape = shapes.medium)
				.border(BorderStroke(1.dp, borderColor), shapes.medium)
				.rowDropHighlight(isDropTarget, shapes.medium, colors)
				.padding(
					start = SOURCES_ROW_PADDING_START - SOURCES_ROW_BAND_INSET + SOURCES_INDENT_PER_DEPTH * row.depth,
					end = SOURCES_ROW_PADDING_END - SOURCES_ROW_BAND_INSET,
				),
	) {
		Box(modifier = Modifier.width(SOURCES_CHEVRON_WIDTH), contentAlignment = Alignment.Center) {
			if (node.children.isNotEmpty()) {
				DisclosureChevron(
					expanded = expanded,
					tint = colors.textMuted,
					modifier = Modifier.focusProperties { canFocus = false }.clickable(onClick = onToggle),
				)
			}
		}
		// The status is the icon: its glyph and traffic-light tint say bound / bound by name / unbound and
		// present / missing, and the word survives as the glyph's tooltip rather than as row text.
		val visual = sourcesRowVisual(node, icons, colors)
		val statusLabel = visual.statusLabel?.let { label -> stringResource(label) } ?: ""
		Box(modifier = Modifier.width(SOURCES_ICON_WIDTH), contentAlignment = Alignment.Center) {
			IconSlot(icon = visual.icon, contentDescription = statusLabel, tint = visual.tint, glyphSize = SOURCES_GLYPH_SIZE)
		}
		Spacer(modifier = Modifier.width(SOURCES_ICON_LABEL_GAP))
		// The label and detail share ONE weighted slot, so the label's unused share is slack inside it and
		// the trailing chip lands flush right on every row; a second weighted child in the outer row would
		// leave that slack at the row's end instead.
		Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
			Text(text = node.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
			val detail = detailText(node.detail)
			if (detail != null) {
				Spacer(modifier = Modifier.width(SOURCES_LABEL_DETAIL_GAP))
				Text(text = detail, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
		when (val kind = node.kind) {
			is SourcesNodeKind.Tile -> {
				Spacer(modifier = Modifier.width(SOURCES_CHIP_GAP))
				RelinkChip(
					tileId = kind.tileId,
					current = node.binding,
					// Only a tile nothing samples may leave the atlas; a sampled one would strand its drawables.
					canDelete = node.drawableIds.isEmpty(),
					labels = labels,
					onRelink = onRelink,
					onDelete = { commands.invoke(DELETE_ART_COMMAND, DeleteArtRequest(kind.tileId)) },
				)
			}
			is SourcesNodeKind.Layer ->
				if (node.status.isReview) {
					Spacer(modifier = Modifier.width(SOURCES_CHIP_GAP))
					ReviewChip(node = node, ref = kind.ref, labels = labels, onRelink = onRelink)
				} else if (node.status.isIgnorable) {
					Spacer(modifier = Modifier.width(SOURCES_CHIP_GAP))
					SourcesActionsChip(contentDescription = labels.layerActions) {
						layerMenuItems(kind.ref, ignored = node.status == SourcesStatus.Ignored, labels = labels, commands = commands)
					}
				}
			is SourcesNodeKind.Source -> {
				Spacer(modifier = Modifier.width(SOURCES_CHIP_GAP))
				SourcesActionsChip(contentDescription = labels.fileActions) { sourceFileMenuItems(kind.sourceId, labels, commands) }
			}
			is SourcesNodeKind.Drawable, SourcesNodeKind.UnboundGroup -> Unit
		}
	}
}

/**
 * The localized secondary text of a row, or null when it has none.
 *
 * @param SourcesDetail detail The row's detail.
 * @return String? The text.
 */
@Composable
private fun detailText(detail: SourcesDetail): String? =
	when (detail) {
		is SourcesDetail.Source -> {
			val summary = stringResource(Res.string.sources_source_detail, detail.format.uppercase(), detail.layerCount)
			if (detail.hasPath) summary else "$summary · ${stringResource(Res.string.sources_source_no_path)}"
		}
		is SourcesDetail.Layer -> stringResource(Res.string.sources_layer_detail, detail.width, detail.height, detail.left, detail.top)
		// One decimal, like the Properties Position rows: a half-pixel origin (an odd canvas) is real.
		is SourcesDetail.LayerOnAxes ->
			stringResource(Res.string.sources_layer_detail_axes, detail.width, detail.height, formatDecimals(detail.x, 1), formatDecimals(detail.z, 1))
		is SourcesDetail.TilePage -> stringResource(Res.string.sources_tile_page, detail.pageNumber)
		is SourcesDetail.UnlistedBinding -> stringResource(Res.string.sources_tile_unlisted_binding, detail.sourceId.raw, detail.layerKey)
		SourcesDetail.None -> null
	}