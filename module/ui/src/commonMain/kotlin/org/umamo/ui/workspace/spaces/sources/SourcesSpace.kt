package org.umamo.ui.workspace.spaces.sources

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import kotlinx.coroutines.ensureActive
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.reimport.LayerMatch
import org.umamo.reimport.suggestionsFor
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.model.LocalDrawableThumbnails
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.model.LocalSourceArtRasters
import org.umamo.ui.model.artwork.LocalSourceFilePresence
import org.umamo.ui.model.artwork.LocalSourceSuggestions
import org.umamo.ui.model.artwork.LocalSourceWatch
import org.umamo.ui.model.thumbnails.SourceTileThumbnails
import org.umamo.ui.rememberBooleanSetting
import org.umamo.ui.settings.IMPORT_LAYER_POSITIONS_FROM_WORLD_AXES_KEY
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.commands.RelinkRequest
import org.umamo.ui.workspace.rowdrag.RowDragController
import org.umamo.ui.workspace.rowdrag.RowDragLabel
import org.umamo.ui.workspace.rowdrag.parkCancelOnSeam
import org.umamo.ui.workspace.spaces.RowThumbnailPreview
import org.umamo.ui.workspace.spaces.rememberRowHoverPreviewState
import org.umamo.ui.workspace.spaces.trackRowHoverPointer
import org.umamo.ui.workspace.spaces.zebraFill

/*
 * The Sources space.  This file is the body's entry point; the rest of the package, by role:
 *
 *   SourcesHeaderControls.kt   the area header's controls: import, the name search, match, reload, and the filter
 *   SourcesViewState.kt        the view state the header and the body share
 *   SourcesRowViews.kt         one table row: its context-menu frame, its body, and its secondary text
 *   SourcesRowChips.kt         the chip a row ends with: a row's actions, a review's proposal, a tile's relink
 *   SourcesRowMetrics.kt       the measurements every row shares
 *   SourcesMenus.kt            the entries of every menu a row and its chip show
 *   SourcesLabels.kt           the localized chrome
 *
 * and, holding no composable so commonTest pins them directly:
 *
 *   SourcesTree.kt             the table as a tree, its filtering, and the key-shape rule
 *   SourcesRows.kt             the visible rows, what a click selects, and what a hover previews
 *   SourcesRowDrag.kt          what a row drags, what a drop rebinds, and the drop dispatch
 *   SourcesRowVisual.kt        the glyph, the tint, and the status word a row's icon reads as
 *   SourcesRelink.kt           the relink list grouped by file, and the binding a relink makes
 *
 * The row drag kit it drags with is org.umamo.ui.workspace.rowdrag, and the hover preview it pops is
 * org.umamo.ui.workspace.spaces.RowHoverPreview; both are shared with the Outliner.  The commands a row
 * dispatches are the shell's (workspace/commands/FileCommands.kt); the app reads the files.
 */

/**
 * The Sources space: the linking table between the document's artwork files and its art, file -> layer ->
 * tile -> drawables, each row with its status.  Drawable rows select, so the table is also a way into the
 * rig by the art it came from.
 *
 * This composable is the wiring: it derives the visible rows from the tree, holds the list, the drag
 * state, and the hover preview, and hands each row to [SourcesRowView].
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
	val viewState = scope.spaceState(SOURCES_VIEW_STATE_KEY) { SourcesViewState() }
	val labels = sourcesLabels()
	val tree = rememberSourcesTree(puppet, viewState, labels.unboundArt)
	val query = viewState.query
	val filtered = remember(tree, query, viewState.filters) { filterSourcesTree(tree, query, viewState.filters) }
	// Expand state by node id, on the view state so a saved document carries it (UMA §7.3).  Files and the
	// unbound group open by default; layers and tiles close.
	val expanded = viewState.expanded
	// During a search every row opens, so a match is never hidden behind a closed row.
	val searching = viewState.searching
	val isOpen: (String) -> Boolean = { id -> searching || viewState.isOpen(id) }
	val rows = remember(filtered, expanded.toMap(), searching) { flattenSources(filtered, isOpen) }
	val nodeById = remember(rows) { rows.associate { row -> row.node.id to row.node } }
	// What a drop reads the rows through.  The map is a new object after every fold, and a callback holding
	// the map itself would be a new callback for every row, which would run them all.
	val currentNodeById = rememberUpdatedState(nodeById)

	// Drag-and-drop: long-press a layer or tile row, drop it on the other kind to rebind.  Transient,
	// per space instance; Escape cancels through the shell's shared seam like the outliner.
	val dragController = remember { RowDragController<SourcesDragPayload>() }
	dragController.parkCancelOnSeam()
	// A relink is a command, not a session edit from here: the app reads the layer's file and pulls its
	// art in (a binding-only change when it cannot), and the shell resolves where the strip shows.
	val commands = LocalCommands.current
	val relink: (List<AtlasTileId>, SourceLayerRef?, List<AtlasTileId>) -> Unit = { tileIds, ref, retire -> commands.invoke("sources.relink", RelinkRequest(tileIds, ref, retire)) }
	// One release handler for every row's drag: a drop reads the space's drag state, not the row it began
	// on, and lands only while a session is there to take the edit.
	val onDrop: () -> Unit = { performSourcesDrop(dragController, currentNodeById.value, relink.takeIf { session != null }) { nodeId -> viewState.open(nodeId) } }
	// One selection handler for every row: a row knows what its click selects, the space how to select it.
	val onSelect: (List<SelectionTarget>) -> Unit = { targets ->
		if (session != null && targets.isNotEmpty()) {
			session.setSelection(targets.drop(1).fold(SelectionOps.replace(targets.first())) { acc, target -> SelectionOps.add(acc, target) })
		}
	}

	// Hover art preview: a layer or tile row shows its source art from the document's raster store, a
	// drawable row the atlas crop the outliner shows.  Either provider may be absent, which previews nothing.
	val artRasters = LocalSourceArtRasters.current
	val tileThumbnails = remember(artRasters) { artRasters?.let { store -> SourceTileThumbnails(store) } }
	val drawableThumbnails = LocalDrawableThumbnails.current
	val hoverPreview = rememberRowHoverPreviewState<String>()
	val hoverPreviewsEnabled = tileThumbnails != null || drawableThumbnails != null

	if (tree.isEmpty()) {
		Box(modifier = modifier.fillMaxSize().zebraFill(listState, SOURCES_ROW_HEIGHT, colors.rowStripe))

		return
	}

	// The list and the scrollbar over its right edge share one box.  The list is the first thing in it and
	// takes no padding of its own: the zebra fill behind it counts its rows from the box's top.
	Box(modifier = modifier.fillMaxSize().trackRowHoverPointer(hoverPreview, enabled = hoverPreviewsEnabled)) {
		LazyColumn(
			state = listState,
			modifier = Modifier.fillMaxSize().zebraFill(listState, SOURCES_ROW_HEIGHT, colors.rowStripe),
		) {
			items(rows, key = { row -> row.node.id }, contentType = { row -> row.node.kind::class }) { row ->
				SourcesRowView(
					row = row,
					expanded = isOpen(row.node.id),
					selected = row.node.kind.let { kind -> kind is SourcesNodeKind.Drawable && SelectionTarget.Drawable(kind.drawableId) in selection.targets },
					labels = labels,
					viewState = viewState,
					onSelect = onSelect,
					onRelink = relink,
					dragController = dragController,
					onDrop = onDrop,
					hoverPreviewsEnabled = hoverPreviewsEnabled,
					hoverPreview = hoverPreview,
				)
			}
		}
		VerticalScrollbarOverlay(listState)
	}
	// One art preview for the whole space, beside the rested-on row or at the pointer when the row has no
	// room beside it, once its art resolves.
	val preview = hoverPreview.shown
	val previewBitmap =
		preview?.let { shown -> nodeById[shown.key]?.let(::sourcesPreviewSubject) }?.let { subject ->
			when (subject) {
				is SourcesPreviewSubject.Tile -> tileThumbnails?.thumbnailFor(subject.tileId)
				is SourcesPreviewSubject.Drawable -> drawableThumbnails?.thumbnailFor(subject.drawableId)
			}
		}

	if (preview != null && previewBitmap != null) {
		RowThumbnailPreview(
			name = preview.name,
			thumbnail = previewBitmap,
			anchorRect = preview.rowBounds,
			pointerInWindow = { hoverPreview.pointerInWindow },
		)
	}

	// A name chip follows the cursor while dragging, so there is something clearly "in hand" beyond the
	// faded row: the row being dragged (a layer or a tile).  The space reads which row is in hand, which a
	// drag changes as it starts and as it ends; where the pointer is, the chip asks for itself.
	val draggingLabel = dragController.draggingKey?.let { id -> nodeById[id]?.label }

	if (draggingLabel != null) {
		RowDragLabel(label = draggingLabel, pointerInWindow = { dragController.pointerInWindow })
	}
}

/**
 * The Sources tree as the body derives it from the open document: every file's presence as the app's
 * probe last answered, the proposals for the bindings the files no longer resolve, and the tree built from
 * both.
 *
 * The probe is asked from an effect, never while composing: asking is a look at the disk.  Returns a value,
 * so it is no restart scope of its own: what it reads invalidates the space that calls it.
 *
 * @param PuppetModel      puppet            The rig to list.
 * @param SourcesViewState viewState         The area's view state, for the refresh serial.
 * @param String           unboundGroupLabel The localized label of the unbound-art group.
 * @return List<SourcesNode> The tree's top-level rows.
 */
@Composable
private fun rememberSourcesTree(puppet: PuppetModel, viewState: SourcesViewState, unboundGroupLabel: String): List<SourcesNode> {
	val presenceProbe = LocalSourceFilePresence.current
	// The watcher's presence serial: a file deleted or returned re-probes without a click.
	val watchSerial = LocalSourceWatch.current?.serial?.collectAsState()?.value ?: 0

	// One round of asking per change of the files, per refresh, and per change the watcher saw.  The
	// answers replace the last round's whole and only once all are in, so a missing file stays missing
	// while it is asked about again, and a round the next one overtook lands nothing.
	val sources = puppet.sources
	LaunchedEffect(sources, viewState.refreshSerial, watchSerial, presenceProbe) {
		val answers = probeSourcePresence(sources, presenceProbe)
		ensureActive()
		viewState.presenceBySource = answers
	}
	val presenceBySource = viewState.presenceBySource
	// Two tiers of proposal for a binding the file no longer resolves: the one the last operation that
	// read the file scored with pixels, else the one the model's own inventory ranks (names, folders,
	// bounds, hashes) - so the chips show something even before any file is read.
	val published = LocalSourceSuggestions.current?.suggestions?.collectAsState()?.value.orEmpty()
	// Keyed on what the ranking reads - the files and the tile bindings - not the whole model: every
	// committed edit publishes a model, few of them touch either, and the name-distance pass over every
	// lost layer and every candidate must not run for the rest.
	val modelSuggestions =
		remember(puppet.sources, puppet.atlas.tiles) {
			puppet.sources.associate { source -> source.id to suggestionsFor(puppet, source.id) }
		}
	val suggestionCandidates: (ArtSourceId, String) -> List<LayerMatch> =
		{ sourceId, key -> listOfNotNull(published[sourceId to key], modelSuggestions[sourceId]?.get(key)) }
	// Read live, so flipping the preference re-measures the layer rows at once.
	val layerPositionsFromWorldAxes by rememberBooleanSetting(IMPORT_LAYER_POSITIONS_FROM_WORLD_AXES_KEY, false)

	return remember(puppet, presenceBySource, unboundGroupLabel, published, layerPositionsFromWorldAxes) {
		buildSourcesTree(
			puppet,
			{ source: ArtSource -> presenceBySource[source.id] ?: SourcePresence.Unknown },
			unboundGroupLabel,
			layerPositionsFromWorldAxes = layerPositionsFromWorldAxes,
			suggestionsFor = suggestionCandidates,
		)
	}
}