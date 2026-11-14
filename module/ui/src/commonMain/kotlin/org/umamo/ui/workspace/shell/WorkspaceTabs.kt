package org.umamo.ui.workspace.shell

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
import org.umamo.ui.action.CommandMenuRow
import org.umamo.ui.action.CommandSection
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.rememberCommandMenu
import org.umamo.ui.kit.DoubleClickTracker
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.container.Tab
import org.umamo.ui.kit.menu.ContextMenuArea
import org.umamo.ui.kit.textentry.InlineRenameField
import org.umamo.ui.kit.textentry.LocalInlineEditController
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.drawIcon
import org.umamo.ui.workspace.layout.Workspace

/**
 * How long the pointer must rest on a tab before a drag reorders it.  Below this, a press is a tap
 * (select) or a quick flick, never a reorder - without the gate a fast click that nudges a few pixels
 * would silently reshuffle the tab order.
 */
private const val REORDER_HOLD_MILLIS = 100L

/**
 * A tab's own menu: what is done to that tab's workspace, each row aimed at it, then moving between workspaces.
 *
 * @param String workspaceId The tab's workspace.
 * @return List<CommandMenuRow> The rows.
 */
private fun tabMenuRows(workspaceId: String): List<CommandMenuRow> =
	listOf(
		CommandMenuRow.Members(CommandSection.WorkspaceTab, argument = workspaceId),
		CommandMenuRow.Separator,
		CommandMenuRow.Members(CommandSection.WorkspaceSwitch),
	)

/**
 * The browser-style top tab strip: one tab per workspace plus a trailing "+" button, with a per-tab
 * context menu, inline rename, and drag-to-reorder.  A thin view: the layout is the shell's, and the strip
 * reports a selection, a reorder, and the name an inline edit settles on.
 *
 * What is done to a workspace goes through its command: the "+" button is New Workspace, a tab's menu is the
 * workspace's Rename, Duplicate, and Delete aimed at that tab, and a double-click is Rename.  So the strip, the
 * Workspace menu, the palette, and a key all run one thing, and the strip learns that a tab is being renamed
 * from [renamingId] whichever of them asked.
 *
 * @param List workspaces The workspaces in tab order.
 * @param String activeId The currently active workspace id.
 * @param String? renamingId The workspace whose tab shows the inline rename editor, or null while none does.
 * @param Function onSelect Reports the chosen workspace id.
 * @param Function onReorder Requests moving the workspace at fromIndex to toIndex in tab order.
 * @param Function onRename Requests setting the workspace id's display name (from an inline edit).
 * @param Function onRenameCancel Reports that the inline edit was abandoned.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun WorkspaceTabs(
	workspaces: List<Workspace>,
	activeId: String,
	renamingId: String?,
	onSelect: (String) -> Unit,
	onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
	onRename: (workspaceId: String, newName: String) -> Unit,
	onRenameCancel: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val typography = LocalUmamoTypography.current
	val commands = LocalCommands.current
	val activeIndex = workspaces.indexOfFirst { workspace -> workspace.id == activeId }.coerceAtLeast(0)

	// Drag-to-reorder state.  The dragged tab translates with the pointer while its layout slot stays put;
	// on release the projected center decides the destination index.  Tab centers are measured in root
	// space (a common frame for all tabs) so the projected-center comparison is consistent.
	var draggingIndex by remember { mutableStateOf<Int?>(null) }
	var dragDeltaX by remember { mutableStateOf(0f) }
	val tabCenters = remember { mutableStateMapOf<Int, Float>() }
	// Bumped when a drag finishes, to force-rebuild each tab's hover interaction source (see the key below):
	// a reorder shifts tabs under a stationary pointer, which Compose does not see as a hover change, so a
	// tab hovered during the drag would otherwise stay highlighted until the pointer re-enters it.
	var reorderGeneration by remember { mutableStateOf(0) }

	// height(IntrinsicSize.Min) makes the strip exactly as tall as a Tab's font-metric-driven line
	// box, so fillMaxHeight children (the "+" button) match the tabs to the pixel — a fixed dp size
	// cannot track a fractional text metric and always lands one pixel off at some density.
	Row(modifier = modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
		workspaces.forEachIndexed { index, workspace ->
			// A workspace with a name shows it verbatim (user data, never translated); otherwise the title
			// resolves from the id, which localizes built-ins (and is the id itself for anything else).  So
			// an unnamed built-in relabels on a runtime language switch, while renaming one promotes it to a
			// custom-named tab that keeps its literal name.
			val title = workspace.name ?: workspaceTitle(workspace.id)

			if (renamingId == workspace.id) {
				// The tab turns into an inline editor in place of its label.
				WorkspaceTabEditor(
					initialName = title,
					onCommit = { newName -> onRename(workspace.id, newName) },
					onCancel = onRenameCancel,
				)
			} else {
				val isDragging = draggingIndex == index
				// Read from the registry as the menu opens, so Delete is grayed on the last workspace by its own command.
				val tabMenu = rememberCommandMenu(remember(workspace.id) { tabMenuRows(workspace.id) })
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
							// Keyed on the workspace as well as the slot: the gesture reads workspace.id, and a reorder
							// can hand a slot a different workspace without changing its index or the tab count.
							.pointerInput(workspace.id, index, workspaces.size) {
								// One tracker per tab, so a press on one tab never pairs with a press on another.
								val clickTracker = DoubleClickTracker()
								awaitEachGesture {
									val down = awaitFirstDown(requireUnconsumed = false)

									// A secondary/tertiary press belongs to the context-menu gesture, never to a tab drag
									// or a rename tap - leave it for ContextMenuArea.  (Touch reports neither, so it passes.)
									if (currentEvent.buttons.isSecondaryPressed || currentEvent.buttons.isTertiaryPressed) {
										return@awaitEachGesture
									}

									// Double-click is detected on the press, not the release: the tab's clickable consumes
									// the up when it fires its click, so the up is an unreliable signal, but the down always
									// reaches us.  Selection stays on that clickable, which is why this gesture uses the
									// shared timing core rather than the singleOrDoubleClick modifier: the clickable consumes
									// the press before a parent modifier would see it.
									if (clickTracker.registerPress(down.uptimeMillis)) {
										commands.invoke("workspace.rename", workspace.id)

										return@awaitEachGesture
									}

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
										clickTracker.reset()
										// Rebuild the tab visuals so no tab keeps a hover left stale by the layout shift.
										reorderGeneration++
									}
								}
							},
				) {
					// Keyed on reorderGeneration so a completed reorder rebuilds the tab (and its hover
					// interaction source) from scratch - clearing any hover the layout shift left stale.
					key(reorderGeneration) {
						ContextMenuArea(itemsOnOpen = tabMenu) {
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
		NewWorkspaceButton(contentDescription = stringResource(Res.string.workspace_new), onClick = { commands.invoke("workspace.new") })
	}
}

/**
 * The in-place rename editor shown over a tab's slot, styled to match a selected [Tab] so it reads as the
 * tab itself turning editable.  The commit and cancel lifecycle is [InlineRenameField]'s, with Escape
 * routed in through [LocalInlineEditController].
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
	// The label doubles as the hover text, matching the kit's icon controls; the wrapper carries the
	// fillMaxHeight so the button still measures against the strip row.
	Tooltip(text = contentDescription, modifier = Modifier.fillMaxHeight()) {
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
}

/**
 * The destination index for a drag-reordered tab: the number of other tabs whose center lies left of the
 * dragged tab's projected center (its measured center plus the accumulated drag).  Because the dragged
 * tab is removed before re-insertion, this count is exactly its final index.
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

/** The built-in workspaces' titles, by id.  A built-in has no stored name until it is renamed. */
private val BUILTIN_WORKSPACE_TITLES =
	mapOf(
		"modelling" to Res.string.workspace_modelling,
		"texture" to Res.string.workspace_texture,
		"physics" to Res.string.workspace_physics,
	)

/**
 * Resolves a workspace id to its display title.  Built-in ids map to localized strings; any other id
 * (a user-created/renamed workspace whose name is user data, not chrome) shows verbatim.
 *
 * @param String workspaceId The workspace id.
 * @return String The localized or literal title.
 */
@Composable
fun workspaceTitle(workspaceId: String): String = BUILTIN_WORKSPACE_TITLES[workspaceId]?.let { title -> stringResource(title) } ?: workspaceId

/**
 * The built-in workspaces' titles in the current locale, by id, for the commands that name a workspace outside a
 * composition.
 *
 * @return Map<String, String> The titles.
 */
@Composable
internal fun builtinWorkspaceTitles(): Map<String, String> = BUILTIN_WORKSPACE_TITLES.mapValues { (_, title) -> stringResource(title) }