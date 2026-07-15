package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorSession
import org.umamo.edit.RowDropBand
import org.umamo.edit.Selection
import org.umamo.edit.SelectionOps
import org.umamo.edit.SelectionTarget
import org.umamo.edit.applyOutlinerDrop
import org.umamo.edit.deleteTarget
import org.umamo.edit.outlinerDropBandFor
import org.umamo.edit.rename
import org.umamo.edit.resolveOutlinerDrop
import org.umamo.edit.toggleSelectable
import org.umamo.edit.toggleSelectableSubtree
import org.umamo.edit.toggleVisibility
import org.umamo.edit.toggleVisibilitySubtree
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.ContextMenuArea
import org.umamo.ui.kit.InlineRenameField
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.model.LocalDrawableThumbnails
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.drawIcon
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.LocalRowDragCancel

/** Per-depth indentation, matching the Parameters space's folder indent. */
private val INDENT_PER_DEPTH = 12.dp

/** Fixed width of the disclosure-chevron slot (kept in sync with the math for the click region). */
private val CHEVRON_WIDTH = 14.dp

/** Fixed width of the type-icon slot. */
private val ICON_WIDTH = 16.dp

/** Row height, shared by every outliner row. */
private val ROW_HEIGHT = 22.dp

/** Fixed width of the trailing restriction indicator slot. */
private val RESTRICTION_SLOT_WIDTH = 16.dp

/** Max gap between two presses on a row that opens its inline rename editor (a double-click). */
private const val OUTLINER_DOUBLE_CLICK_MILLIS = 300L

/** Pause the pointer must rest on a drawable row before its art preview pops, so a sweep does not flicker. */
private const val OUTLINER_HOVER_DELAY_MILLIS = 10L

/** One outliner node paired with its tree depth, the unit a [LazyColumn] item renders. */
private data class FlatRow(val node: OutlinerNode, val depth: Int)

/**
 * A pending art hover preview: which row entity is hovered (a drawable's art mesh or a part's combined
 * art), its display name, and the hovered row's window bounds the popup anchors beside.
 *
 * @property SelectionTarget target    The hovered drawable or part.
 * @property String          name      The entity's display name (shown under the thumbnail).
 * @property Rect            rowBounds The hovered row's bounds in window pixels.
 */
private data class HoverPreview(val target: SelectionTarget, val name: String, val rowBounds: Rect)

/**
 * A plain, non-snapshot holder for a row's latest layout coordinates. Writing it from onGloballyPositioned
 * does not invalidate the composition (unlike Compose state), so the per-frame layout callbacks during a
 * scroll cost nothing; the hover effect reads the live window bounds from it only when it needs to anchor a
 * preview.
 */
private class OutlinerRowBoundsHolder {
	/** The row's most recent layout coordinates, or null before the first layout pass. */
	var coordinates: LayoutCoordinates? = null
}

/**
 * The outliner space: the unified Blender-style tree that folds Cubism's split Part and Deformer panels
 * into one. A single puppet root holds the Armature deformer hierarchy followed by the parts, each part
 * listing its drawables then its sub-parts. Reads the open document's puppet from [LocalPuppet] (empty
 * state when nothing is loaded). Branches start collapsed (only the root is open); rows dim when hidden
 * or sketch and select through [LocalSelection] (plain click replaces, Ctrl / Cmd toggles, Shift adds).
 * A selection made elsewhere (a viewport pick) reveals its row - ancestors expand and the list scrolls
 * to it - but a click inside the outliner never scrolls the list. The AREA header carries the name
 * search and the filter dropdown (OutlinerHeaderControls, sharing this body's OutlinerViewState
 * through [scope]); names never wrap, so a horizontal scroll reaches long ones.
 *
 * アウトライナー空間。Cubism の分割パネルを1つの木に統合する。既定は折りたたみ、横スクロール対応、
 * 検索と絞り込みはエリアヘッダ側。
 *
 * @param AreaScope scope The hosting area's scope carrying the shared view state.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun OutlinerSpace(scope: AreaScope, modifier: Modifier = Modifier) {
	val stripeColor = LocalUmamoColors.current.rowStripe
	val puppet = LocalPuppet.current
	if (puppet == null) {
		Box(modifier = modifier.fillMaxSize().zebraFill(rememberLazyListState(), ROW_HEIGHT, stripeColor))
		return
	}
	val selectionHandle = LocalSelection.current
	val selection = selectionHandle?.selection ?: Selection()
	// The open document's session drives the per-row edits (eye toggle, inline rename); null when no
	// document is open, in which case those affordances no-op.
	val editorSession = LocalEditorSession.current
	// The node id whose label is being renamed in place (double-click opens it), or null when none is.
	// Not keyed on the puppet (see the expand-state note): a rename commit changes the model, which must
	// not yank the editor's own id state out from under it mid-edit.
	var renamingNodeId by remember { mutableStateOf<String?>(null) }
	// Drag-and-drop state: long-press a row to pick it up, drop onto another to reparent. Transient, so
	// remembered per outliner instance (never keyed on the puppet).
	val dragController = remember { RowDragController<SelectionTarget>() }
	// While a drag is in flight, park its cancel with the shell (via the shared seam) so Escape aborts the
	// drag instead of falling through to the shell's clear-selection branch. isDragging is snapshot state,
	// so this effect re-keys on drag start/end; onDispose also covers the space closing mid-drag.
	val dragCancelSeam = LocalRowDragCancel.current
	DisposableEffect(dragController.isDragging) {
		if (dragController.isDragging) {
			dragCancelSeam.cancel = { dragController.cancel() }
		}
		onDispose {
			dragCancelSeam.cancel = null
		}
	}
	val rootLabel = stringResource(Res.string.outliner_root)
	val armatureLabel = stringResource(Res.string.outliner_armature)
	val tree = remember(puppet, rootLabel, armatureLabel) { buildOutlinerTree(puppet, rootLabel, armatureLabel) }
	// Expand state keyed by stable node id. Default collapsed - only the root opens (absent = collapsed,
	// except the root). Persisted for this space instance, NOT keyed on the puppet: the model changes
	// identity on every edit / undo, so keying on it would wipe the open branches on each rename or
	// visibility toggle; the host remounts this space (key(document)) when the document actually changes.
	val expanded = remember { mutableStateMapOf<String, Boolean>() }
	// Search / filter state shared with the area-header controls (OutlinerHeaderControls) through the
	// hosting AreaScope - the header slot is a sibling subtree, so a body-local remember cannot reach it.
	val viewState = scope.spaceState(OUTLINER_VIEW_STATE_KEY) { OutlinerViewState() }
	val query = viewState.query
	// Set when a click inside the outliner changes the selection, so the reveal effect can skip the scroll.
	var suppressReveal by remember { mutableStateOf(false) }
	val filteredTree =
		remember(tree, query, viewState.showParts, viewState.showDrawables, viewState.showDeformers) {
			filterOutliner(tree, query, viewState.showParts, viewState.showDrawables, viewState.showDeformers)
		}
	// During an active search every branch opens so matches are not hidden behind the collapsed default.
	val searching = query.isNotBlank()
	val trimmedQuery = query.trim()
	val isOpen: (String) -> Boolean = { id -> searching || (expanded[id] ?: (id == OUTLINER_ROOT_ID)) }
	// Memoise the visible rows on what actually changes them, so the width measurement below is stable
	// across recompositions (and unaffected by vertical scrolling).
	val rows = remember(filteredTree, expanded.toMap(), searching) { flattenOutliner(filteredTree, isOpen) }
	val listState = rememberLazyListState()
	val horizontalScroll = rememberScrollState()
	val density = LocalDensity.current
	val textMeasurer = rememberTextMeasurer()
	val typography = LocalUmamoTypography.current
	// Part folders that contain (anywhere below) a selected node, so a collapsed parent still signals the
	// selection lives inside it. Keyed by node id; computed from each selected target's path to the root.
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
	// disables the preview). [hoveredPreview] tracks the drawable under the pointer the instant it is
	// hovered; [shownPreview] lags by [OUTLINER_HOVER_DELAY_MILLIS] so a quick sweep down the list never
	// pops a popup. The delay re-arms whenever the hovered row changes (the effect re-keys), so only a
	// rested pointer surfaces a preview.
	val thumbnails = LocalDrawableThumbnails.current
	var hoveredPreview by remember { mutableStateOf<HoverPreview?>(null) }
	var shownPreview by remember { mutableStateOf<HoverPreview?>(null) }
	LaunchedEffect(hoveredPreview) {
		val pending = hoveredPreview
		if (pending == null) {
			shownPreview = null
		} else {
			delay(OUTLINER_HOVER_DELAY_MILLIS)
			shownPreview = pending
		}
	}

	// Reveal-on-select: a selection from elsewhere (a viewport pick) opens the target's ancestors and
	// scrolls to it; a selection made by clicking inside the outliner suppresses the scroll so the list
	// does not jump under the user's cursor.
	LaunchedEffect(selection.active) {
		val wasLocalClick = suppressReveal
		suppressReveal = false
		if (wasLocalClick) {
			return@LaunchedEffect
		}
		val active = selection.active ?: return@LaunchedEffect
		val path = pathTo(filteredTree, active) ?: return@LaunchedEffect
		path.dropLast(1).forEach { ancestorId -> expanded[ancestorId] = true }
		val index = flattenOutliner(filteredTree, isOpen).indexOfFirst { row -> row.node.id == path.last() }
		if (index >= 0) {
			listState.animateScrollToItem(index)
		}
	}

	Column(modifier = modifier.fillMaxSize()) {
		Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
			BoxWithConstraints(modifier = Modifier.fillMaxSize().zebraFill(listState, ROW_HEIGHT, stripeColor)) {
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
						OutlinerRowView(
							row = row,
							rowIndex = index,
							rowWidth = contentWidth,
							selected = row.node.target != null && row.node.target in selection,
							ancestorOfSelection = row.node.id in ancestorParts,
							matched = searching && row.node.label.contains(trimmedQuery, ignoreCase = true),
							expanded = isOpen(row.node.id),
							onToggle = {
								expanded[row.node.id] = !(expanded[row.node.id] ?: (row.node.id == OUTLINER_ROOT_ID))
							},
							onSelect = { toggle, extend ->
								val target = row.node.target
								val handle = selectionHandle
								// Selectability gates only viewport picking; the outliner always selects.
								if (target != null && handle != null) {
									suppressReveal = true
									handle.set(
										selectionAfterClick(
											rows,
											handle.selection,
											index,
											target,
											toggle,
											extend,
										),
									)
								}
							},
							renaming = row.node.id == renamingNodeId,
							onStartRename = {
								// Only real rows rename; the editor needs a session to commit through.
								if (row.node.target != null && editorSession != null) {
									renamingNodeId = row.node.id
								}
							},
							onCommitRename = { newName ->
								row.node.target?.let { target -> editorSession?.rename(target, newName) }
								renamingNodeId = null
							},
							onCancelRename = { renamingNodeId = null },
							onToggleVisibility = { shiftHeld ->
								row.node.target?.let { target ->
									// Blender parity: Shift sets the clicked node and its whole subtree to one uniform value.
									if (shiftHeld) {
										editorSession?.toggleVisibilitySubtree(target)
									} else {
										editorSession?.toggleVisibility(target)
									}
								}
							},
							onToggleSelectable = { shiftHeld ->
								row.node.target?.let { target ->
									if (shiftHeld) {
										editorSession?.toggleSelectableSubtree(target)
									} else {
										editorSession?.toggleSelectable(target)
									}
								}
							},
							showSelectableColumn = viewState.showSelectableColumn,
							showVisibilityColumn = viewState.showVisibilityColumn,
							onRequestDelete = { cascade ->
								// No confirmation: history / undo makes an accidental delete cheap to recover, and the
								// rows are hard to hit by accident - so this applies immediately (the chosen behaviour).
								row.node.target?.let { target -> editorSession?.deleteTarget(target, cascade) }
							},
							dragController = dragController,
							onDrop = {
								performOutlinerDrop(
									dragController,
									rows,
									puppet,
									editorSession,
								) { nodeId -> expanded[nodeId] = true }
							},
							hoverPreviewsEnabled = thumbnails != null,
							onHoverPreview = { target, preview ->
								if (preview != null) {
									hoveredPreview = preview
								} else if (hoveredPreview?.target == target) {
									// Only the row that owns the current preview may clear it, so moving onto the next
									// row (which sets its own preview first) is not undone by the old row's exit.
									hoveredPreview = null
								}
							},
						)
					}
				}
			}
			VerticalScrollbarOverlay(listState)
		}
		// One art preview for the whole space, anchored beside the rested-on row. Gated on a provider being
		// present and the entity actually having art (untextured drawables / art-less parts pop nothing): a
		// drawable shows its own crop, a part shows the combined preview of every art mesh under it.
		val preview = shownPreview
		val previewBitmap =
			preview?.let { pending ->
				when (val target = pending.target) {
					is SelectionTarget.Drawable -> thumbnails?.thumbnailFor(target.id)
					is SelectionTarget.Part -> thumbnails?.partThumbnailFor(target.id)
					is SelectionTarget.Deformer -> null
				}
			}
		if (preview != null && previewBitmap != null) {
			OutlinerThumbnailPreview(name = preview.name, thumbnail = previewBitmap, anchorRect = preview.rowBounds)
		}
		// A name chip follows the cursor while dragging, so there is something clearly "in hand".
		val draggingLabel =
			dragController.draggingKey?.let { id -> rows.firstOrNull { it.node.id == id }?.node?.label }
		if (dragController.isDragging && draggingLabel != null) {
			OutlinerDragLabel(
				label = draggingLabel,
				cursorX = dragController.dragWindowX,
				cursorY = dragController.dragWindowY,
			)
		}
	}
}

/**
 * Applies the drop at the end of a drag: reads the drag state, resolves the move through :edit's
 * [resolveOutlinerDrop] (the same band rules the row indicator drew, so what the user saw is what
 * happens), dispatches it as one undo step, and expands the destination on a nest-inside drop so the
 * user sees the moved item land.  Drops with no valid target, onto itself, or across the org /
 * armature boundary do nothing; cycles are refused inside the move.
 *
 * @param RowDragController<SelectionTarget> controller The drag state (read for the dragged + target + band, then ended).
 * @param List rows The visible rows, to resolve a node id to its selection target.
 * @param PuppetModel puppet The model, to resolve the target's parent and siblings.
 * @param EditorSession? session The session the move is dispatched through (null = no document, no-op).
 * @param Function expand Opens the given node id (the destination is expanded on a nest-inside drop so the
 *   user sees the moved item land, rather than fearing it vanished into a collapsed branch).
 */
private fun performOutlinerDrop(
	controller: RowDragController<SelectionTarget>,
	rows: List<FlatRow>,
	puppet: PuppetModel,
	session: EditorSession?,
	expand: (String) -> Unit,
) {
	val targetId = controller.dropTargetKey
	val fraction = controller.dropTargetFraction ?: 0.5f
	val dragged = controller.draggedPayload
	controller.end()
	if (targetId == null || session == null || dragged == null) {
		return
	}
	val targetNode = rows.firstOrNull { it.node.id == targetId }?.node ?: return
	val target = targetNode.target ?: return
	val drop = puppet.resolveOutlinerDrop(dragged, target, fraction) ?: return
	session.applyOutlinerDrop(drop)
	if (drop.expandTarget) {
		expand(targetNode.id)
	}
}

/**
 * One tree row with its context menu attached. A right-click (desktop) or long-press (touch) on a real
 * row opens [outlinerRowMenuItems]; the synthetic root rows (no target) have no menu and pass straight
 * through to the bare [OutlinerRowBody]. The menu reuses the very callbacks the eye / pointer / rename
 * affordances already drive, plus [onRequestDelete] for the destructive entries, so a menu action and its
 * inline affordance always do the same thing.
 *
 * @param FlatRow row The node and its depth.
 * @param Int rowIndex The visible-row index, for the ancestry-guide dash phase.
 * @param Dp rowWidth The fixed width shared by every row.
 * @param Boolean selected Whether the node is in the current selection.
 * @param Boolean ancestorOfSelection Whether this part folder contains the selection.
 * @param Boolean matched Whether the node matches the active search.
 * @param Boolean expanded Whether the node is expanded.
 * @param Function onToggle Flips this node's expand state.
 * @param Function onSelect Applies a selection gesture for a real node.
 * @param Boolean renaming Whether this row's label is the inline rename editor.
 * @param Function onStartRename Opens this row's inline rename.
 * @param Function onCommitRename Commits the inline rename.
 * @param Function onCancelRename Abandons the inline rename.
 * @param Function onToggleVisibility Flips this row's entity visibility; with Shift held, its whole subtree.
 * @param Function onToggleSelectable Flips this row's entity selectability; with Shift held, its whole subtree.
 * @param Boolean showSelectableColumn Whether the pointer restriction column renders on the rows.
 * @param Boolean showVisibilityColumn Whether the eye restriction column renders on the rows.
 * @param Function onRequestDelete Requests a delete of this row's entity (true = cascade, false = ungroup).
 * @param RowDragController<SelectionTarget> dragController Shared drag state.
 * @param Function onDrop Applies the move when a drag started on this row ends.
 * @param Boolean hoverPreviewsEnabled Whether a hovered drawable / part row reports an art preview.
 * @param Function onHoverPreview Reports this row's hover preview (or null to clear).
 */
@Composable
private fun OutlinerRowView(
	row: FlatRow,
	rowIndex: Int,
	rowWidth: Dp,
	selected: Boolean,
	ancestorOfSelection: Boolean,
	matched: Boolean,
	expanded: Boolean,
	onToggle: () -> Unit,
	onSelect: (toggle: Boolean, extend: Boolean) -> Unit,
	renaming: Boolean,
	onStartRename: () -> Unit,
	onCommitRename: (String) -> Unit,
	onCancelRename: () -> Unit,
	onToggleVisibility: (shiftHeld: Boolean) -> Unit,
	onToggleSelectable: (shiftHeld: Boolean) -> Unit,
	showSelectableColumn: Boolean,
	showVisibilityColumn: Boolean,
	onRequestDelete: (cascade: Boolean) -> Unit,
	dragController: RowDragController<SelectionTarget>,
	onDrop: () -> Unit,
	hoverPreviewsEnabled: Boolean,
	onHoverPreview: (SelectionTarget, HoverPreview?) -> Unit,
) {
	val body =
		@Composable {
			OutlinerRowBody(
				row = row,
				rowIndex = rowIndex,
				rowWidth = rowWidth,
				selected = selected,
				ancestorOfSelection = ancestorOfSelection,
				matched = matched,
				expanded = expanded,
				onToggle = onToggle,
				onSelect = onSelect,
				renaming = renaming,
				onStartRename = onStartRename,
				onCommitRename = onCommitRename,
				onCancelRename = onCancelRename,
				onToggleVisibility = onToggleVisibility,
				onToggleSelectable = onToggleSelectable,
				showSelectableColumn = showSelectableColumn,
				showVisibilityColumn = showVisibilityColumn,
				dragController = dragController,
				onDrop = onDrop,
				hoverPreviewsEnabled = hoverPreviewsEnabled,
				onHoverPreview = onHoverPreview,
			)
		}
	val target = row.node.target
	if (target == null) {
		body()
	} else {
		ContextMenuArea(
			items =
				outlinerRowMenuItems(
					target,
					// A menu action has no Shift context, so it is always the plain single-entity toggle.
					{ onToggleVisibility(false) },
					{ onToggleSelectable(false) },
					onStartRename,
					onRequestDelete,
				),
			content = body,
		)
	}
}

/**
 * Builds the context-menu entries for a real outliner row, by entity kind: parts and drawables get a
 * visibility toggle (deformers have none); every kind gets selectability, rename, and delete. A part's
 * "Delete" cascades (removes the subtree) and a second "Delete Group Only" ungroups (keeps the
 * contents); a drawable or deformer has the single delete. Selectability gates only viewport picking,
 * so every entry stays enabled on an unselectable row.
 *
 * @param SelectionTarget target The row's entity.
 * @param Function onToggleVisibility Flips the entity's visibility.
 * @param Function onToggleSelectable Flips the entity's selectability.
 * @param Function onStartRename Opens the inline rename.
 * @param Function onRequestDelete Requests a delete (true = cascade / the sole delete, false = ungroup).
 * @return List The menu entries.
 */
@Composable
private fun outlinerRowMenuItems(
	target: SelectionTarget,
	onToggleVisibility: () -> Unit,
	onToggleSelectable: () -> Unit,
	onStartRename: () -> Unit,
	onRequestDelete: (cascade: Boolean) -> Unit,
): List<MenuItem> =
	buildList {
		// Select Hierarchy replaces the selection with this row's whole subtree (dispatched through the
		// registry with the row's target as the argument, so the palette and a future binding share it).
		val commands = LocalCommands.current
		add(
			MenuItem.Action(
				stringResource(Res.string.outliner_menu_select_hierarchy),
				onSelect = { commands.invoke("outliner.selectHierarchy", target) },
			),
		)
		add(MenuItem.Separator)
		if (target is SelectionTarget.Part || target is SelectionTarget.Drawable) {
			add(MenuItem.Action(stringResource(Res.string.outliner_menu_visibility), onSelect = onToggleVisibility))
		}
		add(MenuItem.Action(stringResource(Res.string.outliner_menu_selectable), onSelect = onToggleSelectable))
		add(MenuItem.Action(stringResource(Res.string.outliner_menu_rename), onSelect = onStartRename))
		add(MenuItem.Separator)
		// "Delete" removes just this item (a part keeps its contents, which rise to the parent); "Delete
		// Hierarchy" removes a part's whole subtree. A drawable / deformer has no hierarchy, so just "Delete".
		add(MenuItem.Action(stringResource(Res.string.outliner_menu_delete), onSelect = { onRequestDelete(false) }))
		if (target is SelectionTarget.Part) {
			add(
				MenuItem.Action(
					stringResource(Res.string.outliner_menu_delete_hierarchy),
					onSelect = { onRequestDelete(true) },
				),
			)
		}
	}

/**
 * Renders one tree row: indent, a disclosure chevron (only when the node has children), a placeholder
 * type icon, the single-line label (or its inline rename editor while [renaming]), and - for parts /
 * drawables - a clickable visibility eye pinned to the right. Every row is fixed to [rowWidth] so the
 * selection / hover / search-match backgrounds span the full width and the horizontal scroll range stays
 * put; a name longer than the viewport grows [rowWidth] and is reached by scrolling rather than wrapping.
 * A single click selects a real node or toggles a synthetic one; a double-click on a real node opens its
 * inline rename ([onStartRename]); the chevron and the eye consume their own press (only toggling
 * expansion / visibility). A part folder whose subtree holds the selection is tinted
 * ([ancestorOfSelection]); a search hit is tinted too, so found rows stand out along the path the filter
 * kept.
 *
 * @param FlatRow row The node and its depth.
 * @param Int rowIndex The visible-row index, for the ancestry-guide dash phase.
 * @param Dp rowWidth The fixed width shared by every row (the wider of the viewport and the longest row).
 * @param Boolean selected Whether the node is in the current selection.
 * @param Boolean ancestorOfSelection Whether this part folder contains the selection (tinted to signal it).
 * @param Boolean matched Whether the node's name matches the active search (tinted to stand out).
 * @param Boolean expanded Whether the node is expanded.
 * @param Function onToggle Flips this node's expand state.
 * @param Function onSelect Applies a selection gesture (toggle / extend modifiers) for a real node.
 * @param Boolean renaming Whether this row's label is currently the inline rename editor.
 * @param Function onStartRename Opens this row's inline rename (a double-click on a real node).
 * @param Function onCommitRename Commits the inline rename with the new name.
 * @param Function onCancelRename Abandons the inline rename.
 * @param Function onToggleVisibility Flips this row's entity visibility (the eye click); Shift = subtree.
 * @param Function onToggleSelectable Flips this row's entity selectability (the pointer click); Shift = subtree.
 * @param Boolean showSelectableColumn Whether the pointer restriction column renders (the filter dropdown's toggle).
 * @param Boolean showVisibilityColumn Whether the eye restriction column renders (the filter dropdown's toggle).
 * @param RowDragController<SelectionTarget> dragController Shared drag state; the row reports its bounds and gestures here.
 * @param Function onDrop Applies the move when a drag started on this row ends.
 * @param Boolean hoverPreviewsEnabled Whether a hovered drawable / part row should report an art preview.
 * @param Function onHoverPreview Reports this drawable / part row's hover (a preview to show, or null to clear).
 */
@Composable
private fun OutlinerRowBody(
	row: FlatRow,
	rowIndex: Int,
	rowWidth: Dp,
	selected: Boolean,
	ancestorOfSelection: Boolean,
	matched: Boolean,
	expanded: Boolean,
	onToggle: () -> Unit,
	onSelect: (toggle: Boolean, extend: Boolean) -> Unit,
	renaming: Boolean,
	onStartRename: () -> Unit,
	onCommitRename: (String) -> Unit,
	onCancelRename: () -> Unit,
	onToggleVisibility: (shiftHeld: Boolean) -> Unit,
	onToggleSelectable: (shiftHeld: Boolean) -> Unit,
	showSelectableColumn: Boolean,
	showVisibilityColumn: Boolean,
	dragController: RowDragController<SelectionTarget>,
	onDrop: () -> Unit,
	hoverPreviewsEnabled: Boolean,
	onHoverPreview: (SelectionTarget, HoverPreview?) -> Unit,
) {
	val node = row.node
	// The selection gesture runs in a long-lived pointerInput coroutine that only re-captures its closures
	// when its keys (node.target, renaming) change. The lambdas below close over live state, so without
	// rememberUpdatedState the loop would keep calling a stale lambda after the model changes. These keep
	// the loop pointed at the latest callbacks.
	val currentOnToggle by rememberUpdatedState(onToggle)
	val currentOnSelect by rememberUpdatedState(onSelect)
	val currentOnStartRename by rememberUpdatedState(onStartRename)
	val currentOnDrop by rememberUpdatedState(onDrop)
	// Drag feedback: this row is the one being dragged (faded), or the row the pointer is over (a drop
	// target). Only real rows are valid drop targets.
	val isDragged = dragController.draggingKey == node.id
	val isDropTarget =
		dragController.isDragging && !isDragged && node.target != null && dragController.dropTargetKey == node.id
	// The drop band for THIS row, resolved by the same band rules the dispatch uses (so the indicator always
	// matches the move): an insertion line above (before) or below (after), or a whole-row fill (nest into).
	val draggedPayload = dragController.draggedPayload
	val dropBand =
		// isDropTarget already includes node.target != null, so it smart-casts non-null inside this branch.
		if (isDropTarget && draggedPayload != null) {
			outlinerDropBandFor(draggedPayload, node.target, dragController.dropTargetFraction ?: 0.5f)
		} else {
			null
		}
	val dropAbove = dropBand == RowDropBand.Before
	val dropBelow = dropBand == RowDropBand.After
	val isIntoTarget = dropBand == RowDropBand.Into
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	// Plain (non-snapshot) holder for the row's layout coordinates so onGloballyPositioned never forces a
	// recompose; the hover effect reads the live window bounds from it on demand to anchor the preview.
	val boundsHolder = remember { OutlinerRowBoundsHolder() }
	// Report this row's hover so the space can pop (after its rest delay) an art preview beside it: a
	// drawable previews its own art mesh, a part the combined art of everything under it; deformers have no
	// art. Unconditional call site (it branches inside) so the composition structure is stable across recompose.
	LaunchedEffect(hovered, hoverPreviewsEnabled, node.target) {
		val target = node.target
		val previewable = target is SelectionTarget.Drawable || target is SelectionTarget.Part
		if (!hoverPreviewsEnabled || target == null || !previewable) {
			return@LaunchedEffect
		}
		if (hovered) {
			val bounds = boundsHolder.coordinates?.boundsInWindow() ?: return@LaunchedEffect
			onHoverPreview(target, HoverPreview(target, node.label, bounds))
		} else {
			onHoverPreview(target, null)
		}
	}
	// Drop this row's drag-hit-test bounds when it scrolls off, so a drop never targets an off-screen row.
	DisposableEffect(node.id) {
		onDispose { dragController.clearBounds(node.id) }
	}
	val background =
		when {
			// A "nest inside" drop fills the row; before / after instead draw an edge line (below).
			isIntoTarget -> colors.dropTargetBackground
			selected -> colors.selection
			hovered -> colors.rowHover
			ancestorOfSelection -> colors.selectionAncestorBackground
			matched -> colors.searchMatchBackground
			else -> Color.Transparent
		}
	val borderColor =
		when {
			isIntoTarget -> colors.accent
			selected -> colors.selection
			hovered -> colors.rowHover
			ancestorOfSelection -> colors.accent
			matched -> colors.accent
			else -> Color.Transparent
		}
	val labelColor =
		when {
			selected -> colors.selectionText
			node.dimmed -> colors.textMuted
			else -> colors.text
		}
	// Subtle dashed ancestry guides: muted so they stay subordinate to the labels in both themes.
	val guideLineColor = colors.treeGuideLine
	val hasChildren = node.children.isNotEmpty()
	val showEye = node.target is SelectionTarget.Part || node.target is SelectionTarget.Drawable
	Row(
		modifier =
			Modifier.width(rowWidth)
				.height(ROW_HEIGHT)
				// Background and border paint on the full row, before padding insets the content - otherwise the
				// selection / hover band is drawn inside the 2dp vertical padding, so the highlighted rows read
				// as having extra vertical padding while the plain rows (no visible band) do not.
				.background(background, shape = shapes.medium)
				.border(BorderStroke(1.dp, borderColor), shapes.medium)
				// Dashed vertical guide lines, one per ancestor indent column, so deep branches line up
				// visually (Blender's outliner ancestry lines). Painted over the zebra fill but behind the
				// content; depth 0 (the root) draws none. The dash phase is offset by the row's stacked height
				// so the dashes stay continuous from one row to the next instead of resetting every row.
				.drawBehind {
					if (row.depth == 0) {
						return@drawBehind
					}
					val leftEdgePx =
						4.dp.toPx() + 4.dp.toPx() // the row's horizontal padding plus the Spacer's base inset
					val indentPx = INDENT_PER_DEPTH.toPx()
					val chevronHalfPx = CHEVRON_WIDTH.toPx() / 2f
					val dashOnPx = 2.dp.toPx()
					val dashOffPx = 3.dp.toPx()
					val dashPeriodPx = dashOnPx + dashOffPx
					val dashEffect =
						PathEffect.dashPathEffect(
							floatArrayOf(dashOnPx, dashOffPx),
							(rowIndex * size.height) % dashPeriodPx,
						)
					var ancestorLevel = 0
					while (ancestorLevel < row.depth) {
						val lineX = leftEdgePx + indentPx * ancestorLevel + chevronHalfPx
						drawLine(
							color = guideLineColor,
							start = Offset(lineX, 0f),
							end = Offset(lineX, size.height),
							strokeWidth = 1.dp.toPx(),
							pathEffect = dashEffect,
						)
						ancestorLevel += 1
					}
				}
				// The drop insertion line: above for a "before" drop, below for "after"; an "into" drop (a part's
				// middle band) shows the whole-row fill instead. The line starts at this row's indent level, so
				// it visually reads as landing at the same nesting as the target (the dropped sibling's level).
				.drawBehind {
					// Inset the line by half its stroke so the full 2.5dp stays inside the row - drawing it at the
					// very edge (y == 0) clips the top half against the row bound, which made the "before" line on
					// the first row nearly invisible.
					val strokeWidth = 2.5.dp.toPx()
					val edgeY =
						when {
							dropAbove -> strokeWidth / 2f
							dropBelow -> size.height - strokeWidth / 2f
							else -> return@drawBehind
						}
					val startX = 4.dp.toPx() + 4.dp.toPx() + INDENT_PER_DEPTH.toPx() * row.depth
					drawLine(
						color = colors.accent,
						start = Offset(startX, edgeY),
						end = Offset(size.width, edgeY),
						strokeWidth = strokeWidth,
					)
				}
				.hoverable(interaction)
				// The dragged row fades while it is in flight.
				.alpha(if (isDragged) 0.4f else 1f)
				.onGloballyPositioned { coordinates ->
					boundsHolder.coordinates = coordinates
					// Publish the window bounds so a drag can hit-test the drop target against every visible row.
					dragController.reportBounds(node.id, coordinates.boundsInWindow())
				}
				// Long-press then drag to reparent: the gesture is distinct from the tap-to-select loop below, so
				// a press still selects first, then a hold begins the drag. Only real rows pick up. The
				// drop is applied on release by the space (which reads the controller's target).
				.pointerInput(node.target) {
					detectDragGesturesAfterLongPress(
						onDragStart = { offset ->
							val target = node.target
							if (target != null) {
								// Seed the drag position with the press point so the initial target is this row (i.e.
								// none) - never a stale target left over from the previous drag.
								val rowBounds = boundsHolder.coordinates?.boundsInWindow()
								val rowLeft = rowBounds?.left ?: 0f
								val rowTop = rowBounds?.top ?: 0f
								dragController.start(node.id, target, rowLeft + offset.x, rowTop + offset.y)
							}
						},
						onDrag = { change, _ ->
							val rowBounds = boundsHolder.coordinates?.boundsInWindow()
							val rowLeft = rowBounds?.left ?: 0f
							val rowTop = rowBounds?.top ?: 0f
							dragController.drag(rowLeft + change.position.x, rowTop + change.position.y)
						},
						onDragEnd = { currentOnDrop() },
						onDragCancel = { dragController.end() },
					)
				}
				// Selection covers the whole row (indent, icon, label, blank space) so clicking anywhere but the
				// chevron or eye selects; those consume their own press, which this skips via the consumed check.
				// A second unmodified press within the double-click window opens the inline rename instead of
				// re-selecting. While renaming the editor owns the row, so this skips its own handling.
				.pointerInput(node.target, renaming) {
					awaitPointerEventScope {
						var lastPressUptime = 0L
						while (true) {
							val event = awaitPointerEvent()
							// A secondary (right) press is the context-menu gesture, not a selection - let it fall
							// through to the wrapping ContextMenuArea instead of selecting the row.
							if (renaming || event.type != PointerEventType.Press || event.buttons.isSecondaryPressed || event.changes.any { it.isConsumed }) {
								continue
							}
							if (node.target == null) {
								currentOnToggle()
								continue
							}
							val toggle = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
							val extend = event.keyboardModifiers.isShiftPressed
							val pressUptime = event.changes.first().uptimeMillis
							if (!toggle && !extend && pressUptime - lastPressUptime <= OUTLINER_DOUBLE_CLICK_MILLIS) {
								currentOnStartRename()
								lastPressUptime = 0L
							} else {
								currentOnSelect(toggle, extend)
								lastPressUptime = pressUptime
							}
						}
					}
				}
				.padding(horizontal = 4.dp, vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Spacer(modifier = Modifier.width(4.dp + INDENT_PER_DEPTH * row.depth))
		ChevronSlot(visible = hasChildren, expanded = expanded, onToggle = onToggle, tint = colors.textMuted)
		OutlinerIconSlot(icon = node.icon, dimmed = node.dimmed, baseTint = colors.textMuted)
		Spacer(modifier = Modifier.width(4.dp))
		val labelStyle =
			if (node.target is SelectionTarget.Drawable) LocalUmamoTypography.current.labelSmall else LocalUmamoTypography.current.bodySmall
		Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
			if (renaming) {
				InlineRenameField(
					initialName = node.label,
					textStyle = labelStyle.copy(color = colors.text),
					cursorColor = colors.text,
					onCommit = onCommitRename,
					onCancel = onCancelRename,
					modifier = Modifier.fillMaxWidth(),
				)
			} else {
				Text(
					text = node.label,
					style = labelStyle,
					color = labelColor,
					maxLines = 1,
					overflow = TextOverflow.Clip,
				)
			}
		}
		// Selectability applies to any real entity (deformers included), so its column covers every real
		// row; the eye only parts / drawables. The filter dropdown's restriction toggles gate each column
		// wholesale - a hidden column composes nothing, so its slot vanishes and a click there selects the
		// row like any other blank space (outlinerContentWidth mirrors this same math).
		if (showSelectableColumn && node.target != null) {
			SelectableIndicator(
				selectable = node.selectable,
				tint = colors.textMuted,
				onToggle = onToggleSelectable,
			)
		}
		if (showVisibilityColumn && showEye) {
			VisibilityIndicator(
				hidden = node.dimmed,
				tint = colors.textMuted,
				onToggle = onToggleVisibility,
			)
		}
		if (node.target != null) {
			Spacer(modifier = Modifier.width(6.dp))
		}
	}
}

/**
 * The disclosure chevron slot - a fixed-width box drawing a right (collapsed) or down (expanded)
 * triangle, the whole slot toggling expansion. Always occupies its width even when [visible] is false,
 * so leaf and parent rows align.
 *
 * @param Boolean visible Whether to draw the chevron (false for a childless node).
 * @param Boolean expanded Whether the node is expanded.
 * @param Function onToggle Toggle callback.
 * @param Color tint The chevron colour.
 */
@Composable
private fun ChevronSlot(visible: Boolean, expanded: Boolean, onToggle: () -> Unit, tint: Color) {
	val base = Modifier.size(CHEVRON_WIDTH)
	// Consume the press so the row's whole-row selection handler skips it (the chevron only toggles).
	val slot =
		if (visible) {
			base.pointerInput(Unit) {
				awaitPointerEventScope {
					while (true) {
						val event = awaitPointerEvent()
						if (event.type == PointerEventType.Press) {
							event.changes.forEach { change -> change.consume() }
							onToggle()
						}
					}
				}
			}
		} else {
			base
		}
	Box(modifier = slot, contentAlignment = Alignment.Center) {
		if (visible) {
			Canvas(modifier = Modifier.size(9.dp)) {
				val chevron = Path()
				if (expanded) {
					chevron.moveTo(size.width * 0.15f, size.height * 0.35f)
					chevron.lineTo(size.width * 0.85f, size.height * 0.35f)
					chevron.lineTo(size.width * 0.50f, size.height * 0.72f)
				} else {
					chevron.moveTo(size.width * 0.35f, size.height * 0.15f)
					chevron.lineTo(size.width * 0.72f, size.height * 0.50f)
					chevron.lineTo(size.width * 0.35f, size.height * 0.85f)
				}
				chevron.close()
				drawPath(chevron, color = tint)
			}
		}
	}
}

/**
 * The type-icon slot: a fixed-width box drawing a simple placeholder glyph per [OutlinerIcon], distinct
 * enough to tell the kinds apart at a glance. Each node family carries a signature palette tint
 * ([UmamoColors.outlinerObjectTint] / [UmamoColors.outlinerDeformTint], after Blender's armature object /
 * data colours). This is the swap-in seam for real icon art - only this function changes when art lands,
 * never the layout.
 *
 * @param OutlinerIcon icon The icon kind.
 * @param Boolean dimmed Whether the row is muted (the glyph fades to match).
 * @param Color baseTint The default glyph colour for non-signature icons.
 */
@Composable
private fun OutlinerIconSlot(icon: OutlinerIcon, dimmed: Boolean, baseTint: Color) {
	val colors = LocalUmamoColors.current
	val tint =
		when (icon) {
			OutlinerIcon.PuppetRoot, OutlinerIcon.Part, OutlinerIcon.ArtMesh ->
				if (dimmed) colors.outlinerObjectTintDimmed else colors.outlinerObjectTint
			OutlinerIcon.Armature, OutlinerIcon.WarpDeformer, OutlinerIcon.RotationDeformer ->
				if (dimmed) colors.outlinerDeformTintDimmed else colors.outlinerDeformTint
		}
	Box(modifier = Modifier.size(ICON_WIDTH), contentAlignment = Alignment.Center) {
		Canvas(modifier = Modifier.size(20.dp)) {
			val width = size.width
			val height = size.height
			val stroke = Stroke(width = width * 0.12f)
			when (icon) {
				OutlinerIcon.PuppetRoot -> drawIcon(LocalUmamoIcons.puppetRoot, tint)
				OutlinerIcon.Armature -> drawIcon(LocalUmamoIcons.armature, tint)
				OutlinerIcon.Part -> drawIcon(LocalUmamoIcons.part, tint)
				OutlinerIcon.ArtMesh -> drawIcon(LocalUmamoIcons.mesh, tint)
				OutlinerIcon.WarpDeformer -> drawIcon(LocalUmamoIcons.warpDeformer, tint)
				OutlinerIcon.RotationDeformer -> drawIcon(LocalUmamoIcons.rotationDeformer, tint)
			}
		}
	}
}

/**
 * The clickable visibility eye: open while the row's entity is shown, struck-through while hidden.
 * Clicking flips the entity's visibility; with Shift held it flips the whole subtree to one uniform value
 * (Blender parity). The slot consumes its own press so the whole-row selection handler skips it,
 * mirroring the chevron.
 *
 * @param Boolean hidden Whether the row is dimmed (hidden or sketch).
 * @param Color tint The indicator colour.
 * @param Function onToggle Flips the visibility; the argument reports whether Shift was held (subtree).
 */
@Composable
private fun VisibilityIndicator(hidden: Boolean, tint: Color, onToggle: (shiftHeld: Boolean) -> Unit) {
	/* pointerInput(Unit) never re-captures its closures, so route through rememberUpdatedState to keep the
	   long-lived loop pointed at the latest callback (the row body's own convention). */
	val currentOnToggle by rememberUpdatedState(onToggle)
	Box(
		modifier =
			Modifier
				.size(RESTRICTION_SLOT_WIDTH)
				// Consume the press so the row's whole-row selection handler skips it (the eye only toggles
				// visibility), mirroring the chevron's own consume.
				.pointerInput(Unit) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							if (event.type == PointerEventType.Press) {
								event.changes.forEach { change -> change.consume() }
								currentOnToggle(event.keyboardModifiers.isShiftPressed)
							}
						}
					}
				},
		contentAlignment = Alignment.Center,
	) {
		Canvas(modifier = Modifier.size(16.dp)) {
			drawIcon(if (hidden) LocalUmamoIcons.eyeHidden else LocalUmamoIcons.eyeVisible, tint)
		}
	}
}

/**
 * The trailing selectable toggle: a small pointer the user clicks to flip whether the row's entity can be
 * picked in the viewport; with Shift held it flips the whole subtree to one uniform value (Blender
 * parity). An unselectable entity shows the struck-through pointer; a selectable one the plain pointer.
 * The slot consumes its own press so the whole-row selection handler skips it, mirroring the chevron and
 * the eye.
 *
 * @param Boolean selectable Whether the entity is viewport-selectable (plain vs struck-through pointer).
 * @param Color tint The base glyph colour.
 * @param Function onToggle Flips the selectability; the argument reports whether Shift was held (subtree).
 */
@Composable
private fun SelectableIndicator(selectable: Boolean, tint: Color, onToggle: (shiftHeld: Boolean) -> Unit) {
	/* pointerInput(Unit) never re-captures its closures, so route through rememberUpdatedState to keep the
	   long-lived loop pointed at the latest callback (the row body's own convention). */
	val currentOnToggle by rememberUpdatedState(onToggle)
	Box(
		modifier =
			Modifier
				.size(RESTRICTION_SLOT_WIDTH)
				.pointerInput(Unit) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							if (event.type == PointerEventType.Press) {
								event.changes.forEach { change -> change.consume() }
								currentOnToggle(event.keyboardModifiers.isShiftPressed)
							}
						}
					}
				},
		contentAlignment = Alignment.Center,
	) {
		Canvas(modifier = Modifier.size(16.dp)) {
			drawIcon(if (selectable) LocalUmamoIcons.selectable else LocalUmamoIcons.unselectable, tint)
		}
	}
}

/**
 * Computes the selection after a row click given the held modifiers: plain replaces, Ctrl / Cmd
 * ([toggle]) toggles the one target, and Shift ([extend]) range-selects from the active row to the
 * clicked one over the currently visible [rows], adding that contiguous run to the existing selection
 * (a mass-select). With no active anchor the range is just the clicked row.
 *
 * @param List rows The currently visible rows, in display order (the range runs over these).
 * @param Selection current The selection before the click.
 * @param Int clickedIndex The clicked row's index in [rows].
 * @param SelectionTarget target The clicked row's target (becomes active).
 * @param Boolean toggle Whether Ctrl / Cmd was held.
 * @param Boolean extend Whether Shift was held (range select).
 * @return Selection The resulting selection.
 */
private fun selectionAfterClick(
	rows: List<FlatRow>,
	current: Selection,
	clickedIndex: Int,
	target: SelectionTarget,
	toggle: Boolean,
	extend: Boolean,
): Selection =
	when {
		extend -> outlinerRangeSelection(rows.map { it.node.target }, current, clickedIndex, target)
		toggle -> SelectionOps.toggle(current, target)
		else -> SelectionOps.replace(target)
	}

/**
 * Measures the single width every row is fixed to: the wider of the viewport and the longest row (its
 * indent + chevron + icon + gap + the measured label + the trailing restriction slots its row kind and
 * the active restriction toggles compose). Fixing all rows to one width is what keeps the selection /
 * hover backgrounds full-width and the horizontal scroll range constant as rows scroll in and out.
 * Run once per visible-row set (memoised by the caller), not per frame.
 *
 * @param List rows The visible rows.
 * @param TextMeasurer measurer Measures label widths.
 * @param Density density For dp <-> px conversion.
 * @param Dp viewportWidth The available width - the floor for the result.
 * @param TextStyle bodyStyle The label style for non-drawable rows.
 * @param TextStyle drawableStyle The label style for drawable rows.
 * @param Boolean showSelectableColumn Whether the pointer restriction column renders.
 * @param Boolean showVisibilityColumn Whether the eye restriction column renders.
 * @return Dp The shared row width.
 */
private fun outlinerContentWidth(
	rows: List<FlatRow>,
	measurer: TextMeasurer,
	density: Density,
	viewportWidth: Dp,
	bodyStyle: TextStyle,
	drawableStyle: TextStyle,
	showSelectableColumn: Boolean,
	showVisibilityColumn: Boolean,
): Dp =
	with(density) {
		val basePx = 4.dp.toPx()
		val perDepthPx = INDENT_PER_DEPTH.toPx()
		val fixedPx = CHEVRON_WIDTH.toPx() + ICON_WIDTH.toPx() + 4.dp.toPx()
		val restrictionSlotPx = RESTRICTION_SLOT_WIDTH.toPx()
		val trailingSpacerPx = 6.dp.toPx()
		val trailingPx = 8.dp.toPx()
		var maxPx = viewportWidth.toPx()
		for (row in rows) {
			val style = if (row.node.target is SelectionTarget.Drawable) drawableStyle else bodyStyle
			val labelPx = measurer.measure(row.node.label, style).size.width.toFloat()
			val hasTarget = row.node.target != null
			val showEye = row.node.target is SelectionTarget.Part || row.node.target is SelectionTarget.Drawable
			// Must mirror the row body's trailing slots exactly: a selectable slot for every real row when
			// that column is on, an eye slot for parts / drawables when that column is on, and the trailing
			// spacer every real row keeps regardless.
			val trailingSlotsPx =
				(if (showSelectableColumn && hasTarget) restrictionSlotPx else 0f) +
					(if (showVisibilityColumn && showEye) restrictionSlotPx else 0f) +
					(if (hasTarget) trailingSpacerPx else 0f)
			val rowPx = basePx + perDepthPx * row.depth + fixedPx + labelPx + trailingSlotsPx + trailingPx
			if (rowPx > maxPx) {
				maxPx = rowPx
			}
		}
		maxPx.toDp()
	}

/**
 * Flattens the tree into the visible rows for the [LazyColumn], descending into a node's children only
 * when [isOpen] reports it expanded. Pure given the predicate, so the reveal effect can recompute the
 * same index the body renders.
 *
 * @param OutlinerNode root The tree root.
 * @param Function isOpen Reports whether a node id is expanded.
 * @return List the visible rows, each with its depth.
 */
private fun flattenOutliner(root: OutlinerNode, isOpen: (String) -> Boolean): List<FlatRow> {
	val rows = mutableListOf<FlatRow>()

	fun visit(node: OutlinerNode, depth: Int) {
		rows += FlatRow(node, depth)
		if (isOpen(node.id)) {
			node.children.forEach { child -> visit(child, depth + 1) }
		}
	}

	visit(root, 0)
	return rows
}

/**
 * Finds the id path from [root] to the node carrying [target], inclusive, or null if absent. Used by
 * reveal-on-select to expand a found node's ancestors before scrolling to it.
 *
 * @param OutlinerNode root The tree (sub)root to search.
 * @param SelectionTarget target The selection target to locate.
 * @return List the node ids from root to the match, or null.
 */
private fun pathTo(root: OutlinerNode, target: SelectionTarget): List<String>? {
	if (root.target == target) {
		return listOf(root.id)
	}
	for (child in root.children) {
		val sub = pathTo(child, target)
		if (sub != null) {
			return listOf(root.id) + sub
		}
	}
	return null
}
