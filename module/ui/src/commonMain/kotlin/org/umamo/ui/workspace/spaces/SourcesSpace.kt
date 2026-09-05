package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.setTileSource
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.drawableIdsByAtlasTile
import org.umamo.ui.kit.DisclosureChevron
import org.umamo.ui.kit.PopupChip
import org.umamo.ui.kit.SearchField
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.IconSlot
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.model.LocalSourceFilePresence
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.LocalRowDragCancel

/*
 * The Sources space: the linking table between the document's artwork files and its art.  File ->
 * layer -> tile -> drawables, each with a status; a layer row dragged onto a tile row (or the reverse)
 * rebinds the tile, and a tile row's chip picks a layer or unbinds.  Drawable rows select, so the
 * table is also a way into the rig by the art it came from.
 */

private val SOURCES_ROW_HEIGHT = 22.dp
private val SOURCES_INDENT_PER_DEPTH = 12.dp
private val SOURCES_CHEVRON_WIDTH = 14.dp
private val SOURCES_ICON_WIDTH = 16.dp

/** The relink panel's width; fixed so the list stays put as the search narrows it. */
private val RELINK_PANEL_WIDTH = 320.dp

/** How tall the relink list grows before it scrolls. */
private val RELINK_MAX_LIST_HEIGHT = 320.dp

/** What a dragged row carries: the binding a layer row stands for, or the tile a tile row stands for. */
internal sealed interface SourcesDragPayload {
	data class Layer(val ref: SourceLayerRef) : SourcesDragPayload

	data class Tile(val tileId: AtlasTileId) : SourcesDragPayload
}

/** A plain holder for a row's coordinates, so publishing them never forces a recompose. */
private class SourcesRowBoundsHolder {
	var coordinates: LayoutCoordinates? = null
}

/**
 * The Sources space body.
 *
 * @param AreaScope scope    The hosting area's scope (view state, per-area state).
 * @param Modifier  modifier The layout modifier.
 */
@Composable
fun SourcesSpace(scope: AreaScope, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	val listState = rememberLazyListState()
	val puppet = LocalPuppet.current
	if (puppet == null) {
		Box(modifier = modifier.fillMaxSize().zebraFill(listState, SOURCES_ROW_HEIGHT, colors.rowStripe))
		return
	}
	val session = LocalEditorSession.current
	val selection = LocalSelection.current?.selection ?: Selection()
	val presenceProbe = LocalSourceFilePresence.current
	val viewState = scope.spaceState(SOURCES_VIEW_STATE_KEY) { SourcesViewState() }

	// The presence probe runs once per source per refresh, off the row composition: a file check per
	// recompose would hit the disk every time the pointer moves.
	val presenceBySource =
		remember(puppet.sources, viewState.refreshSerial, presenceProbe) {
			puppet.sources.associate { source ->
				val path = source.path
				val present = if (path == null || presenceProbe == null) null else presenceProbe(path)
				source.id to
					when (present) {
						null -> SourcePresence.Unknown
						true -> SourcePresence.Present
						false -> SourcePresence.Missing
					}
			}
		}
	val unboundGroupLabel = stringResource(Res.string.sources_unbound_art)
	val tree =
		remember(puppet, presenceBySource, unboundGroupLabel) {
			buildSourcesTree(puppet, { source: ArtSource -> presenceBySource[source.id] ?: SourcePresence.Unknown }, unboundGroupLabel)
		}
	val query = viewState.query
	val filtered = remember(tree, query, viewState.filter) { filterSourcesTree(tree, query, viewState.filter) }
	// Expand state by node id, per space instance and NOT keyed on the puppet (the model changes
	// identity on every edit).  Files and the unbound group open by default; layers and tiles close.
	val expanded = remember { mutableStateMapOf<String, Boolean>() }
	val searching = query.isNotBlank()
	val isOpen: (String) -> Boolean = { id ->
		searching || (expanded[id] ?: (id.startsWith("source:") || id == SOURCES_UNBOUND_GROUP_ID))
	}
	val rows = remember(filtered, expanded.toMap(), searching) { flattenSources(filtered, isOpen) }
	val nodeById = remember(rows) { rows.associate { row -> row.node.id to row.node } }

	// Drag-and-drop: long-press a layer or tile row, drop it on the other kind to rebind.  Transient,
	// per space instance; Escape cancels through the shell's shared seam like the outliner.
	val dragController = remember { RowDragController<SourcesDragPayload>() }
	val dragCancelSeam = LocalRowDragCancel.current
	DisposableEffect(dragController.isDragging) {
		if (dragController.isDragging) {
			dragCancelSeam.cancel = { dragController.cancel() }
		}
		onDispose {
			dragCancelSeam.cancel = null
		}
	}
	val performDrop: () -> Unit = {
		val payload = dragController.draggedPayload
		val target = dragController.dropTargetKey?.let { key -> nodeById[key] }
		if (session != null && payload != null && target != null) {
			relinkFor(payload, target.kind)?.let { (tileId, ref) -> session.setTileSource(tileId, ref) }
		}
		dragController.end()
	}

	if (tree.isEmpty()) {
		Box(modifier = modifier.fillMaxSize().zebraFill(listState, SOURCES_ROW_HEIGHT, colors.rowStripe)) {
			Text(text = stringResource(Res.string.sources_empty), color = colors.textMuted, modifier = Modifier.padding(12.dp))
		}
		return
	}
	LazyColumn(
		state = listState,
		modifier = modifier.fillMaxSize().zebraFill(listState, SOURCES_ROW_HEIGHT, colors.rowStripe),
	) {
		items(rows, key = { row -> row.node.id }) { row ->
			SourcesRowView(
				row = row,
				puppet = puppet,
				expanded = isOpen(row.node.id),
				selected = row.node.kind.let { kind -> kind is SourcesNodeKind.Drawable && SelectionTarget.Drawable(kind.drawableId) in selection.targets },
				onToggle = { expanded[row.node.id] = !isOpen(row.node.id) },
				onSelect = { targets ->
					if (session != null && targets.isNotEmpty()) {
						session.setSelection(targets.drop(1).fold(SelectionOps.replace(targets.first())) { acc, target -> SelectionOps.add(acc, target) })
					}
				},
				onRelink = { tileId, ref -> session?.setTileSource(tileId, ref) },
				dragController = dragController,
				onDrop = performDrop,
			)
		}
	}
}

/**
 * The rebind a drop means: a layer onto a tile, or a tile onto a layer; anything else is no drop.
 *
 * @param SourcesDragPayload payload The dragged row.
 * @param SourcesNodeKind    target  The row it was dropped on.
 * @return Pair? The tile to rebind and its new binding, or null.
 */
internal fun relinkFor(payload: SourcesDragPayload, target: SourcesNodeKind): Pair<AtlasTileId, SourceLayerRef>? =
	when {
		payload is SourcesDragPayload.Layer && target is SourcesNodeKind.Tile -> target.tileId to payload.ref
		payload is SourcesDragPayload.Tile && target is SourcesNodeKind.Layer -> payload.tileId to target.ref
		else -> null
	}

/**
 * The drawables a row's click selects: a drawable row itself, a tile row every drawable over it, a
 * layer row every drawable over its bound tiles; files and the unbound group select nothing.
 *
 * @param SourcesNode node   The clicked row.
 * @param PuppetModel puppet The rig.
 * @return List The targets, possibly empty.
 */
private fun selectionTargetsOf(node: SourcesNode, puppet: PuppetModel): List<SelectionTarget> {
	fun drawablesOver(tileId: AtlasTileId): List<DrawableId> = puppet.drawableIdsByAtlasTile()[tileId].orEmpty()
	return when (val kind = node.kind) {
		is SourcesNodeKind.Drawable -> listOf(SelectionTarget.Drawable(kind.drawableId))
		is SourcesNodeKind.Tile -> drawablesOver(kind.tileId).map { drawableId -> SelectionTarget.Drawable(drawableId) }
		is SourcesNodeKind.Layer ->
			node.children.flatMap { child -> (child.kind as? SourcesNodeKind.Tile)?.let { tile -> drawablesOver(tile.tileId) }.orEmpty() }
				.map { drawableId -> SelectionTarget.Drawable(drawableId) }
		is SourcesNodeKind.Source, SourcesNodeKind.UnboundGroup -> emptyList()
	}
}

/**
 * One row: indent, chevron, icon, label, detail, status, and on a tile row the relink chip.
 *
 * @param SourcesRow  row            The row.
 * @param PuppetModel puppet         The rig, for the relink chip's candidates and the click's targets.
 * @param Boolean     expanded       Whether the row's children are shown.
 * @param Boolean     selected       Whether the row's drawable is in the session selection.
 * @param Function    onToggle       Flips the expand state.
 * @param Function    onSelect       Selects the given targets.
 * @param Function    onRelink       Rebinds a tile (null unbinds).
 * @param RowDragController dragController The space's drag state.
 * @param Function    onDrop         Applies the drop on release.
 */
@Composable
private fun SourcesRowView(
	row: SourcesRow,
	puppet: PuppetModel,
	expanded: Boolean,
	selected: Boolean,
	onToggle: () -> Unit,
	onSelect: (List<SelectionTarget>) -> Unit,
	onRelink: (AtlasTileId, SourceLayerRef?) -> Unit,
	dragController: RowDragController<SourcesDragPayload>,
	onDrop: () -> Unit,
) {
	val node = row.node
	val colors = LocalUmamoColors.current
	val icons = LocalUmamoIcons
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val boundsHolder = remember { SourcesRowBoundsHolder() }
	val currentOnDrop by rememberUpdatedState(onDrop)
	val payload: SourcesDragPayload? =
		when (val kind = node.kind) {
			is SourcesNodeKind.Layer -> SourcesDragPayload.Layer(kind.ref)
			is SourcesNodeKind.Tile -> SourcesDragPayload.Tile(kind.tileId)
			else -> null
		}
	val isDragged = dragController.draggingKey == node.id
	val isDropTarget =
		dragController.isDragging &&
			!isDragged &&
			dragController.dropTargetKey == node.id &&
			dragController.draggedPayload?.let { dragged -> relinkFor(dragged, node.kind) } != null
	DisposableEffect(node.id) {
		onDispose { dragController.clearBounds(node.id) }
	}
	val background =
		when {
			isDropTarget -> colors.accent.copy(alpha = 0.35f)
			selected -> colors.accent.copy(alpha = 0.25f)
			hovered -> colors.rowHover
			else -> Color.Transparent
		}
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier =
			Modifier
				.fillMaxWidth()
				.height(SOURCES_ROW_HEIGHT)
				.background(background)
				.alpha(if (isDragged) 0.4f else 1f)
				.onGloballyPositioned { coordinates ->
					boundsHolder.coordinates = coordinates
					dragController.reportBounds(node.id, coordinates.boundsInWindow())
				}
				.hoverable(interaction)
				.focusProperties { canFocus = false }
				.clickable(interactionSource = interaction, indication = null) {
					val targets = selectionTargetsOf(node, puppet)
					if (targets.isEmpty()) {
						onToggle()
					} else {
						onSelect(targets)
					}
				}
				.pointerInput(payload) {
					if (payload == null) {
						return@pointerInput
					}
					detectDragGesturesAfterLongPress(
						onDragStart = { offset ->
							val bounds = boundsHolder.coordinates?.boundsInWindow()
							dragController.start(node.id, payload, (bounds?.left ?: 0f) + offset.x, (bounds?.top ?: 0f) + offset.y)
						},
						onDrag = { change, _ ->
							val bounds = boundsHolder.coordinates?.boundsInWindow()
							dragController.drag((bounds?.left ?: 0f) + change.position.x, (bounds?.top ?: 0f) + change.position.y)
						},
						onDragEnd = { currentOnDrop() },
						onDragCancel = { dragController.end() },
					)
				}
				.padding(start = SOURCES_INDENT_PER_DEPTH * row.depth + 4.dp, end = 6.dp),
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
		val (icon, tint) = rowIconOf(node, icons, colors.text, colors.textMuted)
		Box(modifier = Modifier.width(SOURCES_ICON_WIDTH), contentAlignment = Alignment.Center) {
			IconSlot(icon = icon, contentDescription = "", tint = tint, glyphSize = 14.dp)
		}
		Spacer(modifier = Modifier.width(4.dp))
		Text(text = node.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
		val detail = detailText(node.detail)
		if (detail != null) {
			Spacer(modifier = Modifier.width(8.dp))
			Text(text = detail, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
		Spacer(modifier = Modifier.weight(1f))
		val status = statusText(node.status)
		if (status != null) {
			Text(text = status, color = if (node.status == SourcesStatus.Missing) colors.accent else colors.textMuted, maxLines = 1)
		}
		val tileKind = node.kind as? SourcesNodeKind.Tile
		if (tileKind != null) {
			Spacer(modifier = Modifier.width(6.dp))
			RelinkChip(tileId = tileKind.tileId, puppet = puppet, onRelink = onRelink)
		}
	}
}

/**
 * The icon and tint a row draws with.
 *
 * @param SourcesNode node   The row.
 * @param UmamoIcons  icons  The icon set.
 * @param Color       text   The regular tint.
 * @param Color       muted  The muted tint.
 * @return Pair The icon and its tint.
 */
@Composable
private fun rowIconOf(node: SourcesNode, icons: org.umamo.ui.theme.UmamoIcons, text: Color, muted: Color): Pair<UmamoIcon, Color> =
	when (node.kind) {
		is SourcesNodeKind.Source -> icons.sources to text
		is SourcesNodeKind.Layer -> if (node.status == SourcesStatus.Unbound) icons.unlinked to muted else icons.linked to text
		is SourcesNodeKind.Tile -> icons.spaceTexture to text
		is SourcesNodeKind.Drawable -> icons.mesh to text
		SourcesNodeKind.UnboundGroup -> icons.unlinked to muted
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
		is SourcesDetail.TilePage -> stringResource(Res.string.sources_tile_page, detail.pageNumber)
		SourcesDetail.None -> null
	}

/**
 * The localized status chip text, or null for a row with no status.
 *
 * @param SourcesStatus status The row's status.
 * @return String? The text.
 */
@Composable
private fun statusText(status: SourcesStatus): String? =
	when (status) {
		SourcesStatus.Present -> stringResource(Res.string.sources_status_present)
		SourcesStatus.Missing -> stringResource(Res.string.sources_status_missing)
		SourcesStatus.Unknown -> stringResource(Res.string.sources_status_unknown)
		SourcesStatus.Bound -> stringResource(Res.string.sources_status_bound)
		SourcesStatus.BoundByName -> stringResource(Res.string.sources_status_bound_unstable)
		SourcesStatus.Unbound -> stringResource(Res.string.sources_status_unbound)
		SourcesStatus.Unplaced -> stringResource(Res.string.sources_status_unplaced)
		SourcesStatus.None -> null
	}

/**
 * A tile row's relink chip: a searchable list of every listed file's layers, plus Unbind while the
 * tile is bound.  Picking closes the panel and rebinds as one undo step.
 *
 * @param AtlasTileId tileId   The tile the chip rebinds.
 * @param PuppetModel puppet   The rig, for the candidates and the current binding.
 * @param Function    onRelink Rebinds the tile (null unbinds).
 */
@Composable
private fun RelinkChip(tileId: AtlasTileId, puppet: PuppetModel, onRelink: (AtlasTileId, SourceLayerRef?) -> Unit) {
	val colors = LocalUmamoColors.current
	val icons = LocalUmamoIcons
	var open by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	val current = puppet.atlas.tileById[tileId]?.source
	val candidates =
		remember(puppet.sources) {
			puppet.sources.flatMap { source ->
				source.layers.map { layer -> Triple(source, layer.key, layer.name) }
			}
		}
	val trimmed = query.trim()
	val filtered =
		remember(candidates, trimmed) {
			if (trimmed.isEmpty()) candidates else candidates.filter { (source, _, name) -> name.contains(trimmed, ignoreCase = true) || source.name.contains(trimmed, ignoreCase = true) }
		}
	PopupChip(
		contentDescription = stringResource(Res.string.sources_relink_title),
		icon = if (current != null) icons.linked else icons.unlinked,
		expanded = open,
		onExpandedChange = { next -> open = next },
	) {
		Column(modifier = Modifier.width(RELINK_PANEL_WIDTH)) {
			SearchField(
				value = query,
				onValueChange = { updated -> query = updated },
				modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
				width = RELINK_PANEL_WIDTH - 16.dp,
			)
			// A plain scrolling column, not a lazy list: the popup measures its content intrinsically,
			// which a lazy list cannot answer (see UvLayerPickerChip).
			Column(modifier = Modifier.fillMaxWidth().heightIn(max = RELINK_MAX_LIST_HEIGHT).verticalScroll(rememberScrollState())) {
				if (current != null) {
					RelinkRow(label = stringResource(Res.string.sources_relink_clear), muted = true) {
						open = false
						onRelink(tileId, null)
					}
				}
				if (filtered.isEmpty()) {
					Text(text = stringResource(Res.string.sources_relink_no_matches), color = colors.textMuted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
				}
				for ((source, key, name) in filtered) {
					val bound = current?.sourceId == source.id && current.layerKey == key
					RelinkRow(label = stringResource(Res.string.sources_relink_row, source.name, name), muted = bound) {
						open = false
						if (!bound) {
							// A layer some tile already binds says how strong its key is; otherwise the key's shape does.
							val stable =
								puppet.atlas.tiles
									.mapNotNull { tile -> tile.source }
									.firstOrNull { ref -> ref.sourceId == source.id && ref.layerKey == key }
									?.stableKey
							onRelink(tileId, SourceLayerRef(source.id, key, stableKey = stable ?: layerKeyLooksStable(key)))
						}
					}
				}
			}
		}
	}
}

/**
 * One row of the relink list: the label, hover-highlighted, acting on click.
 *
 * @param String   label   The row text.
 * @param Boolean  muted   Whether the row reads as secondary (the current binding, the unbind action).
 * @param Function onClick Invoked when the row is chosen.
 */
@Composable
private fun RelinkRow(label: String, muted: Boolean, onClick: () -> Unit) {
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Text(
		text = label,
		color = if (muted) colors.textMuted else colors.text,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier =
			Modifier
				.fillMaxWidth()
				.hoverable(interaction)
				.background(if (hovered) colors.rowHover else Color.Transparent)
				.focusProperties { canFocus = false }
				.clickable(onClick = onClick)
				.padding(horizontal = 8.dp, vertical = 6.dp),
	)
}