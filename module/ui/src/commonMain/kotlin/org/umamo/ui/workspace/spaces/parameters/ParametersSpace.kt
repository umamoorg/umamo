package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.umamo.edit.ParameterMoveSubject
import org.umamo.edit.ParameterSelection
import org.umamo.edit.Selection
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.kit.menu.ContextMenuArea
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.resources.*
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.rowdrag.RowDragController
import org.umamo.ui.workspace.rowdrag.RowDragLabel
import org.umamo.ui.workspace.rowdrag.parkCancelOnSeam

/*
 * The Parameters space.  This file is the panel body's entry point; the rest of the package, by role:
 *
 *   ParametersHeaderControls.kt   the area header's controls
 *   ParametersViewState.kt        the view state the header and the body share
 *   ParameterRowViews.kt          the frame every row shares, and one composable per row kind
 *   ParameterControls.kt          the island, the slider, the 2D pad, the value row, and the range fields
 *   ParameterGroupHeader.kt       the group rail and its rename field
 *   ParameterRowDrag.kt           the grip, the drop line, and the drop dispatch
 *   ParameterMenus.kt             the entries of every menu the panel and its header show
 *   ParameterLabels.kt            the localized chrome
 *   ParameterPoseState.kt         the displayed values and the effects that keep them on the pose
 *
 * and, free of Compose so commonTest pins them directly:
 *
 *   ParameterPose.kt              the pose writes and their Edit-mode lock, and the follow rule
 *   ParameterRows.kt              the row model and what the panel asks of it
 *   ParametersDropRules.kt        where a dragged row may land
 *   ParameterObjectBinding.kt     which parameters drive a selection
 */

/**
 * The parameter cockpit: one control per animatable parameter, organized into the model's collapsible
 * groups, a linked pair rendering as one 2D pad and every other parameter as a slider.
 *
 * Scrubbing is undoable: a drag streams preview frames to the renderer through [LocalLiveParams] and commits
 * one undo step on release.  This composable is the wiring: it derives the rows, holds the list and the drag
 * state, and hands each row to [ParameterRowView].
 *
 * @param AreaScope scope The hosting area's scope carrying the panel's view state.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun ParametersSpace(scope: AreaScope, modifier: Modifier = Modifier) {
	val puppet = LocalPuppet.current
	val liveParams = LocalLiveParams.current
	val session = LocalEditorSession.current

	if (puppet == null) {
		// No document: the bare panel fill is the empty state (no load hint).
		return
	}

	val viewState = scope.spaceState(PARAMETERS_VIEW_STATE_KEY) { ParametersViewState() }
	val pose = rememberParameterPoseState(puppet, liveParams, session)

	// Group expand/collapse state, keyed by group id; absent entries fall back to the group's saved
	// initiallyOpen. Parked on [viewState] rather than remember(puppet) so it survives every model edit -
	// keying it to the puppet would reset the map, and so collapse every group, on any edit at all,
	// including a link write inside the group itself.
	val expandedGroups = viewState.expandedGroups

	// Puppet-derived facts reused every rebuild: which parameters are animatable, and the link pairing.
	val linkInfo = remember(puppet) { buildLinkInfo(puppet) }
	val parameterById = remember(puppet) { puppet.parameters.associateBy { it.id } }
	// Per-parameter key marks (circle grid keys / square blend keys) for the sliders; one graph pass,
	// recomputed only when the model changes.
	val keyMarksByParameter = remember(puppet) { puppet.parameterKeyMarks() }

	// The "affects the selected object" filter: when it is on and something is selected, restrict the panel
	// to the parameters that drive the selection (effective, through the deformer chain). Inert with no
	// selection, so the panel is never mysteriously blank. Recomputed only on a puppet / selection / flag /
	// query change.
	val selection = LocalSelection.current?.selection ?: Selection()
	// The keyform-authoring target, shared across areas via the session - a keyform sheet in another area
	// follows whatever is picked here.
	val parameterSelection by remember(session) { session?.parameterSelection ?: MutableStateFlow(ParameterSelection()) }.collectAsState()
	// The header search's text, applied as a second filter over the same set. Trimmed once here so the
	// memo key and the match both read the same string.
	val searchQuery = viewState.query.trim()
	val visibleParamIds =
		remember(puppet, selection, viewState.showOnlySelected, searchQuery, viewState.renamingParameterId) {
			visibleParameterIds(puppet, selection, viewState.showOnlySelected, searchQuery, viewState.renamingParameterId)
		}

	// Built each recomposition so a group toggle reflects immediately; reading [expandedGroups] here
	// registers the snapshot reads that drive the rebuild.  While a search is running every group renders
	// open, so a match inside a collapsed one is still reachable.
	val rows =
		buildParameterRows(
			puppet,
			linkInfo,
			parameterById,
			expandedGroups,
			visibleParamIds,
			forceExpanded = viewState.searching,
			namingGroupId = viewState.renamingGroupId,
		)
	val labels = parameterLabels()
	val listState = rememberLazyListState()
	HoldListTopEffect(listState, firstRowKey = rows.firstOrNull()?.let { row -> rowKey(row) })

	// A newly created group or parameter is prepended at the top and immediately opened for inline rename;
	// scroll its row into view when it is not already visible (the created item can otherwise land just
	// above the viewport when the list was scrolled down). Mirrors the reveal-on-select effect in the
	// outliner / history spaces; the visibility guard keeps renaming an already-visible item from jumping.
	// The row is AWAITED through snapshotFlow rather than looked up once: a created parameter reaches this
	// composition through the model StateFlow's collector, which can land a recomposition after the
	// renaming id was set - a one-shot look at the captured rows would find nothing and, with the effect's
	// keys unchanged, would never look again.  The prepended row also sits above a scrolled-down viewport,
	// where its lazy item cannot even open the rename field until this scroll composes it.
	val currentRows = rememberUpdatedState(rows)
	LaunchedEffect(viewState.renamingGroupId, viewState.renamingParameterId) {
		val renamingGroupId = viewState.renamingGroupId
		val renamingParameterId = viewState.renamingParameterId

		if (renamingGroupId == null && renamingParameterId == null) {
			return@LaunchedEffect
		}

		val index =
			snapshotFlow { indexOfRenamedRow(currentRows.value, renamingGroupId, renamingParameterId) }
				.first { candidate -> candidate >= 0 }

		if (listState.layoutInfo.visibleItemsInfo.none { visible -> visible.index == index }) {
			listState.animateScrollToItem(index)
		}
	}

	// Transient drag-and-drop state, per panel instance. While a drag is in flight the panel parks its
	// cancel on the shell's seam so Escape aborts the drag (the grip is pointerInput and never focusable,
	// so the shell root keeps the keyboard and its Escape reaches the seam).
	val dragController = remember { RowDragController<ParameterMoveSubject>() }
	dragController.parkCancelOnSeam()

	// The entries every parameter menu ends with, and the whole of the panel background's menu, so the
	// first parameter or group can be made with no row to right-click.
	val createMenuItems =
		createParameterMenuItems(labels, puppet.runtimeTarget, session, viewState) + newParameterGroupMenuItem(labels, session, viewState)
	// One release handler for every row's grip: a drag reads the panel's drag state, not the row it began on.
	// The rows and the model are read through state at the release, so the handler stays one object while
	// the panel builds new rows and an edit publishes a new model, and no grip runs again for it.
	val currentPuppet = rememberUpdatedState(puppet)
	val onDrop = {
		performParameterDrop(dragController, currentRows.value, currentPuppet.value, session) { groupId ->
			expandedGroups[groupId] = true
		}
	}

	// A right-click anywhere on the panel body (empty space or a row) offers Add Parameter / New Group, so
	// the first parameter or group can be made without a row to right-click; ContextMenuArea supplies the
	// body Box.
	ContextMenuArea(
		items = createMenuItems,
		modifier = modifier.fillMaxSize(),
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			// The list scrolls under an overlay scrollbar; the Box carries the column weight so the bar
			// spans the scrolling region. Reset All lives in the area header (parametersHeaderControls).
			Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
				LazyColumn(
					state = listState,
					modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
					// The gap between islands is the row separation (no dividers).
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					// Stable per-row keys: expanding/collapsing a group mutates the row list, so without keys
					// Compose would reuse slots by position and a row's remembered state (a slider's gesture
					// detector, a field's text/focus) would bind to the wrong parameter.
					items(rows, key = { row -> rowKey(row) }, contentType = { row -> row::class }) { row ->
						ParameterRowView(
							row = row,
							keyMarksByParameter = keyMarksByParameter,
							parameterSelection = parameterSelection,
							labels = labels,
							pose = pose,
							viewState = viewState,
							session = session,
							createMenuItems = createMenuItems,
							dragController = dragController,
							onDrop = onDrop,
						)
					}
				}
				VerticalScrollbarOverlay(listState)
			}
		}
		// The floating drag ghost follows the cursor over everything.  What is read here is which row is in
		// hand, which a drag changes as it starts and as it ends; where the pointer is, the ghost asks for
		// itself.
		val draggingKey = dragController.draggingKey

		if (draggingKey != null) {
			RowDragLabel(label = draggedRowLabel(rows, draggingKey), pointerInWindow = { dragController.pointerInWindow })
		}
	}
}

/** The first row a list was last seen to hold, by its key. */
private class FirstRowMemo(var key: String?)

/**
 * Keeps a list that rests at its very top resting there when a different row becomes its first.
 *
 * A lazy list holds its place on the row that was first in view, so that rows changing above a list
 * scrolled part way down do not move what the rigger is looking at.  For a list at its top that same
 * rule puts a row arriving above the first one out of sight, above a list that looks unscrolled: a
 * created row, a row restored by undo, a row dragged to the top.  A list scrolled anywhere else is left
 * to hold its place.
 *
 * A side effect, which runs once the rows are composed and before the list measures them, so the
 * position it reads is still the one the old rows were laid out at.
 *
 * @param LazyListState listState The list's state.
 * @param String? firstRowKey The key of the first row the list now holds, or null for an empty list.
 */
@Composable
private fun HoldListTopEffect(listState: LazyListState, firstRowKey: String?) {
	val lastSeen = remember { FirstRowMemo(firstRowKey) }
	SideEffect {
		if (lastSeen.key != firstRowKey) {
			lastSeen.key = firstRowKey

			if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
				listState.requestScrollToItem(0)
			}
		}
	}
}