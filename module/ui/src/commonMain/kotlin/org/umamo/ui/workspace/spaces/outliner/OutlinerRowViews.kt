package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.edit.RowDropBand
import org.umamo.edit.SelectionTarget
import org.umamo.edit.outlinerDropBandFor
import org.umamo.edit.rename
import org.umamo.edit.toggleSelectable
import org.umamo.edit.toggleSelectableSubtree
import org.umamo.edit.toggleVisibility
import org.umamo.edit.toggleVisibilitySubtree
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.menu.ContextMenuArea
import org.umamo.ui.kit.singleOrDoubleClick
import org.umamo.ui.kit.textentry.InlineRenameField
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.transform.deleteTargetKeepingRest
import org.umamo.ui.workspace.LocalRelationPick
import org.umamo.ui.workspace.rowdrag.RowCoordinatesHolder
import org.umamo.ui.workspace.rowdrag.RowDragController
import org.umamo.ui.workspace.rowdrag.RowDragRole
import org.umamo.ui.workspace.rowdrag.dragRowOnLongPress
import org.umamo.ui.workspace.rowdrag.rememberRowDragRole
import org.umamo.ui.workspace.rowdrag.rowDropHighlight
import org.umamo.ui.workspace.spaces.ReportRowHover
import org.umamo.ui.workspace.spaces.RowHoverPreviewState

/**
 * One tree row with its context menu attached.  A right-click (desktop) or long-press (touch) on a real
 * row opens [outlinerRowMenuItems]; the synthetic root rows (no target) have no menu and pass straight
 * through to the bare [OutlinerRowBody].
 *
 * The edits a row makes to its own entity - the eye, the pointer, the rename commit, a delete - are built
 * here from the [session] and the row's target, and the menu reuses the very same callbacks the inline
 * affordances drive, so a menu action and its affordance always do the same thing.  What the space owns
 * stays with the space and arrives as callbacks: the selection (which needs the visible rows and the
 * reveal to stand down), the rename state, and the drop.
 *
 * @param OutlinerRow row The node and its depth.
 * @param Function rowIndex The row's place among the visible rows, read while drawing: the ancestry guides'
 *   dash phase follows it, and a row that only moved is drawn again, not composed again.
 * @param Dp rowWidth The fixed width shared by every row.
 * @param Boolean selected Whether the node is in the current selection.
 * @param Boolean ancestorOfSelection Whether this part folder contains the selection.
 * @param Boolean matched Whether the node matches the active search.
 * @param Boolean expanded Whether the node is expanded.
 * @param Boolean renaming Whether this row's label is the inline rename editor.
 * @param Boolean showSelectableColumn Whether the pointer restriction column renders on the rows.
 * @param Boolean showVisibilityColumn Whether the eye restriction column renders on the rows.
 * @param OutlinerLabels labels The outliner's localized chrome.
 * @param OutlinerViewState viewState The area's view state, which the chevron folds through.
 * @param EditorSession? session The session the row's edits dispatch through; null (no document) makes them no-ops.
 * @param Function onSelect Applies a selection gesture for a real node.
 * @param Function onStartRename Opens this row's inline rename.
 * @param Function onRenameEnd Closes this row's inline rename, after a commit or a cancel.
 * @param RowDragController<SelectionTarget> dragController Shared drag state.
 * @param Function onDrop Applies the move when a drag started on this row ends.
 * @param Boolean hoverPreviewsEnabled Whether a hovered drawable / part row reports an art preview.
 * @param RowHoverPreviewState hoverPreview The space's hover preview state the row reports into.
 */
@Composable
internal fun OutlinerRowView(
	row: OutlinerRow,
	rowIndex: () -> Int,
	rowWidth: Dp,
	selected: Boolean,
	ancestorOfSelection: Boolean,
	matched: Boolean,
	expanded: Boolean,
	renaming: Boolean,
	showSelectableColumn: Boolean,
	showVisibilityColumn: Boolean,
	labels: OutlinerLabels,
	viewState: OutlinerViewState,
	session: EditorSession?,
	onSelect: (toggle: Boolean, extend: Boolean) -> Unit,
	onStartRename: () -> Unit,
	onRenameEnd: () -> Unit,
	dragController: RowDragController<SelectionTarget>,
	onDrop: () -> Unit,
	hoverPreviewsEnabled: Boolean,
	hoverPreview: RowHoverPreviewState<SelectionTarget>,
) {
	val node = row.node
	val target = node.target
	val onToggle = { viewState.toggleFold(node.id) }
	// Blender parity: Shift sets the clicked node and its whole subtree to one uniform value.
	val onToggleVisibility: (shiftHeld: Boolean) -> Unit = { shiftHeld ->
		if (target != null) {
			if (shiftHeld) {
				session?.toggleVisibilitySubtree(target)
			} else {
				session?.toggleVisibility(target)
			}
		}
	}
	val onToggleSelectable: (shiftHeld: Boolean) -> Unit = { shiftHeld ->
		if (target != null) {
			if (shiftHeld) {
				session?.toggleSelectableSubtree(target)
			} else {
				session?.toggleSelectable(target)
			}
		}
	}
	// No confirmation: history / undo makes an accidental delete cheap to recover, and the rows are hard to
	// hit by accident - so this applies immediately (the chosen behavior).
	val onRequestDelete: (cascade: Boolean) -> Unit = { cascade ->
		if (target != null) {
			session?.deleteTargetKeepingRest(target, cascade)
		}
	}
	// Only real rows rename; the editor needs a session to commit through.
	val startRename = {
		if (target != null && session != null) {
			onStartRename()
		}
	}
	val onCommitRename: (String) -> Unit = { newName ->
		if (target != null) {
			session?.rename(target, newName)
		}
		onRenameEnd()
	}
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
				labels = labels,
				onToggle = onToggle,
				onSelect = onSelect,
				renaming = renaming,
				onStartRename = startRename,
				onCommitRename = onCommitRename,
				onCancelRename = onRenameEnd,
				onToggleVisibility = onToggleVisibility,
				onToggleSelectable = onToggleSelectable,
				showSelectableColumn = showSelectableColumn,
				showVisibilityColumn = showVisibilityColumn,
				dragController = dragController,
				onDrop = onDrop,
				hoverPreviewsEnabled = hoverPreviewsEnabled,
				hoverPreview = hoverPreview,
			)
		}
	if (target == null) {
		body()
	} else {
		// Select Hierarchy dispatches through the registry with the row's target as the argument, so the
		// palette and a future binding share it.
		val commands = LocalCommands.current
		ContextMenuArea(
			items =
				outlinerRowMenuItems(
					target,
					labels,
					commands,
					// A menu action has no Shift context, so it is always the plain single-entity toggle.
					{ onToggleVisibility(false) },
					{ onToggleSelectable(false) },
					startRename,
					onRequestDelete,
				),
			content = body,
		)
	}
}

/**
 * Renders one tree row: indent, a disclosure chevron (only when the node has children), a type icon,
 * the single-line label (or its inline rename editor while [renaming]), and - for parts / drawables - a
 * clickable visibility eye pinned to the right.  Every row is fixed to [rowWidth] so the selection /
 * hover / search-match backgrounds span the full width and the horizontal scroll range stays put; a name
 * longer than the viewport grows [rowWidth] and is reached by scrolling rather than wrapping.  A single
 * click selects a real node or toggles a synthetic one; a double-click on a real node opens its inline
 * rename ([onStartRename]); the chevron and the eye consume their own press (only toggling expansion /
 * visibility).  A part folder whose subtree holds the selection is tinted ([ancestorOfSelection]); a
 * search hit is tinted too, so found rows stand out along the path the filter kept.
 *
 * @param OutlinerRow row The node and its depth.
 * @param Function rowIndex The row's place among the visible rows, read while drawing: the ancestry guides'
 *   dash phase follows it, and a row that only moved is drawn again, not composed again.
 * @param Dp rowWidth The fixed width shared by every row (the wider of the viewport and the longest row).
 * @param Boolean selected Whether the node is in the current selection.
 * @param Boolean ancestorOfSelection Whether this part folder contains the selection (tinted to signal it).
 * @param Boolean matched Whether the node's name matches the active search (tinted to stand out).
 * @param Boolean expanded Whether the node is expanded.
 * @param OutlinerLabels labels The outliner's localized chrome, for the slots' accessible names.
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
 * @param RowHoverPreviewState hoverPreview The space's hover preview state this drawable / part row reports into.
 */
@Composable
private fun OutlinerRowBody(
	row: OutlinerRow,
	rowIndex: () -> Int,
	rowWidth: Dp,
	selected: Boolean,
	ancestorOfSelection: Boolean,
	matched: Boolean,
	expanded: Boolean,
	labels: OutlinerLabels,
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
	hoverPreview: RowHoverPreviewState<SelectionTarget>,
) {
	val node = row.node
	// The long-press drag runs in a long-lived pointerInput coroutine that only re-captures its closures
	// when its keys change, and onDrop closes over live state; this keeps the drag's release pointed at the
	// latest callback.
	val currentOnDrop by rememberUpdatedState(onDrop)
	// Drag feedback: this row is the one being dragged (faded), or the row a release would drop on, with
	// the band the drop would land in - resolved by the same band rules the dispatch uses, so the indicator
	// always matches the move: an insertion line above (before) or below (after), or a whole-row ring (nest
	// into).  Only real rows take a drop.  Read as the row's part in the drag, which changes when this row's
	// part does, so a pointer moving over other rows, or inside one band of this one, runs nothing here.
	val rowTarget = node.target
	val dragRole by dragController.rememberRowDragRole(node.id) { dragged, fraction ->
		rowTarget?.let { target -> outlinerDropBandFor(dragged, target, fraction) }
	}
	val isDragged = dragRole == RowDragRole.Dragged
	val dropBand = dragRole.bandOrNull
	val isIntoTarget = dropBand == RowDropBand.Into
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	// Plain (non-snapshot) holder for the row's layout coordinates so onGloballyPositioned never forces a
	// recompose; the hover effect reads the live window bounds from it on demand to anchor the preview.
	val boundsHolder = remember { RowCoordinatesHolder() }
	// While a relation pick is armed, report this row as what a click here would bind, so the shell's badge
	// names it exactly as it does for a viewport hover.  Withdrawn on leaving, and only if still ours.
	val relationPick = LocalRelationPick.current
	LaunchedEffect(hovered, node.target) {
		val target = node.target ?: return@LaunchedEffect
		if (hovered) {
			relationPick.hover(target)
		} else {
			relationPick.unhover(target)
		}
	}
	// Report this row's hover so the space can pop (after its rest delay) an art preview beside it: a
	// drawable previews its own art mesh, a part the combined art of everything under it; deformers have no
	// art, and the synthetic root rows have no entity to report.  A row's target is fixed for its list key,
	// so the branch never flips under a live row.
	val hoverTarget = node.target
	if (hoverTarget != null) {
		ReportRowHover(
			state = hoverPreview,
			key = hoverTarget,
			name = node.label,
			hovered = hovered,
			enabled = hoverPreviewsEnabled && (hoverTarget is SelectionTarget.Drawable || hoverTarget is SelectionTarget.Part),
			boundsHolder = boundsHolder,
		)
	}
	// Drop this row's drag-hit-test bounds when it scrolls off, so a drop never targets an off-screen row.
	DisposableEffect(node.id) {
		onDispose { dragController.clearBounds(node.id) }
	}
	// A "nest inside" drop draws the shared drop ring over whatever fill the row has; before / after draw
	// an edge line instead (outlinerDropLine).
	val background =
		when {
			selected -> colors.selection.copy(alpha = 0.75f)
			hovered -> colors.rowHover
			ancestorOfSelection -> colors.selectionAncestorBackground
			matched -> colors.searchMatchBackground
			else -> Color.Transparent
		}
	val borderColor =
		when {
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
	val hasChildren = node.children.isNotEmpty()
	val showEye = node.target is SelectionTarget.Part || node.target is SelectionTarget.Drawable
	Row(
		modifier =
			Modifier.width(rowWidth)
				.height(OUTLINER_ROW_HEIGHT)
				// The dragged row fades while it is in flight - the whole row, fill and ring included, so the
				// alpha layer wraps everything painted below it.
				.alpha(if (isDragged) 0.4f else 1f)
				.hoverable(interaction)
				.onGloballyPositioned { coordinates ->
					boundsHolder.coordinates = coordinates
					// Publish the window bounds so a drag can hit-test the drop target against every visible row.
					dragController.reportBounds(node.id, coordinates.boundsInWindow())
				}
				// Long-press then drag to reparent: distinct from the tap-to-select gesture below, so a press still
				// selects first, then a hold begins the drag.  Only real rows (a target) pick up; the drop is
				// applied on release by the space, which reads the controller's target.
				.dragRowOnLongPress(dragController, node.id, node.target, boundsHolder) { currentOnDrop() }
				// Selection covers the whole row (indent, icon, label, blank space) so clicking anywhere but the
				// chevron or eye selects; those consume their own press, which the gesture skips.  A right press
				// falls through to the wrapping ContextMenuArea.  A double click on a real node opens the inline
				// rename; a synthetic node toggles on every press.  While renaming the editor owns the row, so
				// the gesture stands down.
				.singleOrDoubleClick(
					enabled = !renaming,
					onSingle = { modifiers ->
						if (node.target == null) {
							onToggle()
						} else {
							onSelect(modifiers.isCtrlPressed || modifiers.isMetaPressed, modifiers.isShiftPressed)
						}
					},
					onDouble = {
						if (node.target == null) {
							onToggle()
						} else {
							onStartRename()
						}
					},
				)
				// The fill, border, and ring sit inside the band inset, after the pointer input so the gap between
				// two bands still belongs to a row.
				.padding(OUTLINER_ROW_BAND_INSET)
				.background(background, shape = shapes.medium)
				.border(BorderStroke(1.dp, borderColor), shapes.medium)
				.rowDropHighlight(isIntoTarget, shapes.medium, colors)
				// Painted over the fill but behind the content, in this order: the guides, then the drop line
				// over them.
				.outlinerAncestryGuides(row.depth, rowIndex, colors.treeGuideLine)
				.outlinerDropLine(dropBand, row.depth, colors.accent)
				.padding(
					horizontal = OUTLINER_ROW_PADDING_HORIZONTAL - OUTLINER_ROW_BAND_INSET,
					vertical = OUTLINER_ROW_PADDING_VERTICAL - OUTLINER_ROW_BAND_INSET,
				),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Spacer(modifier = Modifier.width(OUTLINER_INDENT_BASE + OUTLINER_INDENT_PER_DEPTH * row.depth))
		ChevronSlot(visible = hasChildren, expanded = expanded, labels = labels, onToggle = onToggle, tint = colors.textMuted)
		OutlinerIconSlot(icon = node.icon, dimmed = node.dimmed)
		Spacer(modifier = Modifier.width(OUTLINER_ICON_LABEL_GAP))
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
		// row; the eye only parts / drawables.  The filter dropdown's restriction toggles gate each column
		// wholesale - a hidden column composes nothing, so its slot vanishes and a click there selects the
		// row like any other blank space (outlinerContentWidth mirrors this same math).
		if (showSelectableColumn && node.target != null) {
			SelectableIndicator(
				selectable = node.selectable,
				label = labels.selectable,
				tint = colors.textMuted,
				onToggle = onToggleSelectable,
			)
		}
		if (showVisibilityColumn && showEye) {
			VisibilityIndicator(
				hidden = node.dimmed,
				label = labels.visibility,
				tint = colors.textMuted,
				onToggle = onToggleVisibility,
			)
		}
		if (node.target != null) {
			Spacer(modifier = Modifier.width(OUTLINER_TRAILING_GAP))
		}
	}
}

/**
 * Draws the subtle dashed ancestry guides behind a row: one vertical line per ancestor indent column, so
 * deep branches line up visually (Blender's outliner ancestry lines).  Depth 0 (the root) draws none.  The
 * dash phase is offset by the row's stacked height so the dashes stay continuous from one row to the next
 * instead of resetting every row.  Muted so the lines stay subordinate to the labels in both themes.
 *
 * @param Int depth The row's depth: how many ancestor columns to draw.
 * @param Function rowIndex The row's place among the visible rows, read inside the draw for the dash phase.
 * @param Color color The guide line color.
 * @return Modifier This modifier drawing the guides behind the row.
 */
private fun Modifier.outlinerAncestryGuides(depth: Int, rowIndex: () -> Int, color: Color): Modifier =
	this.drawBehind {
		if (depth == 0) {
			return@drawBehind
		}
		// Drawn inside the row's band inset, so widen back out to the row's own frame: the guides run its full
		// height and join the next row's.
		inset(-OUTLINER_ROW_BAND_INSET.toPx()) {
			val leftEdgePx = OUTLINER_CONTENT_START.toPx()
			val indentPx = OUTLINER_INDENT_PER_DEPTH.toPx()
			val chevronHalfPx = OUTLINER_CHEVRON_WIDTH.toPx() / 2f
			val dashOnPx = 2.dp.toPx()
			val dashOffPx = 3.dp.toPx()
			val dashPeriodPx = dashOnPx + dashOffPx
			val dashEffect =
				PathEffect.dashPathEffect(
					floatArrayOf(dashOnPx, dashOffPx),
					(rowIndex() * size.height) % dashPeriodPx,
				)
			var ancestorLevel = 0
			while (ancestorLevel < depth) {
				val lineX = leftEdgePx + indentPx * ancestorLevel + chevronHalfPx
				drawLine(
					color = color,
					start = Offset(lineX, 0f),
					end = Offset(lineX, size.height),
					strokeWidth = 1.dp.toPx(),
					pathEffect = dashEffect,
				)
				ancestorLevel += 1
			}
		}
	}