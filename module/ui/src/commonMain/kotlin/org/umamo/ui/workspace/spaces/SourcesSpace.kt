package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
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
import org.umamo.ui.kit.BelowAnchorPositionProvider
import org.umamo.ui.kit.ContextMenuArea
import org.umamo.ui.kit.DisclosureChevron
import org.umamo.ui.kit.DropdownChip
import org.umamo.ui.kit.DropdownChipStyle
import org.umamo.ui.kit.Menu
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.IconSlot
import org.umamo.ui.model.LocalDrawableThumbnails
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.model.LocalSourceArtRasters
import org.umamo.ui.model.LocalSourceFilePresence
import org.umamo.ui.model.LocalSourceSuggestions
import org.umamo.ui.model.LocalSourceWatch
import org.umamo.ui.model.SourceTileThumbnails
import org.umamo.ui.model.percentOf
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.UmamoColors
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.UmamoIcons
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.commands.DeleteArtRequest
import org.umamo.ui.workspace.commands.IgnoreLayerRequest
import org.umamo.ui.workspace.commands.RelinkRequest
import org.umamo.ui.workspace.commands.ReloadScope
import org.umamo.ui.workspace.commands.ReplaceRequest
import org.umamo.ui.workspace.rowdrag.RowCoordinatesHolder
import org.umamo.ui.workspace.rowdrag.RowDragController
import org.umamo.ui.workspace.rowdrag.RowDragLabel
import org.umamo.ui.workspace.rowdrag.dragRowOnLongPress
import org.umamo.ui.workspace.rowdrag.parkCancelOnSeam
import org.umamo.ui.workspace.rowdrag.rowDropHighlight

/*
 * The Sources space: the linking table between the document's artwork files and its art.  File ->
 * layer -> tile -> drawables, each with a status; a layer row dragged onto a tile row (or the reverse)
 * rebinds the tile, a tile row's chip picks a layer or unbinds, a row that needs review carries the
 * matcher's proposal to accept or a relink by hand, an unbound layer row's chip (or its context menu)
 * ignores the layer so a reload never mints it, and a file row's chip (or its context menu) replaces
 * or reloads that one file.  Drawable rows select, so the table is also a way into the rig by the art
 * it came from.
 */

private val SOURCES_ROW_HEIGHT = 22.dp
private val SOURCES_INDENT_PER_DEPTH = 12.dp
private val SOURCES_CHEVRON_WIDTH = 14.dp
private val SOURCES_ICON_WIDTH = 16.dp

/** The relink menu's width, which its search box fixes so the rows stay put as the search narrows them. */
private val RELINK_PANEL_WIDTH = 320.dp

/** What a dragged row carries: the binding a layer row stands for, or the tile a tile row stands for. */
internal sealed interface SourcesDragPayload {
	data class Layer(val ref: SourceLayerRef) : SourcesDragPayload

	data class Tile(val tileId: AtlasTileId) : SourcesDragPayload
}

/** The art a row previews on hover: a tile's source art, or a drawable's crop of the atlas. */
internal sealed interface SourcesPreviewSubject {
	data class Tile(val tileId: AtlasTileId) : SourcesPreviewSubject

	data class Drawable(val drawableId: DrawableId) : SourcesPreviewSubject
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
	val filtered = remember(tree, query, viewState.filters) { filterSourcesTree(tree, query, viewState.filters) }
	// Expand state by node id, on the view state so a saved document carries it (UMA §7.3).  Files and the
	// unbound group open by default; layers and tiles close.
	val expanded = viewState.expanded
	val searching = query.isNotBlank()
	val isOpen: (String) -> Boolean = { id -> searching || viewState.isOpen(id) }
	val rows = remember(filtered, expanded.toMap(), searching) { flattenSources(filtered, isOpen) }
	val nodeById = remember(rows) { rows.associate { row -> row.node.id to row.node } }

	// Drag-and-drop: long-press a layer or tile row, drop it on the other kind to rebind.  Transient,
	// per space instance; Escape cancels through the shell's shared seam like the outliner.
	val dragController = remember { RowDragController<SourcesDragPayload>() }
	dragController.parkCancelOnSeam()
	// A relink is a command, not a session edit from here: the app reads the layer's file and pulls its
	// art in (a binding-only change when it cannot), and the shell resolves where the strip shows.
	val commands = LocalCommands.current
	val relink: (List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit = { tileIds, ref, retire -> commands.invoke("sources.relink", RelinkRequest(tileIds, ref, retire)) }
	val performDrop: () -> Unit = {
		val payload = dragController.draggedPayload
		val target = dragController.dropTargetKey?.let { key -> nodeById[key] }
		if (session != null && payload != null && target != null) {
			relinkFor(payload, target)?.let { (tileId, ref) -> relink(listOf(tileId), ref, emptyList()) }
		}
		dragController.end()
	}

	// Hover art preview: a layer or tile row shows its source art from the document's raster store, a
	// drawable row the atlas crop the outliner shows.  Either provider may be absent, which previews nothing.
	val artRasters = LocalSourceArtRasters.current
	val tileThumbnails = remember(artRasters) { artRasters?.let { store -> SourceTileThumbnails(store) } }
	val drawableThumbnails = LocalDrawableThumbnails.current
	val hoverPreview = rememberRowHoverPreviewState<String>()

	if (tree.isEmpty()) {
		Box(modifier = modifier.fillMaxSize().zebraFill(listState, SOURCES_ROW_HEIGHT, colors.rowStripe))
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
				hoverPreview = hoverPreview,
			)
		}
	}
	// One art preview for the whole space, beside the rested-on row, once its art resolves.
	val preview = hoverPreview.shown
	val previewBitmap =
		preview?.let { shown -> nodeById[shown.key]?.let(::sourcesPreviewSubject) }?.let { subject ->
			when (subject) {
				is SourcesPreviewSubject.Tile -> tileThumbnails?.thumbnailFor(subject.tileId)
				is SourcesPreviewSubject.Drawable -> drawableThumbnails?.thumbnailFor(subject.drawableId)
			}
		}
	if (preview != null && previewBitmap != null) {
		RowThumbnailPreview(name = preview.name, thumbnail = previewBitmap, anchorRect = preview.rowBounds)
	}
	// A name chip follows the cursor while dragging, so there is something clearly "in hand" beyond the
	// faded row: the row being dragged (a layer or a tile).
	val draggingLabel = dragController.draggingKey?.let { id -> nodeById[id]?.label }
	if (dragController.isDragging && draggingLabel != null) {
		RowDragLabel(label = draggingLabel, cursorX = dragController.dragWindowX, cursorY = dragController.dragWindowY)
	}
}

/**
 * The rebind a drop means: a layer onto a tile, or a tile onto a layer; anything else is no drop.  A
 * layer row under review (lost, erased, or lost to a replacement) is no target either - it stands for
 * a review, not for art a tile could take, and a binding to it would read as needing review the moment
 * it landed - and neither is an ignored row, which the rigger keeps out of the rig.
 *
 * @param SourcesDragPayload payload The dragged row.
 * @param SourcesNode        target  The row it was dropped on.
 * @return Pair? The tile to rebind and its new binding, or null.
 */
internal fun relinkFor(payload: SourcesDragPayload, target: SourcesNode): Pair<AtlasTileId, SourceLayerRef>? {
	val kind = target.kind
	return when {
		payload is SourcesDragPayload.Layer && kind is SourcesNodeKind.Tile -> kind.tileId to payload.ref
		payload is SourcesDragPayload.Tile && kind is SourcesNodeKind.Layer && !target.status.isReview && target.status != SourcesStatus.Ignored -> payload.tileId to kind.ref
		else -> null
	}
}

/**
 * The art a row previews on hover: a tile row its tile, a layer row the first tile bound to it, and a
 * drawable row its drawable.  A layer under review still binds its old tile, so its row previews the art
 * the review is about.  A file row, the unbound group, and a layer no tile binds preview nothing - an
 * unbound layer's pixels live in its file, not in the document.
 *
 * @param SourcesNode node The hovered row.
 * @return SourcesPreviewSubject? What the row previews, or null for nothing.
 */
internal fun sourcesPreviewSubject(node: SourcesNode): SourcesPreviewSubject? =
	when (val kind = node.kind) {
		is SourcesNodeKind.Tile -> SourcesPreviewSubject.Tile(kind.tileId)
		is SourcesNodeKind.Layer -> node.children.firstNotNullOfOrNull { child -> (child.kind as? SourcesNodeKind.Tile)?.let { tile -> SourcesPreviewSubject.Tile(tile.tileId) } }
		is SourcesNodeKind.Drawable -> SourcesPreviewSubject.Drawable(kind.drawableId)
		is SourcesNodeKind.Source, SourcesNodeKind.UnboundGroup -> null
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
 * tile row's relink chip, a review row's proposal chip, an unbound layer row's ignore chip, a file
 * row's actions chip (the last two mirrored in the row's context menu).
 *
 * @param SourcesRow  row            The row.
 * @param PuppetModel puppet         The rig, for the relink chip's candidates and the click's targets.
 * @param Boolean     expanded       Whether the row's children are shown.
 * @param Boolean     selected       Whether the row's drawable is in the session selection.
 * @param Function    onToggle       Flips the expand state.
 * @param Function    onSelect       Selects the given targets.
 * @param Function    onRelink       Rebinds tiles to one layer (null unbinds), retiring the tiles the accepted proposal named.
 * @param RowDragController dragController The space's drag state.
 * @param Function    onDrop         Applies the drop on release.
 * @param RowHoverPreviewState hoverPreview The space's hover preview state the row reports into.
 */
@Composable
private fun SourcesRowView(
	row: SourcesRow,
	puppet: PuppetModel,
	expanded: Boolean,
	selected: Boolean,
	onToggle: () -> Unit,
	onSelect: (List<SelectionTarget>) -> Unit,
	onRelink: (List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit,
	dragController: RowDragController<SourcesDragPayload>,
	onDrop: () -> Unit,
	hoverPreview: RowHoverPreviewState<String>,
) {
	val node = row.node
	val colors = LocalUmamoColors.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val boundsHolder = remember { RowCoordinatesHolder() }
	ReportRowHover(
		state = hoverPreview,
		key = node.id,
		name = node.label,
		hovered = hovered,
		enabled = sourcesPreviewSubject(node) != null,
		boundsHolder = boundsHolder,
	)
	val currentOnDrop by rememberUpdatedState(onDrop)
	// A layer under review drags nowhere: its binding is what is under review, not a layer to offer; an
	// ignored layer stays out of the rig until its row says otherwise.
	val payload: SourcesDragPayload? =
		when (val kind = node.kind) {
			is SourcesNodeKind.Layer -> if (node.status.isReview || node.status == SourcesStatus.Ignored) null else SourcesDragPayload.Layer(kind.ref)
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
	// A valid target (a layer under a dragged tile, a tile under a dragged layer) takes the shared drop ring;
	// anything else under the pointer shows nothing, so the rigger sees where a release would bind.
	val background =
		when {
			selected -> colors.accent.copy(alpha = 0.25f)
			hovered -> colors.rowHover
			else -> Color.Transparent
		}
	val sourceKind = node.kind as? SourcesNodeKind.Source
	val layerKind = node.kind as? SourcesNodeKind.Layer
	val commands = LocalCommands.current
	val body: @Composable () -> Unit = {
		SourcesRowBody(
			row = row,
			puppet = puppet,
			expanded = expanded,
			background = background,
			isDropTarget = isDropTarget,
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
	// A secondary press falls through the row's clickable to the menu; the items are the row's own
	// chip's, so a mouse and a pen reach the same actions.
	when {
		sourceKind != null -> ContextMenuArea(items = sourceFileMenuItems(sourceKind.sourceId, commands), content = body)
		layerKind != null && ignorable(node.status) ->
			ContextMenuArea(items = layerMenuItems(layerKind.ref, ignored = node.status == SourcesStatus.Ignored, commands = commands), content = body)
		else -> body()
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
 * Whether a layer row carries the ignore toggle: an unbound present layer, or one already ignored.  A
 * bound row is matched by key and needs no mark; a review row has a binding to settle first.
 *
 * @param SourcesStatus status The row's status.
 * @return Boolean True when the row may be ignored or un-ignored.
 */
private fun ignorable(status: SourcesStatus): Boolean = status == SourcesStatus.Unbound || status == SourcesStatus.Ignored

/**
 * An unbound layer row's one action, as the chip and the context menu both list it: Ignore Layer
 * (`sources.ignoreLayer`, so a reload never mints a drawable for it) while the row is plain unbound,
 * Stop Ignoring once it is.
 *
 * @param SourceLayerRef                      ref      The layer.
 * @param Boolean                             ignored  Whether the row is ignored now.
 * @param org.umamo.ui.action.CommandRegistry commands The registry to dispatch through.
 * @return List<MenuItem> The item.
 */
@Composable
private fun layerMenuItems(ref: SourceLayerRef, ignored: Boolean, commands: org.umamo.ui.action.CommandRegistry): List<MenuItem> =
	listOf(
		MenuItem.Action(
			stringResource(if (ignored) Res.string.sources_layer_menu_unignore else Res.string.sources_layer_menu_ignore),
			onSelect = { commands.invoke("sources.ignoreLayer", IgnoreLayerRequest(ref, ignored = !ignored)) },
		),
	)

/**
 * The row itself, laid out inside whatever wraps it.
 *
 * @param SourcesRow  row            The row.
 * @param PuppetModel puppet         The rig.
 * @param Boolean     expanded       Whether the row's children are shown.
 * @param Color       background     The row's fill for its hover or selection state.
 * @param Boolean     isDropTarget   Whether a release would bind onto this row, which draws the drop ring.
 * @param Boolean     isDragged      Whether the row is the one being dragged.
 * @param MutableInteractionSource interaction The row's hover source.
 * @param RowCoordinatesHolder     boundsHolder The row's coordinates holder.
 * @param SourcesDragPayload?      payload      What a drag from the row carries, or null when it cannot be dragged.
 * @param Function    onToggle       Flips the expand state.
 * @param Function    onSelect       Selects the given targets.
 * @param Function    onRelink       Rebinds tiles to one layer (null unbinds), retiring the tiles the accepted proposal named.
 * @param RowDragController dragController The space's drag state.
 * @param Function    onDropNow      Applies the drop on release.
 */
@Composable
private fun SourcesRowBody(
	row: SourcesRow,
	puppet: PuppetModel,
	expanded: Boolean,
	background: Color,
	isDropTarget: Boolean,
	isDragged: Boolean,
	interaction: MutableInteractionSource,
	boundsHolder: RowCoordinatesHolder,
	payload: SourcesDragPayload?,
	onToggle: () -> Unit,
	onSelect: (List<SelectionTarget>) -> Unit,
	onRelink: (List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit,
	dragController: RowDragController<SourcesDragPayload>,
	onDropNow: () -> Unit,
) {
	val node = row.node
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val icons = LocalUmamoIcons
	val commands = LocalCommands.current
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier =
			Modifier
				.fillMaxWidth()
				.height(SOURCES_ROW_HEIGHT)
				// The dragged row fades whole, fill and ring included, so the alpha layer wraps what follows.
				.alpha(if (isDragged) 0.4f else 1f)
				.background(background, shape = shapes.small)
				.rowDropHighlight(isDropTarget, shapes.small, colors)
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
				// Long-press to pick a layer or tile row up; a file or drawable row (no payload) never lifts.
				.dragRowOnLongPress(dragController, node.id, payload, boundsHolder, onDropNow)
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
				RelinkChip(
					tileId = kind.tileId,
					puppet = puppet,
					onRelink = onRelink,
					// Only a tile nothing samples may leave the atlas; a sampled one would strand its drawables.
					canDelete = puppet.drawables.none { drawable -> drawable.atlasTileId == kind.tileId },
					onDelete = { commands.invoke("sources.deleteArt", DeleteArtRequest(kind.tileId)) },
				)
			}
			is SourcesNodeKind.Layer ->
				if (node.status.isReview) {
					Spacer(modifier = Modifier.width(6.dp))
					ReviewChip(node = node, ref = kind.ref, puppet = puppet, onRelink = onRelink)
				} else if (ignorable(node.status)) {
					Spacer(modifier = Modifier.width(6.dp))
					LayerChip(ref = kind.ref, ignored = node.status == SourcesStatus.Ignored, commands = commands)
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
 * menu offers, drawn as the kit [Menu] so the chip and the context menu are one menu in two places.
 *
 * @param ArtSourceId                         sourceId The file.
 * @param org.umamo.ui.action.CommandRegistry commands The registry to dispatch through.
 */
@Composable
private fun SourceFileChip(sourceId: ArtSourceId, commands: org.umamo.ui.action.CommandRegistry) {
	val icons = LocalUmamoIcons
	var open by remember { mutableStateOf(false) }
	val items = sourceFileMenuItems(sourceId, commands)
	DropdownChip(
		expanded = open,
		onExpandRequest = { open = true },
		contentDescription = stringResource(Res.string.sources_file_menu),
		icon = icons.dots,
		style = DropdownChipStyle.Compact,
	) {
		Menu(items = items, onDismissRequest = { open = false }, positionProvider = BelowAnchorPositionProvider)
	}
}

/**
 * An unbound layer row's actions chip: Ignore Layer, or Stop Ignoring once it is, the same item the
 * row's context menu offers, drawn as the kit [Menu] so the chip and the menu are one menu in two places.
 *
 * @param SourceLayerRef                      ref      The layer.
 * @param Boolean                             ignored  Whether the row is ignored now.
 * @param org.umamo.ui.action.CommandRegistry commands The registry to dispatch through.
 */
@Composable
private fun LayerChip(ref: SourceLayerRef, ignored: Boolean, commands: org.umamo.ui.action.CommandRegistry) {
	val icons = LocalUmamoIcons
	var open by remember { mutableStateOf(false) }
	val items = layerMenuItems(ref, ignored, commands)
	DropdownChip(
		expanded = open,
		onExpandRequest = { open = true },
		contentDescription = stringResource(Res.string.sources_layer_menu),
		icon = icons.dots,
		style = DropdownChipStyle.Compact,
	) {
		Menu(items = items, onDismissRequest = { open = false }, positionProvider = BelowAnchorPositionProvider)
	}
}

/**
 * A review row's chip: the matcher's proposal to accept (the candidate's name and confidence, and
 * whether a fresh drawable over the candidate goes with it), a relink by hand through the same list a
 * tile row's chip shows, or leave the binding as it is.  Accepting is one relink of every tile bound to
 * the lost key, so the art is pulled exactly as a manual relink pulls it and the tiles move together as
 * one step, naming the tiles the proposal retires; the planner re-checks those for rig work before any
 * leaves.  A relink by hand names none.
 *
 * @param SourcesNode    node     The review row, carrying its suggestion when there is one.
 * @param SourceLayerRef ref      The lost binding the row stands for.
 * @param PuppetModel    puppet   The rig, for the relink list.
 * @param Function       onRelink Rebinds the tiles as one step (null unbinds), with the tiles the proposal retires.
 */
@Composable
private fun ReviewChip(node: SourcesNode, ref: SourceLayerRef, puppet: PuppetModel, onRelink: (List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit) {
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
	val relinkAll: (SourceLayerRef?, List<AtlasTileId>) -> Unit = { target, retire ->
		if (boundTiles.isNotEmpty()) {
			onRelink(boundTiles, target, retire)
		}
	}
	// The proposal page is a kit Menu, like every other menu in the application; picking Relink by
	// hand… flips the SAME open chip to the stay-open list panel (the menu row runs its action before
	// the root dismiss, so the dismiss sees the flag and keeps the chip open).
	val menuItems =
		buildList {
			if (suggestion != null) {
				val acceptLabel =
					if (suggestion.retires.isEmpty()) {
						stringResource(Res.string.sources_suggestion_accept, suggestion.candidateName, percentOf(suggestion.score))
					} else {
						stringResource(Res.string.sources_suggestion_accept_merge, suggestion.candidateName, percentOf(suggestion.score))
					}
				add(
					MenuItem.Action(
						label = acceptLabel,
						onSelect = { relinkAll(SourceLayerRef(ref.sourceId, suggestion.candidateKey, stableKey = layerKeyLooksStable(suggestion.candidateKey)), suggestion.retires) },
					),
				)
			}
			add(MenuItem.Action(label = stringResource(Res.string.sources_suggestion_relink), onSelect = { byHand = true }))
			add(MenuItem.Action(label = stringResource(Res.string.sources_suggestion_leave), onSelect = {}))
		}
	DropdownChip(
		expanded = open,
		onExpandRequest = {
			open = true
			byHand = false
		},
		contentDescription = stringResource(Res.string.sources_suggestion_title),
		icon = icons.linked,
		style = DropdownChipStyle.Compact,
		iconTint = colors.signalCaution,
	) {
		if (byHand) {
			Menu(
				items = relinkMenuItems(puppet, ref, query, { updated -> query = updated }, showUnbind = false) { target -> relinkAll(target, emptyList()) },
				onDismissRequest = {
					open = false
					byHand = false
				},
				positionProvider = BelowAnchorPositionProvider,
			)
		} else {
			Menu(
				items = menuItems,
				onDismissRequest = {
					if (!byHand) {
						open = false
					}
				},
				positionProvider = BelowAnchorPositionProvider,
			)
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
 * an unbound layer, a missing file, or the unbound-art group, and the muted text color for a layer the
 * rigger ignored.  The glyph itself already says what the
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
				// Bound to a layer the file still has but erased: the same wait, with a different reason on the tooltip.
				SourcesStatus.Emptied -> SourcesRowVisual(icons.unlinked, colors.signalCaution, Res.string.sources_status_emptied)
				// Bound to a key the replacement file does not mint: the same wait again, and the tooltip says so.
				SourcesStatus.SourceReplaced -> SourcesRowVisual(icons.unlinked, colors.signalCaution, Res.string.sources_status_replaced)
				// Kept out of the rig on purpose: no signal color, since the row is settled rather than waiting.
				SourcesStatus.Ignored -> SourcesRowVisual(icons.unlinked, colors.textMuted, Res.string.sources_status_ignored)
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
		// A row the file lost is kept for the review, never offered as a target; a layer erased to nothing
		// has no art to give; an ignored layer is kept out of the rig until its row says otherwise.
		val present = source.layers.filter { layer -> layer.present && !layer.empty && !layer.ignored }
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
 * A tile row's relink chip: a searchable menu of every listed file's layers, grouped under the file,
 * plus Unbind while the tile is bound.  Picking closes the menu and rebinds as one undo step.
 *
 * @param AtlasTileId tileId   The tile the chip rebinds.
 * @param PuppetModel puppet   The rig, for the candidates and the current binding.
 * @param Function    onRelink Rebinds tiles to one layer (null unbinds); a chip relink retires nothing.
 */
@Composable
private fun RelinkChip(
	tileId: AtlasTileId,
	puppet: PuppetModel,
	onRelink: (List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit,
	canDelete: Boolean,
	onDelete: () -> Unit,
) {
	val icons = LocalUmamoIcons
	var open by remember { mutableStateOf(false) }
	var query by remember { mutableStateOf("") }
	val current = puppet.atlas.tileById[tileId]?.source
	DropdownChip(
		expanded = open,
		onExpandRequest = { open = true },
		contentDescription = stringResource(Res.string.sources_relink_title),
		icon = if (current != null) icons.linked else icons.unlinked,
		// The row is 22.dp; the Header face would overflow it.
		style = DropdownChipStyle.Compact,
	) {
		Menu(
			items =
				relinkMenuItems(puppet, current, query, { updated -> query = updated }, showUnbind = current != null, onDelete = onDelete.takeIf { canDelete }) { target ->
					onRelink(listOf(tileId), target, emptyList())
				},
			onDismissRequest = { open = false },
			positionProvider = BelowAnchorPositionProvider,
		)
	}
}

/**
 * The searchable relink menu: every listed file's present layers under the file's name, with the
 * search box on top and Unbind leading when asked - the tile chip's menu and the review chip's by-hand
 * page, as one kit [Menu] like every other menu.  The current binding reads dimmed and does nothing;
 * a query that matches nothing leaves one dimmed line saying so.
 *
 * @param PuppetModel     puppet        The rig, for the candidates and the strength of a key some tile already binds.
 * @param SourceLayerRef? current       The binding the picker starts from, shown dimmed, or null.
 * @param String          query         The search text.
 * @param Function        onQueryChange Takes the edited search text.
 * @param Boolean         showUnbind    Whether the Unbind row leads the list.
 * @param Function?       onDelete      Removes the tile from the atlas, offered as a Delete Art row when
 *   non-null (a tile no drawable samples); null hides the row.
 * @param Function        onPick        Takes the chosen binding, or null for Unbind; the menu dismisses itself.
 * @return List<MenuItem> The menu, search box first.
 */
@Composable
private fun relinkMenuItems(
	puppet: PuppetModel,
	current: SourceLayerRef?,
	query: String,
	onQueryChange: (String) -> Unit,
	showUnbind: Boolean,
	onDelete: (() -> Unit)? = null,
	onPick: (SourceLayerRef?) -> Unit,
): List<MenuItem> {
	val groups = remember(puppet.sources, query) { relinkGroups(puppet.sources, query) }
	val unbindLabel = stringResource(Res.string.sources_relink_clear)
	val deleteLabel = stringResource(Res.string.sources_relink_delete)
	val noMatchesLabel = stringResource(Res.string.sources_relink_no_matches)
	return buildList {
		add(MenuItem.Search(value = query, onValueChange = onQueryChange, width = RELINK_PANEL_WIDTH))
		if (showUnbind) {
			add(MenuItem.Action(label = unbindLabel, onSelect = { onPick(null) }))
		}
		if (onDelete != null) {
			add(MenuItem.Action(label = deleteLabel, onSelect = onDelete))
		}
		if (showUnbind || onDelete != null) {
			add(MenuItem.Separator)
		}
		if (groups.isEmpty()) {
			add(MenuItem.Action(label = noMatchesLabel, onSelect = {}, enabled = false))
		}
		for (group in groups) {
			val source = group.source
			add(MenuItem.Heading(source.name))
			for (layer in group.layers) {
				val key = layer.key
				val bound = current?.sourceId == source.id && current.layerKey == key
				add(
					MenuItem.Action(
						label = layer.name,
						enabled = !bound,
						onSelect = {
							// A layer some tile already binds says how strong its key is; otherwise the key's shape does.
							val stable =
								puppet.atlas.tiles
									.mapNotNull { tile -> tile.source }
									.firstOrNull { ref -> ref.sourceId == source.id && ref.layerKey == key }
									?.stableKey
							onPick(SourceLayerRef(source.id, key, stableKey = stable ?: layerKeyLooksStable(key)))
						},
					),
				)
			}
		}
	}
}