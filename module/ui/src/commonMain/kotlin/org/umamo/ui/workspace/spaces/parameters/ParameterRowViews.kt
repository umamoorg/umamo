package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterMoveSubject
import org.umamo.edit.ParameterSelection
import org.umamo.edit.RowDropBand
import org.umamo.edit.renameParameter
import org.umamo.edit.renameParameterGroup
import org.umamo.edit.setParameterLink
import org.umamo.edit.setParameterRange
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.kit.menu.ContextMenuArea
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.workspace.rowdrag.RowDragController
import org.umamo.ui.workspace.rowdrag.RowDragRole
import org.umamo.ui.workspace.rowdrag.rememberRowDragRole

/** Indentation applied per group nesting level, matching the outliner's indent idiom. */
private val INDENT_PER_DEPTH = 12.dp

/**
 * One row of the list: the frame every kind shares - the bounds the drop hit-test reads, the drop line,
 * the dimming of the row being dragged, the group indent, and the grip - around the kind's own content.
 *
 * @param ParameterRow row The row to show.
 * @param Map<ParameterId, ParameterKeyMarks> keyMarksByParameter Each parameter's grid / blend key marks.
 * @param ParameterSelection parameterSelection The keyform-authoring target, which outlines its rows.
 * @param ParameterLabels labels The panel's localized chrome.
 * @param ParameterPoseState pose The pose the controls show and write.
 * @param ParametersViewState viewState The panel's view state.
 * @param EditorSession? session The editing session, or null with none.
 * @param List<MenuItem> createMenuItems The entries that create, which every parameter menu ends with.
 * @param RowDragController<ParameterMoveSubject> dragController The panel's drag state.
 * @param Function onDrop Invoked on release of a drag that began on this row, to apply the move.
 */
@Composable
internal fun ParameterRowView(
	row: ParameterRow,
	keyMarksByParameter: Map<ParameterId, ParameterKeyMarks>,
	parameterSelection: ParameterSelection,
	labels: ParameterLabels,
	pose: ParameterPoseState,
	viewState: ParametersViewState,
	session: EditorSession?,
	createMenuItems: List<MenuItem>,
	dragController: RowDragController<ParameterMoveSubject>,
	onDrop: () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val key = rowKey(row)
	// The panel builds its row objects again every time it recomposes, around the same parameters.  What
	// the grip is handed is taken so that it stays the same object across that: the callback reads the
	// row through state rather than holding it, and the subject is remembered on what it is made of - the
	// key, and for a pad the vertical axis its key leaves out.  Either one new each time would run the
	// grip again with nothing changed.
	val currentRow by rememberUpdatedState(row)
	val subject = remember(key, (row as? ParameterRow.Pair2D)?.vertical?.id) { parameterMoveSubjectOf(row) }
	// Drop this row's window bounds when it scrolls off, so the drop hit-test never
	// targets an invisible row.
	DisposableEffect(key) {
		onDispose { dragController.clearBounds(key) }
	}
	// The band this row would receive if a drop landed now (null unless it is the drag target), and
	// whether it is the row in hand.  Read as the row's part in the drag, which changes when this row's
	// part does, so a pointer moving over other rows, or inside one band of this one, runs nothing here.
	val dragRole by dragController.rememberRowDragRole(key) { dragged, fraction ->
		parameterDropBandFor(dragged, currentRow, currentRow.depth == 0, fraction)
	}
	val dropBand: RowDropBand? = dragRole.bandOrNull
	val isDragged = dragRole == RowDragRole.Dragged
	// A row being named asks to be shown whole.  Its rename field asks for itself the moment it takes
	// focus, and a list scrolling toward the row stops for that as soon as the field is in view, which
	// leaves whatever of the row sits above the field cut off.  The row's own request holds the field's
	// inside it, so the list keeps going until both are met.
	val wholeRow = remember { BringIntoViewRequester() }
	val beingNamed = isBeingNamed(row, viewState.renamingGroupId, viewState.renamingParameterId)
	LaunchedEffect(beingNamed) {
		if (beingNamed) {
			wholeRow.bringIntoView()
		}
	}
	Row(
		// Report the OUTER row's bounds (not the inner island's) so the drop band math
		// measures the whole row. The group-depth indent sits here, beside the grip.
		modifier =
			Modifier
				.fillMaxWidth()
				.bringIntoViewRequester(wholeRow)
				.onGloballyPositioned { coordinates ->
					dragController.reportBounds(
						key,
						coordinates.boundsInWindow(),
					)
				}
				.parameterDropLine(dropBand, colors.accent)
				.alpha(if (isDragged) 0.4f else 1f)
				.padding(start = INDENT_PER_DEPTH * row.depth),
		verticalAlignment = Alignment.CenterVertically,
	) {
		ParameterGripHandle(
			gripLabel = labels.reorderHandle,
			subject = subject,
			rowKey = key,
			dragController = dragController,
			onDrop = onDrop,
			// Clicking the handle targets the row's parameters. The island itself is
			// crowded - slider, pad, name, numeric field, reset glyph all consume their
			// own pointer input - so the handle is the one reliably empty place to click,
			// and it already means "this row" for the drag.  A group header selects
			// nothing: it owns no parameter to key on.
			onSelect = {
				parameterSelectionOf(currentRow)?.let { selection ->
					session?.setParameterSelection(selection)
				}
			},
		)
		// The row's content takes the width the grip leaves.  The weight is handed down from here because
		// it exists only in this Row's scope.
		when (row) {
			is ParameterRow.GroupHeader -> {
				ParameterGroupRow(
					row = row,
					nesting = dropBand == RowDropBand.Into,
					labels = labels,
					viewState = viewState,
					session = session,
					modifier = Modifier.weight(1f),
				)
			}

			is ParameterRow.Single -> {
				ParameterSliderRow(
					row = row,
					keyMarks = keyMarksByParameter[row.parameter.id],
					selected = row.parameter.id in parameterSelection,
					labels = labels,
					pose = pose,
					viewState = viewState,
					session = session,
					createMenuItems = createMenuItems,
					modifier = Modifier.weight(1f),
				)
			}

			is ParameterRow.Pair2D -> {
				ParameterPadRow(
					row = row,
					selected = row.horizontal.id in parameterSelection || row.vertical.id in parameterSelection,
					labels = labels,
					pose = pose,
					viewState = viewState,
					session = session,
					createMenuItems = createMenuItems,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

/**
 * A group header's row: the rail, or the rename field in its place, under the group's context menu.
 *
 * @param ParameterRow.GroupHeader row The group to show.
 * @param Boolean nesting Whether a drag is hovering to nest into this group.
 * @param ParameterLabels labels The panel's localized chrome.
 * @param ParametersViewState viewState The panel's view state, which holds the fold and the rename target.
 * @param EditorSession? session The editing session, or null with none.
 * @param Modifier modifier The layout modifier (the caller supplies the row weight).
 */
@Composable
private fun ParameterGroupRow(
	row: ParameterRow.GroupHeader,
	nesting: Boolean,
	labels: ParameterLabels,
	viewState: ParametersViewState,
	session: EditorSession?,
	modifier: Modifier = Modifier,
) {
	val expandedGroups = viewState.expandedGroups
	val renaming = viewState.renamingGroupId == row.groupId
	// A search shows every group open whatever its fold says, so for as long as one runs a press has
	// nothing to fold, and must not write a fold the rigger cannot see change.
	val foldLocked = viewState.searching
	ContextMenuArea(
		items = parameterGroupMenuItems(row.groupId, labels, session, viewState),
		modifier = modifier,
	) {
		if (renaming) {
			ParameterGroupRenameField(
				initialName = row.name,
				onCommit = { newName ->
					session?.renameParameterGroup(row.groupId, newName)
					viewState.renamingGroupId = null
				},
				onCancel = { viewState.renamingGroupId = null },
			)
		} else {
			// A recessed rounded rail (tabBackground) so groups read as chrome under the
			// raised islands; a nest-into drop tints and outlines it. Double-click the
			// header to rename it (pointerInput, so it stays keyboard-focus-safe).
			ParameterGroupHeaderBody(
				name = row.name,
				expanded = row.expanded,
				nesting = nesting,
				onToggle = {
					if (!foldLocked) {
						expandedGroups[row.groupId] = !(expandedGroups[row.groupId] ?: row.expanded)
					}
				},
				onStartRename = {
					// The double-click's first press already fired the immediate single-click
					// toggle; flip the group back so renaming does not also open / close it.
					// Flip the LIVE map value, not the captured row's - the capture can be a
					// recomposition stale under a fast double click, and flipping a stale
					// value re-applies the first toggle instead of undoing it.  Under a fold lock
					// the first press toggled nothing, and there is nothing to flip back.
					if (!foldLocked) {
						expandedGroups[row.groupId] = !(expandedGroups[row.groupId] ?: row.expanded)
					}
					viewState.renamingGroupId = row.groupId
				},
			)
		}
	}
}

/**
 * A slider's row: the island holding the slider and, while open, its range editor, under the
 * parameter's context menu.
 *
 * The rename slot and the link action are built here, above the menu area, and the value is read inside
 * the island.  A scrub invalidates the scope that reads the value and no wider one, so a scrub frame
 * builds neither holder again.
 *
 * @param ParameterRow.Single row The slider to show.
 * @param ParameterKeyMarks? keyMarks The parameter's grid / blend key marks, or null with none.
 * @param Boolean selected Whether the parameter is the keyform-authoring target.
 * @param ParameterLabels labels The panel's localized chrome.
 * @param ParameterPoseState pose The pose the slider shows and writes.
 * @param ParametersViewState viewState The panel's view state, which holds the open range editors and
 *   the rename target.
 * @param EditorSession? session The editing session, or null with none.
 * @param List<MenuItem> createMenuItems The entries that create, which the row's menu ends with.
 * @param Modifier modifier The layout modifier (the caller supplies the row weight).
 */
@Composable
private fun ParameterSliderRow(
	row: ParameterRow.Single,
	keyMarks: ParameterKeyMarks?,
	selected: Boolean,
	labels: ParameterLabels,
	pose: ParameterPoseState,
	viewState: ParametersViewState,
	session: EditorSession?,
	createMenuItems: List<MenuItem>,
	modifier: Modifier = Modifier,
) {
	val parameter = row.parameter
	// Read through state, so the island's callback stays the same object while the panel builds new rows.
	val currentRow by rememberUpdatedState(row)
	val shownValue by pose.rememberShownValue(parameter)
	val linkCandidateId = row.linkCandidateId
	val rangeOpen = viewState.openRangeEditors[parameter.id] == true
	val rename = parameterRenameSlot(parameter.id, rangeEditorId = parameter.id, viewState, session)
	// Link editing is a document edit, not a pose write, so it deliberately
	// bypasses the Edit-mode parameter lock (the same policy as range edits).
	val link =
		if (linkCandidateId != null && session != null) {
			ParameterLinkAction(LocalUmamoIcons.unlinked, labels.link) {
				session.setParameterLink(parameter.id, linkCandidateId, linked = true)
			}
		} else {
			null
		}
	ContextMenuArea(
		items = parameterSliderMenuItems(parameter.id, labels, session, viewState, createMenuItems),
		modifier = modifier,
	) {
		ParameterIsland(
			modifier = Modifier.fillMaxWidth(),
			selected = selected,
			// Through parameterSelectionOf, the ONE place the targeting policy lives -
			// the grip handle uses the same function, so click and grab cannot drift.
			onSelect = { parameterSelectionOf(currentRow)?.let { target -> session?.setParameterSelection(target) } },
		) {
			ParameterSlider(
				parameter = parameter,
				keyMarks = keyMarks,
				value = shownValue,
				labels = labels,
				rangeOpen = rangeOpen,
				onToggleRange = {
					viewState.openRangeEditors[parameter.id] = !rangeOpen
				},
				rename = rename,
				onPreview = { newValue -> pose.preview(parameter.id, newValue) },
				onCommitGesture = { pose.commitGesture(setOf(parameter.id)) },
				onCommitValue = { newValue -> pose.commitValue(parameter.id, newValue) },
				link = link,
			)
			if (rangeOpen) {
				RangeFieldsRow(
					parameter = parameter,
					onSetRange = { min, default, max ->
						session?.setParameterRange(
							parameter.id,
							min,
							default,
							max,
						)
					},
				)
			}
		}
	}
}

/**
 * A linked pair's row: the island holding the pad and, while open, a range editor per axis, under the
 * pad's context menu.  The island keys its open state on the horizontal (upper) member.
 *
 * @param ParameterRow.Pair2D row The pad to show.
 * @param Boolean selected Whether either axis is the keyform-authoring target.
 * @param ParameterLabels labels The panel's localized chrome.
 * @param ParameterPoseState pose The pose the pad shows and writes.
 * @param ParametersViewState viewState The panel's view state, which holds the open range editors and
 *   the rename target.
 * @param EditorSession? session The editing session, or null with none.
 * @param List<MenuItem> createMenuItems The entries that create, which the row's menu ends with.
 * @param Modifier modifier The layout modifier (the caller supplies the row weight).
 */
@Composable
private fun ParameterPadRow(
	row: ParameterRow.Pair2D,
	selected: Boolean,
	labels: ParameterLabels,
	pose: ParameterPoseState,
	viewState: ParametersViewState,
	session: EditorSession?,
	createMenuItems: List<MenuItem>,
	modifier: Modifier = Modifier,
) {
	// The callbacks below hold the two parameters, or the row through state, never the row itself: the
	// list builds its rows again whenever the panel recomposes, and a callback holding the row would be a
	// new one each time.
	val horizontal = row.horizontal
	val vertical = row.vertical
	val currentRow by rememberUpdatedState(row)
	val shownX by pose.rememberShownValue(horizontal)
	val shownY by pose.rememberShownValue(vertical)
	val rangeOpen = viewState.openRangeEditors[horizontal.id] == true
	// Double-clicking an axis name renames it, as the menu's per-axis entry does.  Either name toggles
	// the one range editor the pad keys on its upper axis, so that is the one a double click gives back.
	val horizontalRename = parameterRenameSlot(horizontal.id, rangeEditorId = horizontal.id, viewState, session)
	val verticalRename = parameterRenameSlot(vertical.id, rangeEditorId = horizontal.id, viewState, session)
	// Unlink splits the pad back into two sliders; a document edit, allowed
	// in Edit mode like range edits.
	val link =
		if (session != null) {
			ParameterLinkAction(LocalUmamoIcons.linked, labels.unlink) {
				session.setParameterLink(horizontal.id, vertical.id, linked = false)
			}
		} else {
			null
		}
	ContextMenuArea(
		items = parameterPadMenuItems(horizontal, vertical, labels, session, viewState, createMenuItems),
		modifier = modifier,
	) {
		ParameterIsland(
			modifier = Modifier.fillMaxWidth(),
			selected = selected,
			// A pad targets BOTH its axes - through parameterSelectionOf, the ONE place
			// the policy lives, shared with the grip handle and the single island.
			onSelect = { parameterSelectionOf(currentRow)?.let { target -> session?.setParameterSelection(target) } },
		) {
			ParameterPad2D(
				horizontal = horizontal,
				vertical = vertical,
				xValue = shownX,
				yValue = shownY,
				labels = labels,
				rangeOpen = rangeOpen,
				onToggleRange = {
					viewState.openRangeEditors[horizontal.id] = !rangeOpen
				},
				horizontalRename = horizontalRename,
				verticalRename = verticalRename,
				onPreview = { id, newValue -> pose.preview(id, newValue) },
				onCommitGesture = { ids -> pose.commitGesture(ids) },
				onCommitValue = { id, newValue -> pose.commitValue(id, newValue) },
				link = link,
			)
			if (rangeOpen) {
				// One range row per axis, each captioned with its axis name (user
				// data, never localized).
				RangeAxisLabel(horizontal.name)
				RangeFieldsRow(
					parameter = horizontal,
					onSetRange = { min, default, max ->
						session?.setParameterRange(horizontal.id, min, default, max)
					},
				)
				RangeAxisLabel(vertical.name)
				RangeFieldsRow(
					parameter = vertical,
					onSetRange = { min, default, max ->
						session?.setParameterRange(vertical.id, min, default, max)
					},
				)
			}
		}
	}
}

/**
 * Builds the inline rename of one parameter's name: open while the panel's rename target is this
 * parameter, and closed by a commit or a cancel alike.
 *
 * Composable so its callbacks are the ones the compiler keeps across recompositions; a plain function
 * would hand back new lambdas on every call, and a slot that never equals its predecessor.
 *
 * @param ParameterId parameterId The parameter whose name the slot edits.
 * @param ParameterId rangeEditorId The parameter the name's island keys its range editor on, which a
 *   single click on the name toggles.
 * @param ParametersViewState viewState The panel's view state, which holds the rename target.
 * @param EditorSession? session The editing session the commit writes through, or null with none.
 * @return ParameterRenameSlot The rename as the row's name sees it.
 */
@Composable
private fun parameterRenameSlot(
	parameterId: ParameterId,
	rangeEditorId: ParameterId,
	viewState: ParametersViewState,
	session: EditorSession?,
): ParameterRenameSlot =
	ParameterRenameSlot(
		renaming = viewState.renamingParameterId == parameterId,
		onStart = {
			// The double click's first press already toggled the range editor; toggle it back, so that
			// renaming a parameter does not also open or close its ranges.  From the LIVE value, as the
			// group header does with its fold: a value captured in composition can be one press stale.
			viewState.openRangeEditors[rangeEditorId] = viewState.openRangeEditors[rangeEditorId] != true
			viewState.renamingParameterId = parameterId
		},
		onCommit = { newName ->
			session?.renameParameter(parameterId, newName)
			viewState.renamingParameterId = null
		},
		onCancel = { viewState.renamingParameterId = null },
	)