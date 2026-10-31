package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.model.LocalDrawableThumbnails
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.PickClickOutcome
import org.umamo.ui.workspace.operationstrip.LocalOperationStripArea
import org.umamo.ui.workspace.rowdrag.RowDragController
import org.umamo.ui.workspace.rowdrag.RowDragLabel
import org.umamo.ui.workspace.rowdrag.parkCancelOnSeam
import org.umamo.ui.workspace.spaces.RowThumbnailPreview
import org.umamo.ui.workspace.spaces.rememberRowHoverPreviewState
import org.umamo.ui.workspace.spaces.trackRowHoverPointer
import org.umamo.ui.workspace.spaces.zebraFill

/*
 * The Outliner space.  This file is the body's entry point; the rest of the package, by role:
 *
 *   OutlinerHeaderControls.kt   the area header's controls: the name search and the filter dropdown
 *   OutlinerViewState.kt        the view state the header and the body share
 *   OutlinerRowViews.kt         one tree row: its context-menu frame, its body, and the ancestry guides
 *   OutlinerRowSlots.kt         the chevron, the type icon, the eye, and the pointer a row is built from
 *   OutlinerRowDrag.kt          the drop insertion line and the drop dispatch
 *   OutlinerRowMetrics.kt       the measurements every row shares, and the one width the rows are fixed to
 *   OutlinerMenus.kt            the entries of a row's context menu
 *   OutlinerLabels.kt           the localized chrome
 *
 * and, holding no composable so commonTest pins them directly:
 *
 *   OutlinerTree.kt             the unified tree, its filtering, and the Shift range rule
 *   OutlinerRows.kt             the visible rows, the path to a node, and what a click does to the selection
 *   OutlinerRevealStandDown.kt  which change of the selection is the outliner's own, and so not revealed
 *
 * The row drag kit it drags with is org.umamo.ui.workspace.rowdrag, and the hover preview it pops is
 * org.umamo.ui.workspace.spaces.RowHoverPreview; both are shared with the Sources space.  The drop rules a
 * drag lands by live in :edit (OutlinerTreeEdits.kt).
 */

/**
 * The outliner space: the unified Blender-style tree that folds Cubism's split Part and Deformer panels
 * into one.  A single puppet root holds the Armature deformer hierarchy followed by the parts, each part
 * listing its drawables then its sub-parts.  Reads the open document's puppet from [LocalPuppet] (empty
 * state when nothing is loaded).  Branches start collapsed (only the root is open); rows dim when hidden
 * or sketch and select through [LocalSelection] (plain click replaces, Ctrl / Cmd toggles, Shift adds).
 * A selection made elsewhere (a viewport pick) reveals its row - ancestors expand and the list scrolls
 * to it - but a click inside the outliner never scrolls the list.  Clearing the search reveals the active
 * selection the same way, so a row picked out of the results is not stranded behind a branch that closes
 * again.  The AREA header carries the name search and the filter dropdown (outlinerHeaderControls,
 * sharing this body's OutlinerViewState through [scope]); names never wrap, so a horizontal scroll
 * reaches long ones.
 *
 * This composable is the wiring: it derives the tree and its visible rows, holds the list, the rename
 * state, and the drag state, runs the two reveal effects, and hands each row to [OutlinerRowView].  The
 * chrome comes from [outlinerLabels], the rows' width from [outlinerContentWidth], and a drop lands
 * through [performOutlinerDrop].
 *
 * @param AreaScope scope The hosting area's scope carrying the shared view state.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun OutlinerSpace(scope: AreaScope, modifier: Modifier = Modifier) {
	val stripeColor = LocalUmamoColors.current.rowStripe
	val puppet = LocalPuppet.current

	if (puppet == null) {
		Box(modifier = modifier.fillMaxSize().zebraFill(rememberLazyListState(), OUTLINER_ROW_HEIGHT, stripeColor))

		return
	}

	val selectionHandle = LocalSelection.current
	// An armed relation pick (a Properties eyedropper) resolves from an outliner row as well as from a
	// viewport, so rows check it before selecting.
	val relationPick = LocalRelationPick.current
	val selection = selectionHandle?.selection ?: Selection()
	// The open document's session drives the per-row edits (eye toggle, inline rename); null when no
	// document is open, in which case those affordances no-op.
	val editorSession = LocalEditorSession.current
	// The node id whose label is being renamed in place (double-click opens it), or null when none is.
	// Not keyed on the puppet (see the expand-state note): a rename commit changes the model, which must
	// not yank the editor's own id state out from under it mid-edit.
	var renamingNodeId by remember { mutableStateOf<String?>(null) }
	// Drag-and-drop state: long-press a row to pick it up, drop onto another to reparent.  Transient, so
	// remembered per outliner instance (never keyed on the puppet).
	val dragController = remember { RowDragController<SelectionTarget>() }
	// While a drag is in flight its cancel is parked with the shell, so Escape aborts the drag instead of
	// falling through to the shell's clear-selection branch.
	dragController.parkCancelOnSeam()
	val labels = outlinerLabels()
	val tree = remember(puppet, labels.root, labels.armature) { buildOutlinerTree(puppet, labels.root, labels.armature) }
	// Search / filter state shared with the area-header controls (outlinerHeaderControls) through the
	// hosting AreaScope - the header slot is a sibling subtree, so a body-local remember cannot reach it.
	val viewState = scope.spaceState(OUTLINER_VIEW_STATE_KEY) { OutlinerViewState() }
	// Expand state keyed by stable node id, on the view state so a saved document carries it (UMA §7.3).
	// Default collapsed - only the root opens.
	val expanded = viewState.expanded
	val query = viewState.query
	// Marked by a click inside the outliner with the active target it is about to produce, so the reveal
	// can tell that change from one made elsewhere and skip the scroll.
	val revealStandDown = remember { OutlinerRevealStandDown() }
	val filteredTree =
		remember(tree, query, viewState.showParts, viewState.showDrawables, viewState.showDeformers) {
			filterOutliner(tree, query, viewState.showParts, viewState.showDrawables, viewState.showDeformers)
		}
	// During an active search every branch opens so matches are not hidden behind the collapsed default.
	val searching = viewState.searching
	val trimmedQuery = query.trim()
	val isOpen: (String) -> Boolean = { id -> searching || viewState.isOpen(id) }
	// Memoise the visible rows on what actually changes them, so the width measurement below is stable
	// across recompositions (and unaffected by vertical scrolling).
	val rows = remember(filteredTree, expanded.toMap(), searching) { flattenOutliner(filteredTree, isOpen) }
	// What a click and a drop read the rows through.  The list is a new object after every fold, and a
	// callback holding the list itself would be a new callback for every row, which would run them all.
	val currentRows = rememberUpdatedState(rows)
	val listState = rememberLazyListState()
	val horizontalScroll = rememberScrollState()
	val density = LocalDensity.current
	val textMeasurer = rememberTextMeasurer()
	val typography = LocalUmamoTypography.current
	// Part folders that contain (anywhere below) a selected node, so a collapsed parent still signals the
	// selection lives inside it.  Keyed by node id; computed from each selected target's path to the root.
	val ancestorParts =
		remember(filteredTree, selection) {
			buildSet {
				for (target in selection.targets) {
					val path = pathTo(filteredTree, target) ?: continue
					path.dropLast(1).forEach { ancestorId -> if (ancestorId.startsWith("part:")) add(ancestorId) }
				}
			}
		}

	// Hover art preview: the host's thumbnail provider (null on a platform / document without one, which
	// disables the preview), and the shared rest-delayed state the rows report into (RowHoverPreview.kt).
	val thumbnails = LocalDrawableThumbnails.current
	val hoverPreview = rememberRowHoverPreviewState<SelectionTarget>()

	// Reveal-on-select: a selection from elsewhere (a viewport pick) opens the target's ancestors and
	// scrolls to it; a selection made by clicking inside the outliner suppresses the scroll so the list
	// does not jump under the user's cursor.
	LaunchedEffect(selection.active) {
		if (revealStandDown.claims(selection.active)) {
			return@LaunchedEffect
		}

		val active = selection.active ?: return@LaunchedEffect
		revealOutlinerTarget(active, filteredTree, viewState, isOpen, listState)
	}

	// Reveal-on-search-cleared: a search opens every branch, so a row clicked out of the results sits in a
	// branch that closes again the moment the query goes away.  Revealing the active selection on that edge
	// keeps it in view.  Strictly the searching -> not-searching edge: doing it on every query change would
	// pull the list to the selection on each keystroke, and expand the ancestors of whatever the shifting
	// matches happen to be, which is nearly every branch.
	var wasSearching by remember { mutableStateOf(searching) }
	LaunchedEffect(searching) {
		val searchCleared = wasSearching && !searching
		wasSearching = searching

		if (!searchCleared) {
			return@LaunchedEffect
		}

		val active = selection.active ?: return@LaunchedEffect
		revealOutlinerTarget(active, filteredTree, viewState, isOpen, listState)
	}

	// One release handler for every row's drag: a drop reads the space's drag state, not the row it began
	// on, and expands the destination of a nest-inside drop.  The model is read through state at the
	// release, like the rows: a handler holding the model itself would be a new handler after every edit,
	// for every row.
	val currentPuppet = rememberUpdatedState(puppet)
	val stripArea = LocalOperationStripArea.current
	val onDrop = {
		performOutlinerDrop(dragController, currentRows.value, currentPuppet.value, editorSession, stripArea) { nodeId -> viewState.open(nodeId) }
	}

	Column(modifier = modifier.fillMaxSize().trackRowHoverPointer(hoverPreview, enabled = thumbnails != null)) {
		Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
			BoxWithConstraints(modifier = Modifier.fillMaxSize().zebraFill(listState, OUTLINER_ROW_HEIGHT, stripeColor)) {
				val viewportWidth = maxWidth
				val contentWidth =
					remember(rows, viewportWidth, density, typography, viewState.showSelectableColumn, viewState.showVisibilityColumn) {
						outlinerContentWidth(
							rows,
							textMeasurer,
							density,
							viewportWidth,
							typography.bodySmall,
							typography.labelSmall,
							viewState.showSelectableColumn,
							viewState.showVisibilityColumn,
						)
					}
				LazyColumn(
					state = listState,
					modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScroll),
				) {
					itemsIndexed(
						rows,
						key = { _, row -> row.node.id },
						contentType = { _, row -> row.node.icon },
					) { index, row ->
						// Read where it is used, at a click and while drawing: a row a fold only moved has a new
						// place and nothing else, and a place handed over as a value would compose it again.
						val currentIndex = rememberUpdatedState(index)
						OutlinerRowView(
							row = row,
							rowIndex = { currentIndex.value },
							rowWidth = contentWidth,
							selected = row.node.target != null && row.node.target in selection,
							ancestorOfSelection = row.node.id in ancestorParts,
							matched = searching && row.node.label.contains(trimmedQuery, ignoreCase = true),
							expanded = isOpen(row.node.id),
							renaming = row.node.id == renamingNodeId,
							showSelectableColumn = viewState.showSelectableColumn,
							showVisibilityColumn = viewState.showVisibilityColumn,
							labels = labels,
							viewState = viewState,
							session = editorSession,
							onSelect = { toggle, extend ->
								val target = row.node.target
								val handle = selectionHandle
								// An armed relation pick (a Properties eyedropper) claims the click.  It claims a
								// row it will NOT accept too - selecting would record an undo step and swap the
								// Properties panel, and the arming field with it, out from under the pick.
								val pickOutcome = target?.let { pressed -> relationPick.click(pressed) } ?: PickClickOutcome.Ignored

								// A resolved or swallowed pick leaves the selection as it was, so there is no change
								// for the reveal to stand down from.
								if (pickOutcome == PickClickOutcome.Ignored && target != null && handle != null) {
									// Selectability gates only viewport picking; the outliner always selects.
									val clicked = selectionAfterClick(currentRows.value, handle.selection, currentIndex.value, target, toggle, extend)
									revealStandDown.clicked(clicked.active)
									handle.set(clicked)
								}
							},
							onStartRename = { renamingNodeId = row.node.id },
							onRenameEnd = { renamingNodeId = null },
							dragController = dragController,
							onDrop = onDrop,
							hoverPreviewsEnabled = thumbnails != null,
							hoverPreview = hoverPreview,
						)
					}
				}
			}
			VerticalScrollbarOverlay(listState)
		}
		// One art preview for the whole space, beside the rested-on row, or at the pointer when the row has
		// no room beside it.  Gated on a provider being
		// present and the entity actually having art (untextured drawables / art-less parts pop nothing): a
		// drawable shows its own crop, a part shows the combined preview of every art mesh under it.
		val preview = hoverPreview.shown
		val previewBitmap =
			preview?.let { shown ->
				when (val target = shown.key) {
					is SelectionTarget.Drawable -> thumbnails?.thumbnailFor(target.id)
					is SelectionTarget.Part -> thumbnails?.partThumbnailFor(target.id)
					is SelectionTarget.Deformer -> null
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

		// A name chip follows the cursor while dragging, so there is something clearly "in hand".  The space
		// reads which row is in hand, which a drag changes as it starts and as it ends; where the pointer
		// is, the chip asks for itself.
		val draggingLabel =
			dragController.draggingKey?.let { id -> rows.firstOrNull { row -> row.node.id == id }?.node?.label }

		if (draggingLabel != null) {
			RowDragLabel(label = draggingLabel, pointerInWindow = { dragController.pointerInWindow })
		}
	}
}

/**
 * Reveals [target]'s row: opens every closed ancestor of it in the tree, then brings the row into view.  The one
 * body both reveal effects share, so the two entry points cannot drift into revealing a row differently.
 * Every read here runs in the effect's coroutine, never under composition.
 *
 * @param SelectionTarget target The entity whose row to reveal.
 * @param OutlinerNode filteredTree The tree as filtered, whose rows the list shows.
 * @param OutlinerViewState viewState The area's view state, which opens each ancestor that is closed.
 * @param Function isOpen Reports whether a node id is open, after the ancestors are.
 * @param LazyListState listState The list to scroll.
 */
private suspend fun revealOutlinerTarget(
	target: SelectionTarget,
	filteredTree: OutlinerNode,
	viewState: OutlinerViewState,
	isOpen: (String) -> Boolean,
	listState: LazyListState,
) {
	val path = pathTo(filteredTree, target) ?: return
	path.dropLast(1).forEach { ancestorId -> viewState.open(ancestorId) }
	val index = flattenOutliner(filteredTree, isOpen).indexOfFirst { row -> row.node.id == path.last() }

	if (index >= 0) {
		listState.animateScrollToItem(index)
	}
}