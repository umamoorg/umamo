package org.umamo.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.kit.ContextMenuArea
import org.umamo.ui.kit.InlineRenameField
import org.umamo.ui.kit.LocalInlineEditController
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.Tab
import org.umamo.ui.kit.Text
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.drawIcon

/**
 * How long the pointer must rest on a tab before a drag reorders it.  Below this, a press is a tap
 * (select) or a quick flick, never a reorder - without the gate a fast click that nudges a few pixels
 * would silently reshuffle the tab order.
 */
private const val REORDER_HOLD_MILLIS = 100L

/**
 * The maximum gap between two clicks on the same tab that counts as a double-click and opens the inline
 * rename editor.  A fixed value rather than the platform's doubleTapTimeoutMillis, which on desktop
 * (Skiko) resolves to a window too short to hit comfortably.
 */
private const val DOUBLE_CLICK_MILLIS = 300L

/**
 * The browser-style top tab strip: one tab per workspace (the active one selected), a trailing "+"
 * button that creates a new workspace, a right-click context menu per tab (Duplicate, Delete, and the
 * Previous/Next navigation commands), and drag-to-reorder.  The strip is a thin view: every mutation is
 * reported to the shell, which owns the layout - selecting reports an id, create/duplicate/delete/reorder
 * report the requested edit, and Previous/Next dispatch through the action registry (so the same keyboard
 * shortcut and this menu drive one code path).
 *
 * 最上部のタブ帯。ワークスペースごとに 1 タブ＋末尾の「+」で新規作成、右クリックで複製・削除・前後移動、
 * ドラッグで並べ替え。状態は持たず、すべての変更をシェルに通知する。
 *
 * @param List workspaces The workspaces in tab order.
 * @param String activeId The currently active workspace id.
 * @param Function onSelect Reports the chosen workspace id.
 * @param Function onCreate Requests a new workspace, given a suggested (already-localized) base name.
 * @param Function onDuplicate Requests a copy of the workspace id, given a suggested name for the copy.
 * @param Function onDelete Requests deletion of the workspace id.
 * @param Function onReorder Requests moving the workspace at fromIndex to toIndex in tab order.
 * @param Function onRename Requests setting the workspace id's display name (from an inline edit).
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun WorkspaceTabs(
	workspaces: List<Workspace>,
	activeId: String,
	onSelect: (String) -> Unit,
	onCreate: (suggestedName: String) -> Unit,
	onDuplicate: (workspaceId: String, suggestedName: String) -> Unit,
	onDelete: (workspaceId: String) -> Unit,
	onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
	onRename: (workspaceId: String, newName: String) -> Unit,
	modifier: Modifier = Modifier,
) {
	val typography = LocalUmamoTypography.current
	val commands = LocalCommands.current
	val keymap = LocalKeymap.current
	val activeIndex = workspaces.indexOfFirst { workspace -> workspace.id == activeId }.coerceAtLeast(0)

	// Drag-to-reorder state. The dragged tab translates with the pointer while its layout slot stays put;
	// on release the projected center decides the destination index. Tab centers are measured in root
	// space (a common frame for all tabs) so the projected-center comparison is consistent.
	var draggingIndex by remember { mutableStateOf<Int?>(null) }
	var dragDeltaX by remember { mutableStateOf(0f) }
	val tabCenters = remember { mutableStateMapOf<Int, Float>() }
	// Bumped when a drag finishes, to force-rebuild each tab's hover interaction source (see the key below):
	// a reorder shifts tabs under a stationary pointer, which Compose does not see as a hover change, so a
	// tab hovered during the drag would otherwise stay highlighted until the pointer re-enters it.
	var reorderGeneration by remember { mutableStateOf(0) }
	// The workspace whose tab is being renamed in place (double-click or the context-menu Rename), or null.
	var editingWorkspaceId by remember { mutableStateOf<String?>(null) }
	// Tracks the previous press (its tab index and event time) so a second press on the same tab within the
	// double-click window opens the rename editor.  Index -1 means "no pending press".
	var lastTapIndex by remember { mutableStateOf(-1) }
	var lastTapUptime by remember { mutableStateOf(0L) }

	// Localized chrome resolved once here: the context-menu onSelect lambdas run on a pointer event, not
	// in composition, so they cannot call stringResource themselves - they capture these instead.
	val newWorkspaceName = stringResource(Res.string.workspace_new_name)
	val newWorkspaceLabel = stringResource(Res.string.workspace_new)
	val copySuffix = stringResource(Res.string.workspace_copy_suffix)
	val renameLabel = stringResource(Res.string.workspace_rename)
	val duplicateLabel = stringResource(Res.string.workspace_duplicate)
	val deleteLabel = stringResource(Res.string.workspace_delete)
	val previousLabel = stringResource(Res.string.cmd_workspace_prev)
	val nextLabel = stringResource(Res.string.cmd_workspace_next)
	val previousShortcut = keymap.chordFor("workspace.prev")?.let { chord -> formatAccelerator(chord) }
	val nextShortcut = keymap.chordFor("workspace.next")?.let { chord -> formatAccelerator(chord) }

	// height(IntrinsicSize.Min) makes the strip exactly as tall as a Tab's font-metric-driven line
	// box, so fillMaxHeight children (the "+" button) match the tabs to the pixel — a fixed dp size
	// cannot track a fractional text metric and always lands one pixel off at some density.
	Row(modifier = modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
		workspaces.forEachIndexed { index, workspace ->
			// A workspace with a name shows it verbatim (user data, never translated); otherwise the title
			// resolves from the id, which localizes built-ins (and is the id itself for anything else).  So
			// an unnamed built-in relabels on a runtime language switch, while renaming one promotes it to a
			// custom-named tab that keeps its literal name.  A legacy layout that baked a name onto a built-in
			// is cleared to null on load (see migrateLayout), so those tabs localize again rather than sticking.
			val title = workspace.name ?: workspaceTitle(workspace.id)
			if (editingWorkspaceId == workspace.id) {
				// The tab turns into an inline editor in place of its label.
				WorkspaceTabEditor(
					initialName = title,
					onCommit = { newName ->
						editingWorkspaceId = null
						onRename(workspace.id, newName)
					},
					onCancel = { editingWorkspaceId = null },
				)
			} else {
				val isDragging = draggingIndex == index
				val contextItems =
					listOf(
						MenuItem.Action(renameLabel, onSelect = { editingWorkspaceId = workspace.id }),
						MenuItem.Action(duplicateLabel, onSelect = { onDuplicate(workspace.id, "$title $copySuffix") }),
						// The strip never goes empty, so deleting the last workspace is offered as a disabled row.
						MenuItem.Action(
							deleteLabel,
							onSelect = { onDelete(workspace.id) },
							enabled = workspaces.size > 1,
						),
						MenuItem.Separator,
						MenuItem.Action(
							previousLabel,
							onSelect = { commands.invoke("workspace.prev") },
							shortcut = previousShortcut,
						),
						MenuItem.Action(
							nextLabel,
							onSelect = { commands.invoke("workspace.next") },
							shortcut = nextShortcut,
						),
					)
				Box(
					modifier =
						Modifier
							// Measured on the static slot (the Box), not the translated Tab, so the slot center is
							// stable while the tab visual is dragged across it.  The center is resolved in the parent
							// (Row) space - a frame shared by every tab - so the drag's projected-center comparison is
							// consistent across tabs.
							.onGloballyPositioned { coordinates ->
								val parent = coordinates.parentLayoutCoordinates
								if (parent != null) {
									tabCenters[index] =
										parent.localPositionOf(coordinates, Offset(coordinates.size.width / 2f, 0f)).x
								}
							}
							// Raise the dragged tab above its neighbors so it draws (and is hit-tested) on top.
							.then(if (isDragging) Modifier.zIndex(1f) else Modifier)
							.pointerInput(index, workspaces.size) {
								awaitEachGesture {
									val down = awaitFirstDown(requireUnconsumed = false)
									// A secondary/tertiary press belongs to the context-menu gesture, never to a tab drag
									// or a rename tap - leave it for ContextMenuArea.  (Touch reports neither, so it passes.)
									if (currentEvent.buttons.isSecondaryPressed || currentEvent.buttons.isTertiaryPressed) {
										return@awaitEachGesture
									}
									// Double-click is detected on the press, not the release: the tab's clickable consumes
									// the up when it fires its click, so the up is an unreliable signal, but the down always
									// reaches us.  Two presses on the same tab within the window open the rename editor.
									val pressTime = down.uptimeMillis
									if (lastTapIndex == index && pressTime - lastTapUptime <= DOUBLE_CLICK_MILLIS) {
										lastTapIndex = -1
										lastTapUptime = 0L
										editingWorkspaceId = workspace.id
										return@awaitEachGesture
									}
									lastTapIndex = index
									lastTapUptime = pressTime
									// Hold gate: wait out the window watching the raw pressed state (read from the event,
									// not waitForUpOrCancellation, whose result the clickable's up-consumption would defeat).
									// A lift within the window is a click: the clickable handled selection, nothing to do here.
									val liftedWithinWindow =
										withTimeoutOrNull(REORDER_HOLD_MILLIS) {
											var pointerUp = false
											while (!pointerUp) {
												val event = awaitPointerEvent()
												val change =
													event.changes.firstOrNull { pointer -> pointer.id == down.id }
												if (change == null || !change.pressed) {
													pointerUp = true
												}
											}
											true
										}
									if (liftedWithinWindow != null) {
										return@awaitEachGesture
									}
									// Held past the window: arm the reorder, engaging once the pointer actually moves.
									var engaged = false
									while (true) {
										val event = awaitPointerEvent()
										val change =
											event.changes.firstOrNull { pointer -> pointer.id == down.id } ?: break
										if (!change.pressed) {
											break
										}
										if (!engaged && change.positionChange().getDistanceSquared() > 0f) {
											engaged = true
											draggingIndex = index
											dragDeltaX = 0f
										}
										if (engaged) {
											dragDeltaX += change.positionChange().x
											change.consume()
										}
									}
									if (engaged) {
										val fromIndex = draggingIndex
										if (fromIndex != null) {
											val centers =
												(0 until workspaces.size).map { tabIndex -> tabCenters[tabIndex] }
											val toIndex = reorderTargetIndex(fromIndex, dragDeltaX, centers)
											if (toIndex != fromIndex) {
												onReorder(fromIndex, toIndex)
											}
										}
										draggingIndex = null
										dragDeltaX = 0f
										// A drag's press must not pair with a later click as a double-click.
										lastTapIndex = -1
										// Rebuild the tab visuals so no tab keeps a hover left stale by the layout shift.
										reorderGeneration++
									}
								}
							},
				) {
					// Keyed on reorderGeneration so a completed reorder rebuilds the tab (and its hover
					// interaction source) from scratch - clearing any hover the layout shift left stale.
					key(reorderGeneration) {
						ContextMenuArea(items = contextItems) {
							Tab(
								selected = index == activeIndex,
								onClick = { onSelect(workspace.id) },
								modifier =
									Modifier.graphicsLayer {
										translationX = if (isDragging) dragDeltaX else 0f
										alpha = if (isDragging) 0.85f else 1f
									},
								label = { Text(title, style = typography.labelMedium) },
							)
						}
					}
				}
			}
		}
		NewWorkspaceButton(contentDescription = newWorkspaceLabel, onClick = { onCreate(newWorkspaceName) })
	}
}

/**
 * The in-place rename editor shown over a tab's slot.  Styled to match a selected [Tab] (panel fill, same
 * padding) so it reads as the tab itself turning editable.  The whole name starts selected so typing
 * replaces it; it commits on Enter or when focus leaves (clicking away), and cancels on Escape - the last
 * routed in by the shell via [LocalInlineEditController], because the shell's root key handler previews
 * Escape (and every shortcut) before this field could see it.  A blank name commits as a cancel, so the
 * tab keeps its old title.
 *
 * タブ位置に重ねるインライン名前編集。Enter／フォーカス喪失で確定、Escape で取消（シェル経由）。空名は取消扱い。
 *
 * @param String initialName The current title, pre-selected for replacement.
 * @param Function onCommit Called with the trimmed new name when the edit is confirmed.
 * @param Function onCancel Called when the edit is abandoned (Escape or a blank name).
 */
@Composable
private fun WorkspaceTabEditor(initialName: String, onCommit: (String) -> Unit, onCancel: () -> Unit) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val typography = LocalUmamoTypography.current
	Box(
		modifier =
			Modifier
				.padding(horizontal = 2.dp)
				.background(colors.headerBackground, shape = shapes.medium)
				.padding(horizontal = 10.dp, vertical = 4.dp),
		contentAlignment = Alignment.Center,
	) {
		InlineRenameField(
			initialName = initialName,
			textStyle = typography.labelMedium.copy(color = colors.text),
			cursorColor = colors.text,
			onCommit = onCommit,
			onCancel = onCancel,
			modifier = Modifier.widthIn(min = 48.dp),
		)
	}
}

/**
 * The trailing "+" affordance that creates a new workspace.  Styled to match a [Tab] at rest (transparent
 * until hovered) so it reads as part of the strip rather than a heavy button; the localized
 * [contentDescription] gives it an accessible label since its face is only a glyph.
 *
 * タブ帯末尾の「+」。新規ワークスペースを作成する。グリフのみのため content description を付ける。
 *
 * @param String contentDescription The accessible label (the localized "New Workspace").
 * @param Function onClick Invoked to create a new workspace.
 */
@Composable
private fun NewWorkspaceButton(contentDescription: String, onClick: () -> Unit) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	// fillMaxHeight (against the strip's IntrinsicSize.Min row) keeps this button exactly as tall as
	// the text-metric-sized tabs beside it; no vertical padding, the icon centers in the full height.
	Box(
		modifier =
			Modifier
				.padding(horizontal = 2.dp)
				.fillMaxHeight()
				.clip(shapes.medium)
				.clickable(interactionSource = interaction, indication = null, onClick = onClick)
				.background(if (hovered) colors.rowHover else Color.Transparent, shape = shapes.medium)
				.semantics { this.contentDescription = contentDescription }
				.padding(horizontal = 3.dp),
		contentAlignment = Alignment.Center,
	) {
		Canvas(modifier = Modifier.size(16.dp)) {
			drawIcon(LocalUmamoIcons.plus, colors.textMuted)
		}
	}
}

/**
 * The destination index for a drag-reordered tab: the number of other tabs whose center lies left of the
 * dragged tab's projected center (its measured center plus the accumulated drag).  Because the dragged
 * tab is removed before re-insertion, this count is exactly its final index - moving right past one
 * neighbor yields the neighbor's old index, moving fully left yields 0.  Tabs with an unmeasured center
 * (null, only on the first frame) are ignored, and a missing dragged center is a no-op (returns
 * [fromIndex]).  Pure so the off-by-one-prone index math is unit-tested directly.
 *
 * ドラッグ並べ替えの着地インデックス。投影中心より左にある他タブの数（＝除去後の最終位置）。
 *
 * @param Int fromIndex The dragged tab's current index.
 * @param Float dragDeltaX The accumulated horizontal drag in pixels.
 * @param List centers The per-index measured tab centers (null where not yet measured).
 * @return Int The destination index (equal to fromIndex when it should not move).
 */
internal fun reorderTargetIndex(fromIndex: Int, dragDeltaX: Float, centers: List<Float?>): Int {
	val fromCenter = centers.getOrNull(fromIndex) ?: return fromIndex
	val projectedCenter = fromCenter + dragDeltaX
	var targetIndex = 0
	centers.forEachIndexed { otherIndex, center ->
		if (otherIndex != fromIndex && center != null && center < projectedCenter) {
			targetIndex++
		}
	}
	return targetIndex
}

/**
 * Resolves a workspace id to its display title. Built-in ids map to localized strings; any other id
 * (a user-created/renamed workspace whose name is user data, not chrome) shows verbatim.
 *
 * ワークスペース id を表示タイトルに解決する。組み込み id はローカライズ、それ以外はそのまま。
 *
 * @param String workspaceId The workspace id.
 * @return String The localized or literal title.
 */
@Composable
fun workspaceTitle(workspaceId: String): String =
	when (workspaceId) {
		"modelling" -> stringResource(Res.string.workspace_modelling)
		"texture" -> stringResource(Res.string.workspace_texture)
		"physics" -> stringResource(Res.string.workspace_physics)
		else -> workspaceId
	}
