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
import androidx.compose.runtime.collectAsState
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
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.reimport.LayerMatch
import org.umamo.reimport.suggestionsFor
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.drawableIdsByAtlasTile
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.ContextMenuArea
import org.umamo.ui.kit.DisclosureChevron
import org.umamo.ui.kit.DropdownChipStyle
import org.umamo.ui.kit.FilterSectionLabel
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.PopupChip
import org.umamo.ui.kit.SearchField
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.IconSlot
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.model.LocalSourceFilePresence
import org.umamo.ui.model.LocalSourceSuggestions
import org.umamo.ui.model.LocalSourceWatch
import org.umamo.ui.model.percentOf
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoColors
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.UmamoIcons
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.LocalRowDragCancel
import org.umamo.ui.workspace.commands.RelinkRequest
import org.umamo.ui.workspace.commands.ReloadScope
import org.umamo.ui.workspace.commands.ReplaceRequest

/*
 * The Sources space: the linking table between the document's artwork files and its art.  File ->
 * layer -> tile -> drawables, each with a status; a layer row dragged onto a tile row (or the reverse)
 * rebinds the tile, a tile row's chip picks a layer or unbinds, a row that needs review carries the
 * matcher's proposal to accept or a relink by hand, and a file row's chip (or its context menu)
 * replaces or reloads that one file.  Drawable rows select, so the table is also a way into the rig
 * by the art it came from.
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
	// The watcher's presence serial: a file deleted or returned re-probes without a click.
	val watchSerial = LocalSourceWatch.current?.serial?.collectAsState()?.value ?: 0

	// The presence probe runs once per source per refresh, off the row composition: a file check per
	// recompose would hit the disk every time the pointer moves.
	val presenceBySource =
		remember(puppet.sources, viewState.refreshSerial, watchSerial, presenceProbe) {
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
	// Two tiers of proposal for a binding the file no longer resolves: the one the last operation that
	// read the file scored with pixels, else the one the model's own inventory ranks (names, folders,
	// bounds, hashes) - so the chips show something even before any file is read.
	val published = LocalSourceSuggestions.current?.suggestions?.collectAsState()?.value.orEmpty()
	// Keyed on what the ranking reads - the files and the tile bindings - not the whole model: a
	// vertex drag mints a model per frame, and the name-distance pass over every lost layer and every
	// candidate must not run on each of them.
	val modelSuggestions =
		remember(puppet.sources, puppet.atlas.tiles) {
			puppet.sources.associate { source -> source.id to suggestionsFor(puppet, source.id) }
		}
	val suggestionCandidates: (ArtSourceId, String) -> List<LayerMatch> =
		{ sourceId, key -> listOfNotNull(published[sourceId to key], modelSuggestions[sourceId]?.get(key)) }
	val tree =
		remember(puppet, presenceBySource, unboundGroupLabel, published) {
			buildSourcesTree(puppet, { source: ArtSource -> presenceBySource[source.id] ?: SourcePresence.Unknown }, unboundGroupLabel, suggestionCandidates)
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
	// A relink is a command, not a session edit from here: the app reads the layer's file and pulls its
	// art in (a binding-only change when it cannot), and the shell resolves where the strip shows.
	val commands = LocalCommands.current
	val relink: (List<AtlasTileId>, SourceLayerRef?) -> Unit = { tileIds, ref -> commands.invoke("sources.relink", RelinkRequest(tileIds, ref)) }
	val performDrop: () -> Unit = {
		val payload = dragController.draggedPayload
		val target = dragController.dropTargetKey?.let { key -> nodeById[key] }
		if (session != null && payload != null && target != null) {
			relinkFor(payload, target)?.let { (tileId, ref) -> relink(listOf(tileId), ref) }
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
				onRelink = relink,
				dragController = dragController,
				onDrop = performDrop,
			)
		}
	}
}

/**
 * The rebind a drop means: a layer onto a tile, or a tile onto a layer; anything else is no drop.  A
 * layer row the file lost is no target either - it stands for a review, not for art a tile could take,
 * and a binding to it would read as needing review the moment it landed.
 *
 * @param SourcesDragPayload payload The dragged row.
 * @param SourcesNode        target  The row it was dropped on.
 * @return Pair? The tile to rebind and its new binding, or null.
 */
internal fun relinkFor(payload: SourcesDragPayload, target: SourcesNode): Pair<AtlasTileId, SourceLayerRef>? {
	val kind = target.kind
	return when {
		payload is SourcesDragPayload.Layer && kind is SourcesNodeKind.Tile -> kind.tileId to payload.ref
		payload is SourcesDragPayload.Tile && kind is SourcesNodeKind.Layer && target.status != SourcesStatus.NeedsReview -> payload.tileId to kind.ref
		else -> null
	}
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
 * One row: indent, chevron, icon, label, detail, status, and the trailing chip its kind carries - a
 * tile row's relink chip, a review row's proposal chip, a file row's actions chip (mirrored in the
 * file row's context menu).
 *
 * @param SourcesRow  row            The row.
 * @param PuppetModel puppet         The rig, for the relink chip's candidates and the click's targets.
 * @param Boolean     expanded       Whether the row's children are shown.
 * @param Boolean     selected       Whether the row's drawable is in the session selection.
 * @param Function    onToggle       Flips the expand state.
 * @param Function    onSelect       Selects the given targets.
 * @param Function    onRelink       Rebinds tiles to one layer (null unbinds).
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
	onRelink: (List<AtlasTileId>, SourceLayerRef?) -> Unit,
	dragController: RowDragController<SourcesDragPayload>,
	onDrop: () -> Unit,
) {
	val node = row.node
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val boundsHolder = remember { SourcesRowBoundsHolder() }
	val currentOnDrop by rememberUpdatedState(onDrop)
	// A layer the file lost drags nowhere: its binding is what is under review, not a layer to offer.
	val payload: SourcesDragPayload? =
		when (val kind = node.kind) {
			is SourcesNodeKind.Layer -> if (node.status == SourcesStatus.NeedsReview) null else SourcesDragPayload.Layer(kind.ref)
			is SourcesNodeKind.Tile -> SourcesDragPayload.Tile(kind.tileId)
			else -> null
		}
	val isDragged = dragController.draggingKey == node.id
	val isDropTarget =
		dragController.isDragging &&
			!isDragged &&
			dragController.dropTargetKey == node.id &&
			dragController.draggedPayload?.let { dragged -> relinkFor(dragged, node) } != null
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
	val sourceKind = node.kind as? SourcesNodeKind.Source
	val commands = LocalCommands.current
	val body: @Composable () -> Unit = {
		SourcesRowBody(
			row = row,
			puppet = puppet,
			expanded = expanded,
			background = background,
			isDragged = isDragged,
			interaction = interaction,
			boundsHolder = boundsHolder,
			payload = payload,
			onToggle = onToggle,
			onSelect = onSelect,
			onRelink = onRelink,
			dragController = dragController,
			onDropNow = { currentOnDrop() },
		)
	}
	if (sourceKind == null) {
		body()
	} else {
		// A secondary press falls through the row's clickable to the menu; the two items are the file
		// chip's, so a mouse and a pen reach the same actions.
		ContextMenuArea(items = sourceFileMenuItems(sourceKind.sourceId, commands), content = body)
	}
}

/**
 * The file row's two actions, as the chip and the context menu both list them: Replace Artwork…
 * (`sources.replaceArtwork`) and Reload This File (`document.reloadArtwork` scoped to the file).
 *
 * @param ArtSourceId                     sourceId The file.
 * @param org.umamo.ui.action.CommandRegistry commands The registry to dispatch through.
 * @return List<MenuItem> The items.
 */
@Composable
private fun sourceFileMenuItems(sourceId: ArtSourceId, commands: org.umamo.ui.action.CommandRegistry): List<MenuItem> =
	listOf(
		MenuItem.Action(stringResource(Res.string.sources_file_menu_replace), onSelect = { commands.invoke("sources.replaceArtwork", ReplaceRequest(sourceId)) }),
		MenuItem.Action(stringResource(Res.string.sources_file_menu_reload), onSelect = { commands.invoke("document.reloadArtwork", ReloadScope(setOf(sourceId))) }),
	)

/**
 * The row itself, laid out inside whatever wraps it.
 *
 * @param SourcesRow  row            The row.
 * @param PuppetModel puppet         The rig.
 * @param Boolean     expanded       Whether the row's children are shown.
 * @param Color       background     The row's fill for its hover, selection, or drop state.
 * @param Boolean     isDragged      Whether the row is the one being dragged.
 * @param MutableInteractionSource interaction The row's hover source.
 * @param SourcesRowBoundsHolder   boundsHolder The row's coordinates holder.
 * @param SourcesDragPayload?      payload      What a drag from the row carries, or null when it cannot be dragged.
 * @param Function    onToggle       Flips the expand state.
 * @param Function    onSelect       Selects the given targets.
 * @param Function    onRelink       Rebinds tiles to one layer (null unbinds).
 * @param RowDragController dragController The space's drag state.
 * @param Function    onDropNow      Applies the drop on release.
 */
@Composable
private fun SourcesRowBody(
	row: SourcesRow,
	puppet: PuppetModel,
	expanded: Boolean,
	background: Color,
	isDragged: Boolean,
	interaction: MutableInteractionSource,
	boundsHolder: SourcesRowBoundsHolder,
	payload: SourcesDragPayload?,
	onToggle: () -> Unit,
	onSelect: (List<SelectionTarget>) -> Unit,
	onRelink: (List<AtlasTileId>, SourceLayerRef?) -> Unit,
	dragController: RowDragController<SourcesDragPayload>,
	onDropNow: () -> Unit,
) {
	val node = row.node
	val colors = LocalUmamoColors.current
	val icons = LocalUmamoIcons
	val commands = LocalCommands.current
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
						onDragEnd = { onDropNow() },
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
		// The status is the icon: its glyph and traffic-light tint say bound / bound by name / unbound and
		// present / missing, and the word survives as the glyph's tooltip rather than as row text.
		val visual = sourcesRowVisual(node, icons, colors)
		val statusLabel = visual.statusLabel?.let { label -> stringResource(label) } ?: ""
		Box(modifier = Modifier.width(SOURCES_ICON_WIDTH), contentAlignment = Alignment.Center) {
			IconSlot(icon = visual.icon, contentDescription = statusLabel, tint = visual.tint, glyphSize = 14.dp)
		}
		Spacer(modifier = Modifier.width(4.dp))
		// The label and detail share ONE weighted slot, so the label's unused share is slack inside it and
		// the trailing chip lands flush right on every row; a second weighted child in the outer row would
		// leave that slack at the row's end instead.
		Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
			Text(text = node.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
			val detail = detailText(node.detail)
			if (detail != null) {
				Spacer(modifier = Modifier.width(8.dp))
				Text(text = detail, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
		when (val kind = node.kind) {
			is SourcesNodeKind.Tile -> {
				Spacer(modifier = Modifier.width(6.dp))
				RelinkChip(tileId = kind.tileId, puppet = puppet, onRelink = onRelink)
			}
			is SourcesNodeKind.Layer ->
				if (node.status == SourcesStatus.NeedsReview) {
					Spacer(modifier = Modifier.width(6.dp))
					ReviewChip(node = node, ref = kind.ref, puppet = puppet, onRelink = onRelink)
				}
			is SourcesNodeKind.Source -> {
				Spacer(modifier = Modifier.width(6.dp))
				SourceFileChip(sourceId = kind.sourceId, commands = commands)
			}
			is SourcesNodeKind.Drawable, SourcesNodeKind.UnboundGroup -> Unit
		}
	}
}

/**
 * A file row's actions chip: Replace Artwork… and Reload This File, the same two the row's context
 * menu offers.
 *
 * @param ArtSourceId                         sourceId The file.
 * @param org.umamo.ui.action.CommandRegistry commands The registry to dispatch through.
 */
@Composable
private fun SourceFileChip(sourceId: ArtSourceId, commands: org.umamo.ui.action.CommandRegistry) {
	val icons = LocalUmamoIcons
	var open by remember { mutableStateOf(false) }
	val items = sourceFileMenuItems(sourceId, commands)
	PopupChip(
		contentDescription = stringResource(Res.string.sources_file_menu),
		icon = icons.dots,
		expanded = open,
		onExpandedChange = { next -> open = next },
		style = DropdownChipStyle.Compact,
	) {
		Column(modifier = Modifier.width(RELINK_PANEL_WIDTH / 2)) {
			for (item in items) {
				if (item is MenuItem.Action) {
					RelinkRow(label = item.label, muted = false) {
						open = false
						item.onSelect()
					}
				}
			}
		}
	}
}

/**
 * A review row's chip: the matcher's proposal to accept (the candidate's name and confidence), a relink
 * by hand through the same list a tile row's chip shows, or leave the binding as it is.  Accepting is
 * one relink of every tile bound to the lost key, so the art is pulled exactly as a manual relink pulls
 * it and the tiles move together as one step.
 *
 * @param SourcesNode    node     The review row, carrying its suggestion when there is one.
 * @param SourceLayerRef ref      The lost binding the row stands for.
 * @param PuppetModel    puppet   The rig, for the relink list.
 * @param Function       onRelink Rebinds the tiles as one step (null unbinds).
 */
@Composable
private fun ReviewChip(node: SourcesNode, ref: SourceLayerRef, puppet: PuppetModel, onRelink: (List<AtlasTileId>, SourceLayerRef?) -> Unit) {
	val colors = LocalUmamoColors.current
	val icons = LocalUmamoIcons
	var open by remember { mutableStateOf(false) }
	var byHand by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	// The tiles under review are the row's own children: the tree lists them by (file, key), which is
	// the binding as the tile carries it, whereas the row's ref re-derives the key's strength and can
	// disagree with a reader-minted one (a flat raster's) - an equality on the ref would find nothing.
	val boundTiles = remember(node) { node.children.mapNotNull { child -> (child.kind as? SourcesNodeKind.Tile)?.tileId } }
	val suggestion = node.suggestion
	val relinkAll: (SourceLayerRef?) -> Unit = { target ->
		if (boundTiles.isNotEmpty()) {
			onRelink(boundTiles, target)
		}
	}
	PopupChip(
		contentDescription = stringResource(Res.string.sources_suggestion_title),
		icon = icons.linked,
		iconTint = colors.signalCaution,
		expanded = open,
		onExpandedChange = { next ->
			open = next
			if (!next) {
				byHand = false
			}
		},
		style = DropdownChipStyle.Compact,
	) {
		if (byHand) {
			RelinkList(puppet = puppet, current = ref, query = query, onQueryChange = { updated -> query = updated }, showUnbind = false) { target ->
				open = false
				byHand = false
				relinkAll(target)
			}
		} else {
			Column(modifier = Modifier.width(RELINK_PANEL_WIDTH)) {
				if (suggestion != null) {
					RelinkRow(label = stringResource(Res.string.sources_suggestion_accept, suggestion.candidateName, percentOf(suggestion.score)), muted = false) {
						open = false
						relinkAll(SourceLayerRef(ref.sourceId, suggestion.candidateKey, stableKey = layerKeyLooksStable(suggestion.candidateKey)))
					}
				}
				RelinkRow(label = stringResource(Res.string.sources_suggestion_relink), muted = false) { byHand = true }
				RelinkRow(label = stringResource(Res.string.sources_suggestion_leave), muted = true) { open = false }
			}
		}
	}
}

/**
 * How a row's leading icon reads: the glyph, its tint, and the status word the glyph's tooltip carries.
 *
 * @property UmamoIcon       icon        The glyph.
 * @property Color           tint        The glyph's color.
 * @property StringResource? statusLabel The row's status as a tooltip, or null for a row with none.
 */
internal class SourcesRowVisual(
	val icon: UmamoIcon,
	val tint: Color,
	val statusLabel: StringResource?,
)

/**
 * The icon a row draws with, carrying the row's status the way a traffic light does: green for a
 * layer bound by a stable key, amber for one bound by name (a binding that holds only while the
 * layer keeps its name and place), a tile on no page, or a binding whose layer the file lost, red for
 * an unbound layer, a missing file, or the unbound-art group.  The glyph itself already says what the
 * row is - a file, a link, a tile, a mesh - and a missing file swaps to the missing-file glyph, so the
 * status word is a tooltip, never row text.  Pure, so the mapping is testable without a composition.
 *
 * @param SourcesNode node   The row.
 * @param UmamoIcons  icons  The icon set.
 * @param UmamoColors colors The palette.
 * @return SourcesRowVisual The glyph, tint, and tooltip.
 */
internal fun sourcesRowVisual(node: SourcesNode, icons: UmamoIcons, colors: UmamoColors): SourcesRowVisual =
	when (node.kind) {
		is SourcesNodeKind.Source ->
			when (node.status) {
				SourcesStatus.Missing -> SourcesRowVisual(icons.missingFile, colors.signalBad, Res.string.sources_status_missing)
				SourcesStatus.Unknown -> SourcesRowVisual(icons.sources, colors.text, Res.string.sources_status_unknown)
				else -> SourcesRowVisual(icons.sources, colors.text, Res.string.sources_status_present)
			}
		is SourcesNodeKind.Layer ->
			when (node.status) {
				SourcesStatus.Unbound -> SourcesRowVisual(icons.unlinked, colors.signalBad, Res.string.sources_status_unbound)
				SourcesStatus.BoundByName -> SourcesRowVisual(icons.linked, colors.signalCaution, Res.string.sources_status_bound_unstable)
				// The tile is bound, but to a layer its file no longer lists: linked to nothing, waiting on a decision.
				SourcesStatus.NeedsReview -> SourcesRowVisual(icons.unlinked, colors.signalCaution, Res.string.sources_status_needs_review)
				else -> SourcesRowVisual(icons.linked, colors.signalGood, Res.string.sources_status_bound)
			}
		is SourcesNodeKind.Tile ->
			if (node.status == SourcesStatus.Unplaced) {
				SourcesRowVisual(icons.spaceTexture, colors.signalCaution, Res.string.sources_status_unplaced)
			} else {
				SourcesRowVisual(icons.spaceTexture, colors.text, null)
			}
		is SourcesNodeKind.Drawable -> SourcesRowVisual(icons.mesh, colors.outlinerObjectTint, null)
		// Every tile under the group is unbound; the one red marker at the heading is the group's status.
		SourcesNodeKind.UnboundGroup -> SourcesRowVisual(icons.unlinked, colors.signalBad, Res.string.sources_status_unbound)
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
 * One artwork file's rows in the relink list: the file as a heading, the layers beneath it.
 *
 * @property ArtSource source The file.
 * @property List      layers The layers to list under it, in inventory order.
 */
internal class RelinkGroup(
	val source: ArtSource,
	val layers: List<ArtSourceLayer>,
)

/**
 * The relink list for [query]: every file with every layer when the query is blank; otherwise a layer
 * survives when its name matches, a whole file survives when the FILE name matches, and a file with
 * nothing left under it is dropped.  Grouped by file so the file name is read once as a heading and
 * a long one can never push the layer names out of a row.
 *
 * @param List<ArtSource> sources The document's artwork files.
 * @param String          query   The search text, matched case-insensitively after trimming.
 * @return List<RelinkGroup> The files and their surviving layers, in document order.
 */
internal fun relinkGroups(sources: List<ArtSource>, query: String): List<RelinkGroup> {
	val trimmed = query.trim()
	return sources.mapNotNull { source ->
		// A row the file lost is kept for the review, never offered as a target.
		val present = source.layers.filter { layer -> layer.present }
		val layers =
			if (trimmed.isEmpty() || source.name.contains(trimmed, ignoreCase = true)) {
				present
			} else {
				present.filter { layer -> layer.name.contains(trimmed, ignoreCase = true) }
			}
		if (layers.isEmpty()) null else RelinkGroup(source, layers)
	}
}

/**
 * A tile row's relink chip: a searchable list of every listed file's layers, grouped under the file,
 * plus Unbind while the tile is bound.  Picking closes the panel and rebinds as one undo step.
 *
 * @param AtlasTileId tileId   The tile the chip rebinds.
 * @param PuppetModel puppet   The rig, for the candidates and the current binding.
 * @param Function    onRelink Rebinds tiles to one layer (null unbinds).
 */
@Composable
private fun RelinkChip(tileId: AtlasTileId, puppet: PuppetModel, onRelink: (List<AtlasTileId>, SourceLayerRef?) -> Unit) {
	val icons = LocalUmamoIcons
	var open by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	val current = puppet.atlas.tileById[tileId]?.source
	PopupChip(
		contentDescription = stringResource(Res.string.sources_relink_title),
		icon = if (current != null) icons.linked else icons.unlinked,
		expanded = open,
		onExpandedChange = { next -> open = next },
		// The row is 22.dp; the Header face would overflow it.
		style = DropdownChipStyle.Compact,
	) {
		RelinkList(puppet = puppet, current = current, query = query, onQueryChange = { updated -> query = updated }, showUnbind = current != null) { target ->
			open = false
			onRelink(listOf(tileId), target)
		}
	}
}

/**
 * The searchable list of every listed file's present layers, grouped under the file, that a relink
 * picks from - the tile chip's panel and the review chip's by-hand page.
 *
 * @param PuppetModel     puppet        The rig, for the candidates and the strength of a key some tile already binds.
 * @param SourceLayerRef? current       The binding the picker starts from, shown muted, or null.
 * @param String          query         The search text.
 * @param Function        onQueryChange Takes the edited search text.
 * @param Boolean         showUnbind    Whether the Unbind row leads the list.
 * @param Function        onPick        Takes the chosen binding, or null for Unbind.
 */
@Composable
private fun RelinkList(
	puppet: PuppetModel,
	current: SourceLayerRef?,
	query: String,
	onQueryChange: (String) -> Unit,
	showUnbind: Boolean,
	onPick: (SourceLayerRef?) -> Unit,
) {
	val colors = LocalUmamoColors.current
	val groups = remember(puppet.sources, query) { relinkGroups(puppet.sources, query) }
	Column(modifier = Modifier.width(RELINK_PANEL_WIDTH)) {
		SearchField(
			value = query,
			onValueChange = onQueryChange,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
			width = RELINK_PANEL_WIDTH - 16.dp,
		)
		// A plain scrolling column, not a lazy list: the popup measures its content intrinsically,
		// which a lazy list cannot answer (see UvLayerPickerChip).
		Column(modifier = Modifier.fillMaxWidth().heightIn(max = RELINK_MAX_LIST_HEIGHT).verticalScroll(rememberScrollState())) {
			if (showUnbind) {
				RelinkRow(label = stringResource(Res.string.sources_relink_clear), muted = true) { onPick(null) }
			}
			if (groups.isEmpty()) {
				Text(text = stringResource(Res.string.sources_relink_no_matches), color = colors.textMuted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
			}
			for (group in groups) {
				val source = group.source
				FilterSectionLabel(text = source.name)
				for (layer in group.layers) {
					val key = layer.key
					val bound = current?.sourceId == source.id && current.layerKey == key
					RelinkRow(label = layer.name, muted = bound, indented = true) {
						if (bound) {
							return@RelinkRow
						}
						// A layer some tile already binds says how strong its key is; otherwise the key's shape does.
						val stable =
							puppet.atlas.tiles
								.mapNotNull { tile -> tile.source }
								.firstOrNull { ref -> ref.sourceId == source.id && ref.layerKey == key }
								?.stableKey
						onPick(SourceLayerRef(source.id, key, stableKey = stable ?: layerKeyLooksStable(key)))
					}
				}
			}
		}
	}
}

/**
 * One row of the relink list: the label, hover-highlighted, acting on click.
 *
 * @param String   label    The row text.
 * @param Boolean  muted    Whether the row reads as secondary (the current binding, the unbind action).
 * @param Boolean  indented Whether the row sits under a file heading, inset past it.
 * @param Function onClick  Invoked when the row is chosen.
 */
@Composable
private fun RelinkRow(label: String, muted: Boolean, indented: Boolean = false, onClick: () -> Unit) {
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
				.padding(start = if (indented) 16.dp else 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
	)
}