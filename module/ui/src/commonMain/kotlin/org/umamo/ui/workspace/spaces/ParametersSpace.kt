package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterMoveSubject
import org.umamo.edit.RowDropBand
import org.umamo.edit.Selection
import org.umamo.edit.createParameter
import org.umamo.edit.createParameterGroup
import org.umamo.edit.deleteParameterGroup
import org.umamo.edit.moveParameterRow
import org.umamo.edit.renameParameter
import org.umamo.edit.renameParameterGroup
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.kit.ContextMenuArea
import org.umamo.ui.kit.InlineRenameField
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.NumberField
import org.umamo.ui.kit.Pad2D
import org.umamo.ui.kit.Slider
import org.umamo.ui.kit.SliderKeyMark
import org.umamo.ui.kit.SliderKeyShape
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.kit.button.IconButton
import org.umamo.ui.kit.button.IconButtonAppearance
import org.umamo.ui.kit.singleOrDoubleClick
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.model.LocalSelection
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoShapes
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.LocalRowDragCancel
import kotlin.math.abs

/** A value within this of a parameter's default counts as "at default" - no reset glyph, no drift. */
private const val RESET_EPSILON = 1e-4f

/** Indentation applied per group nesting level, matching the outliner's indent idiom. */
private val INDENT_PER_DEPTH = 12.dp

/**
 * A generous clamp for the range-editor numeric fields. The three fields (min / default / max) are
 * interdependent, so each is clamped only to this wide sanity bound and the session normalizes the
 * triple (min <= max, default within range); a tight per-field clamp would block typing a default beyond
 * the not-yet-committed max.
 */
private val RANGE_FIELD_LIMIT = -1_000_000f..1_000_000f

/**
 * The parameter cockpit: one control per animatable parameter, organised into the model's collapsible
 * groups (Cubism's CParameterGroup tree). A LINKED ("combined") pair renders as one 2D pad; every other
 * animatable parameter is a slider. Each parameter sits in its own rounded island (the visual
 * separation); group headers render as recessed rounded rails between them. Each row carries a numeric
 * entry field and, when its value is off its default, a reset glyph; a "Reset All" button returns the
 * whole rig to its neutral pose.
 *
 * Scrubbing is undoable: a slider / pad drag streams transient preview frames straight to the renderer
 * (via [LocalLiveParams]) and commits one undo step on release, so a whole gesture is a single Ctrl+Z and
 * an undo re-poses the viewport (the session's pose drives both the renderer and these sliders). Clicking
 * a parameter's name (or its leading chevron) opens the range editor inside that island - minimum /
 * default / maximum, the document-level edit, distinct from scrubbing the live value - and any number of
 * islands can be open at once. The open set lives on the hosting [AreaScope], so it survives switching
 * the space away and back. Models without groups fall back to a flat list.
 *
 * パラメータ操作盤。スライダー／2D パッド／数値入力で値をスクラブ（取り消し可能、ジェスチャ単位で1段）。
 * 各パラメータは角丸の島として区切られ、名前（または先頭のシェブロン）をクリックするとその島の中に
 * 範囲（最小・既定・最大）編集が開く。複数の島を同時に開ける（モーダルなし）。
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
	// Local control state seeded from the live values (or defaults); writes publish to the renderer.
	val values =
		remember(puppet) {
			mutableStateMapOf<ParameterId, Float>().apply {
				puppet.parameters.forEach { parameter ->
					put(parameter.id, liveParams?.values?.get(parameter.id) ?: parameter.default)
				}
			}
		}
	// Reseed the sliders from the session pose when it changes out from under us - an undo / redo restores
	// a prior pose, and these controls must follow it. Mid-drag previews update [values] directly (the
	// session pose does not move until commit), so this never fights a live scrub; the guard skips
	// unchanged entries so a commit (pose == values already) triggers no writes. An empty fallback flow
	// keeps the collect unconditional when no session is present.
	val pose by remember(session) { session?.pose ?: MutableStateFlow(emptyMap<ParameterId, Float>()) }.collectAsState()
	LaunchedEffect(pose) {
		pose.forEach { (id, value) ->
			if (values[id] != value) {
				values[id] = value
			}
		}
	}

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

	// Parameters are LOCKED while in Edit mode: Edit mode edits the neutral state of the base mesh and is
	// pinned to the neutral pose (the viewport shows rest via a display-only override), so no scrub may
	// move the session pose out from under it. The lock gates every write path below; the sliders keep
	// displaying the untouched Object-mode pose, which returns to the viewport on exit.
	val editorMode by remember(session) { session?.mode ?: MutableStateFlow(EditorMode.Object) }.collectAsState()
	val parametersLocked = editorMode == EditorMode.Edit

	/** Previews a value: updates local state and streams it to the renderer, recording no undo step. */
	fun previewValue(id: ParameterId, newValue: Float) {
		if (parametersLocked) {
			return
		}
		values[id] = newValue
		liveParams?.preview(id, newValue)
	}

	/** Commits the current pose as one undo step, ending a scrub gesture over [ids]. */
	fun commitGesture(ids: Set<ParameterId>) {
		if (parametersLocked) {
			return
		}
		liveParams?.commit(ids)
	}

	/** A discrete value edit (a typed field, a reset): preview then commit it as one step. */
	fun commitValue(id: ParameterId, newValue: Float) {
		previewValue(id, newValue)
		commitGesture(setOf(id))
	}

	// The "affects the selected object" filter: when it is on and something is selected, restrict the panel
	// to the parameters that drive the selection (effective, through the deformer chain). Inert with no
	// selection, so the panel is never mysteriously blank. Recomputed only on a puppet / selection / flag change.
	val selection = LocalSelection.current?.selection ?: Selection()
	val visibleParamIds =
		remember(puppet, selection, viewState.showOnlySelected) {
			if (viewState.showOnlySelected && !selection.isEmpty) {
				effectiveParameterIds(puppet, selection)
			} else {
				null
			}
		}

	// Built each recomposition so a group toggle (or a value change) reflects immediately; reading
	// [expandedGroups] / [values] here registers the snapshot reads that drive the rebuild.
	val rows = buildParameterRows(puppet, linkInfo, parameterById, expandedGroups, visibleParamIds)
	val resetLabel = stringResource(Res.string.parameter_reset)
	val rangeToggleLabel = stringResource(Res.string.parameter_range_section)
	val linkLabel = stringResource(Res.string.parameter_link)
	val unlinkLabel = stringResource(Res.string.parameter_unlink)
	val newGroupLabel = stringResource(Res.string.parameter_new_group)
	val renameLabel = stringResource(Res.string.parameter_menu_rename)
	val deleteGroupLabel = stringResource(Res.string.parameter_menu_delete_group)
	val addKeyFormParamLabel = stringResource(Res.string.parameter_menu_add_keyform)
	val addBlendShapeParamLabel = stringResource(Res.string.parameter_menu_add_blendshape)
	val deleteParamLabel = stringResource(Res.string.parameter_menu_delete)
	val defaultGroupName = stringResource(Res.string.parameter_group_default_name)
	val defaultParameterName = stringResource(Res.string.parameter_default_name)
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	val listState = rememberLazyListState()
	val reorderLabel = stringResource(Res.string.parameter_reorder_handle)

	// A newly created group or parameter is prepended at the top and immediately opened for inline rename;
	// scroll its row into view when it is not already visible (the created item can otherwise land just
	// above the viewport when the list was scrolled down). Mirrors the reveal-on-select effect in the
	// outliner / history spaces; the visibility guard keeps renaming an already-visible item from jumping.
	LaunchedEffect(viewState.renamingGroupId, viewState.renamingParameterId) {
		val renamingGroupId = viewState.renamingGroupId
		val renamingParameterId = viewState.renamingParameterId
		if (renamingGroupId == null && renamingParameterId == null) {
			return@LaunchedEffect
		}
		val index =
			rows.indexOfFirst { row ->
				when (row) {
					is ParameterRow.GroupHeader -> row.groupId == renamingGroupId
					is ParameterRow.Single -> row.parameter.id == renamingParameterId
					is ParameterRow.Pair2D -> row.horizontal.id == renamingParameterId || row.vertical.id == renamingParameterId
				}
			}
		if (index < 0) {
			return@LaunchedEffect
		}
		if (listState.layoutInfo.visibleItemsInfo.none { visible -> visible.index == index }) {
			listState.animateScrollToItem(index)
		}
	}

	// Transient drag-and-drop state, per panel instance. While a drag is in flight the panel parks its
	// cancel on the shell's seam so Escape aborts the drag (the grip is pointerInput, never focusable,
	// so it does not reintroduce the link-icon focus bug).
	val dragController = remember { RowDragController<ParameterMoveSubject>() }
	val dragCancelSeam = LocalRowDragCancel.current
	DisposableEffect(dragController.isDragging) {
		if (dragController.isDragging) {
			dragCancelSeam.cancel = { dragController.cancel() }
		}
		onDispose { dragCancelSeam.cancel = null }
	}

	// The "add" items every parameter menu ends with: Add Key Form / Add Blend Shape parameter, and New
	// Group. Each creates a document edit and opens the created item for inline rename. Shared by the
	// panel-background menu (so the first parameter / group can be made with no row to right-click) and
	// each row's context menu. The two parameter kinds mirror the header's Add Parameter dropdown.
	val createMenuItems =
		listOf(
			MenuItem.Action(
				label = addKeyFormParamLabel,
				onSelect = {
					session?.let {
						viewState.renamingParameterId = it.createParameter(defaultParameterName, ParameterKind.NORMAL)
					}
				},
				enabled = session != null,
			),
			MenuItem.Action(
				label = addBlendShapeParamLabel,
				onSelect = {
					session?.let {
						viewState.renamingParameterId = it.createParameter(defaultParameterName, ParameterKind.BLEND_SHAPE)
					}
				},
				enabled = session != null,
			),
			MenuItem.Action(
				label = newGroupLabel,
				onSelect = { session?.let { viewState.renamingGroupId = it.createParameterGroup(defaultGroupName) } },
				enabled = session != null,
			),
		)

	// A right-click anywhere on the panel body (empty space or a row) offers Add Parameter / New Group, so
	// the first parameter or group can be made without a row to right-click; ContextMenuArea supplies the
	// body Box.
	ContextMenuArea(
		items = createMenuItems,
		modifier = modifier.fillMaxSize(),
	) {
		Column(modifier = Modifier.fillMaxSize()) {
			// The list scrolls under an overlay scrollbar; the Box carries the column weight so the bar
			// spans the scrolling region. Reset All lives in the area header (ParametersHeaderControls).
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
						val key = rowKey(row)
						// Drop this row's window bounds when it scrolls off, so the drop hit-test never
						// targets an invisible row.
						DisposableEffect(key) {
							onDispose { dragController.clearBounds(key) }
						}
						// The band this row would receive if a drop landed now (null unless it is the drag
						// target), read from snapshot state so the indicator tracks the moving pointer.
						val dropBand: RowDropBand? =
							if (dragController.isDragging && dragController.dropTargetKey == key) {
								dragController.draggedPayload?.let { subject ->
									parameterDropBandFor(
										subject,
										row,
										row.depth == 0,
										dragController.dropTargetFraction ?: 0.5f,
									)
								}
							} else {
								null
							}
						val isDragged = dragController.draggingKey == key
						Row(
							// Report the OUTER row's bounds (not the inner island's) so the drop band math
							// measures the whole row. The group-depth indent sits here, beside the grip.
							modifier =
								Modifier
									.fillMaxWidth()
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
								gripLabel = reorderLabel,
								subject = parameterMoveSubjectOf(row),
								rowKey = key,
								dragController = dragController,
								onDrop = {
									performParameterDrop(dragController, rows, puppet, session) { groupId ->
										expandedGroups[groupId] = true
									}
								},
							)
							when (row) {
								is ParameterRow.GroupHeader -> {
									val nesting = dropBand == RowDropBand.Into
									val renaming = viewState.renamingGroupId == row.groupId
									ContextMenuArea(
										items =
											listOf(
												MenuItem.Action(
													label = newGroupLabel,
													onSelect = {
														session?.let {
															viewState.renamingGroupId =
																it.createParameterGroup(defaultGroupName)
														}
													},
													enabled = session != null,
												),
												MenuItem.Action(
													label = renameLabel,
													onSelect = { viewState.renamingGroupId = row.groupId },
													enabled = session != null,
												),
												MenuItem.Separator,
												MenuItem.Action(
													label = deleteGroupLabel,
													onSelect = { session?.deleteParameterGroup(row.groupId) },
													enabled = session != null,
												),
											),
										modifier = Modifier.weight(1f),
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
												onToggle = { expandedGroups[row.groupId] = !(expandedGroups[row.groupId] ?: row.expanded) },
												onStartRename = {
													// The double-click's first press already fired the immediate single-click
													// toggle; flip the group back so renaming does not also open / close it.
													// Flip the LIVE map value, not the captured row's - the capture can be a
													// recomposition stale under a fast double click, and flipping a stale
													// value re-applies the first toggle instead of undoing it.
													expandedGroups[row.groupId] = !(expandedGroups[row.groupId] ?: row.expanded)
													viewState.renamingGroupId = row.groupId
												},
											)
										}
									}
								}

								is ParameterRow.Single -> {
									val parameter = row.parameter
									val linkCandidateId = row.linkCandidateId
									val rangeOpen = viewState.openRangeEditors[parameter.id] == true
									ContextMenuArea(
										items =
											listOf(
												MenuItem.Action(
													label = renameLabel,
													onSelect = { viewState.renamingParameterId = parameter.id },
													enabled = session != null,
												),
												MenuItem.Action(
													label = deleteParamLabel,
													onSelect = { session?.deleteParameter(parameter.id) },
													enabled = session != null,
												),
												MenuItem.Separator,
											) + createMenuItems,
										modifier = Modifier.weight(1f),
									) {
										ParameterIsland(modifier = Modifier.fillMaxWidth()) {
											ParameterSlider(
												parameter = parameter,
												keyMarks = keyMarksByParameter[parameter.id],
												value = values[parameter.id] ?: parameter.default,
												resetLabel = resetLabel,
												rangeToggleLabel = rangeToggleLabel,
												rangeOpen = rangeOpen,
												onToggleRange = {
													viewState.openRangeEditors[parameter.id] = !rangeOpen
												},
												renaming = viewState.renamingParameterId == parameter.id,
												onStartRename = { viewState.renamingParameterId = parameter.id },
												onCommitRename = { newName ->
													session?.renameParameter(parameter.id, newName)
													viewState.renamingParameterId = null
												},
												onCancelRename = { viewState.renamingParameterId = null },
												onPreview = { newValue -> previewValue(parameter.id, newValue) },
												onCommitGesture = { commitGesture(setOf(parameter.id)) },
												onCommitValue = { newValue -> commitValue(parameter.id, newValue) },
												// Link editing is a document edit, not a pose write, so it deliberately
												// bypasses the Edit-mode parameter lock (the same policy as range edits).
												linkIcon = if (linkCandidateId != null && session != null) LocalUmamoIcons.unlinked else null,
												linkContentDescription = linkLabel,
												onLinkClick =
													if (linkCandidateId != null && session != null) {
														{
															session.setParameterLink(
																parameter.id,
																linkCandidateId,
																linked = true,
															)
														}
													} else {
														null
													},
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

								is ParameterRow.Pair2D -> {
									// The pad island keys its open state on the horizontal (upper) member.
									val rangeOpen = viewState.openRangeEditors[row.horizontal.id] == true
									ContextMenuArea(
										items =
											listOf(
												// A pad holds two axes, so rename / delete open per-axis submenus
												// (the axis names are user data); double-clicking a name renames it too.
												MenuItem.Submenu(
													label = renameLabel,
													items =
														listOf(
															MenuItem.Action(
																row.horizontal.name,
																onSelect = {
																	viewState.renamingParameterId = row.horizontal.id
																},
																enabled = session != null,
															),
															MenuItem.Action(
																row.vertical.name,
																onSelect = {
																	viewState.renamingParameterId = row.vertical.id
																},
																enabled = session != null,
															),
														),
													enabled = session != null,
												),
												MenuItem.Submenu(
													label = deleteParamLabel,
													items =
														listOf(
															MenuItem.Action(
																row.horizontal.name,
																onSelect = { session?.deleteParameter(row.horizontal.id) },
																enabled = session != null,
															),
															MenuItem.Action(
																row.vertical.name,
																onSelect = { session?.deleteParameter(row.vertical.id) },
																enabled = session != null,
															),
														),
													enabled = session != null,
												),
												MenuItem.Separator,
											) + createMenuItems,
										modifier = Modifier.weight(1f),
									) {
										ParameterIsland(modifier = Modifier.fillMaxWidth()) {
											ParameterPad2D(
												horizontal = row.horizontal,
												vertical = row.vertical,
												xValue = values[row.horizontal.id] ?: row.horizontal.default,
												yValue = values[row.vertical.id] ?: row.vertical.default,
												resetLabel = resetLabel,
												rangeToggleLabel = rangeToggleLabel,
												rangeOpen = rangeOpen,
												onToggleRange = {
													viewState.openRangeEditors[row.horizontal.id] = !rangeOpen
												},
												renamingParameterId = viewState.renamingParameterId,
												onStartRename = { id -> viewState.renamingParameterId = id },
												onCommitRename = { id, newName ->
													session?.renameParameter(id, newName)
													viewState.renamingParameterId = null
												},
												onCancelRename = { viewState.renamingParameterId = null },
												onPreview = { id, newValue -> previewValue(id, newValue) },
												onCommitGesture = { ids -> commitGesture(ids) },
												onCommitValue = { id, newValue -> commitValue(id, newValue) },
												// Unlink splits the pad back into two sliders; a document edit, allowed
												// in Edit mode like range edits.
												linkIcon = if (session != null) LocalUmamoIcons.linked else null,
												linkContentDescription = unlinkLabel,
												onLinkClick =
													if (session != null) {
														{
															session.setParameterLink(
																row.horizontal.id,
																row.vertical.id,
																linked = false,
															)
														}
													} else {
														null
													},
											)
											if (rangeOpen) {
												// One range row per axis, each captioned with its axis name (user
												// data, never localized).
												RangeAxisLabel(row.horizontal.name)
												RangeFieldsRow(
													parameter = row.horizontal,
													onSetRange = { min, default, max ->
														session?.setParameterRange(row.horizontal.id, min, default, max)
													},
												)
												RangeAxisLabel(row.vertical.name)
												RangeFieldsRow(
													parameter = row.vertical,
													onSetRange = { min, default, max ->
														session?.setParameterRange(row.vertical.id, min, default, max)
													},
												)
											}
										}
									}
								}
							}
						}
					}
				}
				VerticalScrollbarOverlay(listState)
			}
		}
		// The floating drag ghost follows the cursor over everything (reuses the outliner's label).
		if (dragController.isDragging) {
			OutlinerDragLabel(
				label = draggedRowLabel(rows, dragController.draggingKey),
				cursorX = dragController.dragWindowX,
				cursorY = dragController.dragWindowY,
			)
		}
	}
}

/**
 * The rounded elevation island wrapping one parameter (slider or pad) and, while open, its range
 * editor. One visual step raised from the panel fill (headerBackground sits exactly one elevation
 * step above panelBackground in both schemes), rounded with the kit's medium shape. The group-depth
 * indent lives on the caller's row (beside the drag grip), so the island only carries [modifier].
 *
 * @param Modifier modifier The layout modifier (the caller supplies the row weight).
 * @param Function content The island's rows.
 */
@Composable
private fun ParameterIsland(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	Column(
		modifier =
			modifier
				.clip(shapes.medium)
				.background(colors.headerBackground, shape = shapes.medium)
				.border(
					width = 1.dp,
					color = colors.panelBorder,
					shape = shapes.medium,
				)
				.padding(horizontal = 6.dp, vertical = 6.dp),
		content = content,
	)
}

/**
 * One labelled slider for a parameter, clamped to its range, with a numeric entry field and (when the
 * value is off its default) a reset glyph. The slider drag previews live and commits one step on release;
 * the field / reset commit one step each.
 *
 * @param Parameter parameter        The parameter to scrub.
 * @param Float     value            The current value.
 * @param String    resetLabel       The localized accessible label for the reset glyph.
 * @param String    rangeToggleLabel The localized accessible label for the range chevron.
 * @param Boolean   rangeOpen        Whether this island's range editor is open (highlights the name).
 * @param Function  onToggleRange    Called when the name / chevron is clicked (toggles the range editor).
 * @param Boolean   renaming         Whether this parameter's name is being edited in place.
 * @param Function  onStartRename    Called on a double-click of the name to begin inline rename.
 * @param Function  onCommitRename   Called with the new name when the inline rename commits.
 * @param Function  onCancelRename   Called when the inline rename is abandoned.
 * @param Function  onPreview        Called per drag frame with the new value (transient, no undo step).
 * @param Function  onCommitGesture  Called on slider release / tap to commit the gesture as one step.
 * @param Function  onCommitValue    Called by the numeric field / reset with a discrete value to commit.
 * @param UmamoIcon linkIcon         The trailing link glyph, or null when the row offers no link edit.
 * @param String    linkContentDescription The localized accessible label for the link glyph.
 * @param Function  onLinkClick      Called when the link glyph is clicked, or null when hidden.
 */
@Composable
private fun ParameterSlider(
	parameter: Parameter,
	keyMarks: ParameterKeyMarks?,
	value: Float,
	resetLabel: String,
	rangeToggleLabel: String,
	rangeOpen: Boolean,
	onToggleRange: () -> Unit,
	renaming: Boolean,
	onStartRename: () -> Unit,
	onCommitRename: (String) -> Unit,
	onCancelRename: () -> Unit,
	onPreview: (Float) -> Unit,
	onCommitGesture: () -> Unit,
	onCommitValue: (Float) -> Unit,
	linkIcon: UmamoIcon?,
	linkContentDescription: String?,
	onLinkClick: (() -> Unit)?,
) {
	ParameterValueRow(
		name = parameter.name,
		value = value,
		default = parameter.default,
		range = parameter.min..parameter.max,
		resetLabel = resetLabel,
		rangeToggleLabel = rangeToggleLabel,
		showRangeToggle = true,
		showLeadingSlot = true,
		rangeOpen = rangeOpen,
		onToggleRange = onToggleRange,
		renaming = renaming,
		onStartRename = onStartRename,
		onCommitRename = onCommitRename,
		onCancelRename = onCancelRename,
		onCommitValue = onCommitValue,
		linkIcon = linkIcon,
		linkContentDescription = linkContentDescription,
		onLinkClick = onLinkClick,
	)
	// Circle marks for grid keys, square marks for blend-shape keys; the thumb takes the parameter's own
	// kind (a blend-shape parameter reads as a square knob even before it has any keys).
	val sliderKeyMarks =
		remember(keyMarks) {
			buildList {
				keyMarks?.gridKeys?.forEach { keyValue -> add(SliderKeyMark(keyValue, SliderKeyShape.Circle)) }
				keyMarks?.blendKeys?.forEach { keyValue -> add(SliderKeyMark(keyValue, SliderKeyShape.Square)) }
			}
		}
	Slider(
		value = value,
		onValueChange = onPreview,
		onValueChangeFinished = onCommitGesture,
		valueRange = parameter.min..parameter.max,
		keyMarks = sliderKeyMarks,
		thumbShape = if (parameter.kind == ParameterKind.BLEND_SHAPE) SliderKeyShape.Square else SliderKeyShape.Circle,
		modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
	)
}

/**
 * One 2D pad for a LINKED parameter pair: a labelled numeric/reset row per axis above a [Pad2D] that
 * scrubs both at once. [horizontal] is the X axis, [vertical] the Y axis. The pad drag previews both live
 * and commits one step (covering both parameters) on release. The range chevron sits on the horizontal
 * (upper) axis row; clicking either axis name toggles the island's shared range editor.
 *
 * @param Parameter horizontal       The X-axis parameter.
 * @param Parameter vertical         The Y-axis parameter.
 * @param Float     xValue           The current X value.
 * @param Float     yValue           The current Y value.
 * @param String    resetLabel       The localized accessible label for the reset glyphs.
 * @param String    rangeToggleLabel The localized accessible label for the range chevron.
 * @param Boolean   rangeOpen        Whether this island's range editor is open.
 * @param Function  onToggleRange    Called when an axis name / the chevron is clicked.
 * @param ParameterId? renamingParameterId The axis id currently being renamed in place, or null.
 * @param Function  onStartRename    Called with an axis id on a double-click of that axis name.
 * @param Function  onCommitRename   Called with (axis id, new name) when an inline rename commits.
 * @param Function  onCancelRename   Called when an inline rename is abandoned.
 * @param Function  onPreview        Called per drag frame with (id, value) for each axis (transient).
 * @param Function  onCommitGesture  Called on pad release to commit both axes as one step.
 * @param Function  onCommitValue    Called by an axis field / reset with (id, value) to commit one step.
 * @param UmamoIcon linkIcon         The unlink glyph shown on the horizontal (upper) axis row - the
 *                                   link "points at the parameter below" so it sits on the upper member.
 * @param String    linkContentDescription The localized accessible label for the unlink glyph.
 * @param Function  onLinkClick      Called when the unlink glyph is clicked, or null when hidden.
 */
@Composable
private fun ParameterPad2D(
	horizontal: Parameter,
	vertical: Parameter,
	xValue: Float,
	yValue: Float,
	resetLabel: String,
	rangeToggleLabel: String,
	rangeOpen: Boolean,
	onToggleRange: () -> Unit,
	renamingParameterId: ParameterId?,
	onStartRename: (ParameterId) -> Unit,
	onCommitRename: (ParameterId, String) -> Unit,
	onCancelRename: () -> Unit,
	onPreview: (ParameterId, Float) -> Unit,
	onCommitGesture: (Set<ParameterId>) -> Unit,
	onCommitValue: (ParameterId, Float) -> Unit,
	linkIcon: UmamoIcon?,
	linkContentDescription: String?,
	onLinkClick: (() -> Unit)?,
) {
	val colors = LocalUmamoColors.current
	// Both axis names toggle the SAME island range editor, so the disclosure chevron is shared and
	// vertically centred across the two axis rows rather than pinned to the first. It is pulled out of the
	// per-row leading slot (both rows pass showLeadingSlot = false, supplying the indent from here) and
	// kept keyboard-focus-safe like the slider's inline toggle.
	// IntrinsicSize.Min gives the row the height of its tallest child (the two-axis Column), so the shared
	// chevron can fillMaxHeight to span both names top-to-bottom - a tall, wide hit target rather than a
	// 12.dp dot pinned to the first row.
	Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
		val chevron =
			if (rangeOpen) {
				LocalUmamoIcons.chevronDown
			} else {
				LocalUmamoIcons.chevronRight
			}
		Box(
			modifier =
				Modifier
					.fillMaxHeight()
					.width(24.dp)
					.focusProperties { canFocus = false }
					.clickable(onClick = onToggleRange),
			contentAlignment = Alignment.Center,
		) {
			Canvas(modifier = Modifier.size(12.dp).semantics { contentDescription = rangeToggleLabel }) {
				drawIcon(chevron, colors.textMuted)
			}
		}
		Column(modifier = Modifier.weight(1f)) {
			ParameterValueRow(
				name = horizontal.name,
				value = xValue,
				default = horizontal.default,
				range = horizontal.min..horizontal.max,
				resetLabel = resetLabel,
				rangeToggleLabel = rangeToggleLabel,
				showRangeToggle = false,
				showLeadingSlot = false,
				rangeOpen = rangeOpen,
				onToggleRange = onToggleRange,
				renaming = renamingParameterId == horizontal.id,
				onStartRename = { onStartRename(horizontal.id) },
				onCommitRename = { newName -> onCommitRename(horizontal.id, newName) },
				onCancelRename = onCancelRename,
				onCommitValue = { newX -> onCommitValue(horizontal.id, newX) },
				linkIcon = linkIcon,
				linkContentDescription = linkContentDescription,
				onLinkClick = onLinkClick,
			)
			ParameterValueRow(
				name = vertical.name,
				value = yValue,
				default = vertical.default,
				range = vertical.min..vertical.max,
				resetLabel = resetLabel,
				rangeToggleLabel = rangeToggleLabel,
				showRangeToggle = false,
				showLeadingSlot = false,
				rangeOpen = rangeOpen,
				onToggleRange = onToggleRange,
				renaming = renamingParameterId == vertical.id,
				onStartRename = { onStartRename(vertical.id) },
				onCommitRename = { newName -> onCommitRename(vertical.id, newName) },
				onCancelRename = onCancelRename,
				onCommitValue = { newY -> onCommitValue(vertical.id, newY) },
				linkIcon = null,
				linkContentDescription = null,
				onLinkClick = null,
			)
		}
	}
	Pad2D(
		xValue = xValue,
		yValue = yValue,
		onChange = { newX, newY ->
			onPreview(horizontal.id, newX)
			onPreview(vertical.id, newY)
		},
		onChangeFinished = { onCommitGesture(setOf(horizontal.id, vertical.id)) },
		xRange = horizontal.min..horizontal.max,
		yRange = vertical.min..vertical.max,
		modifier = Modifier.padding(10.dp).fillMaxWidth(),
	)
}

/**
 * The shared label row above a slider or pad axis: a leading range chevron (on the island's toggle
 * row), the clickable parameter name (name and chevron both toggle the island's range editor, the name
 * highlighted while it is open), an optional reset glyph (shown only when [value] is off [default]),
 * and a numeric entry field clamped to [range].
 *
 * @param String   name             The parameter display name.
 * @param Float    value            The current value.
 * @param Float    default          The parameter's default (neutral) value.
 * @param ClosedFloatingPointRange range The value range the numeric field clamps to.
 * @param String   resetLabel       The localized accessible label for the reset glyph.
 * @param String   rangeToggleLabel The localized accessible label for the range chevron.
 * @param Boolean  showRangeToggle  Whether this row's leading slot draws the island's chevron (versus a
 *                                  matching spacer); only consulted when [showLeadingSlot] is true.
 * @param Boolean  showLeadingSlot  Whether this row renders its own leading chevron / spacer slot. A
 *                                  linked pad shares one external chevron centred across both axis rows,
 *                                  so its rows pass false and let that chevron supply the indent.
 * @param Boolean  rangeOpen        Whether the island's range editor is open.
 * @param Function onToggleRange    Called when the name / chevron is clicked.
 * @param Boolean  renaming         Whether this parameter's name is being edited in place.
 * @param Function onStartRename    Called on a double-click of the name to begin inline rename.
 * @param Function onCommitRename   Called with the new name when the inline rename commits.
 * @param Function onCancelRename   Called when the inline rename is abandoned.
 * @param Function onCommitValue    Called with the new value (a discrete edit committed as one step).
 * @param UmamoIcon linkIcon        The trailing link / unlink glyph, or null for an empty slot.
 * @param String   linkContentDescription The localized accessible label for the link glyph.
 * @param Function onLinkClick      Called when the link glyph is clicked, or null when hidden.
 */
@Composable
private fun ParameterValueRow(
	name: String,
	value: Float,
	default: Float,
	range: ClosedFloatingPointRange<Float>,
	resetLabel: String,
	rangeToggleLabel: String,
	showRangeToggle: Boolean,
	showLeadingSlot: Boolean,
	rangeOpen: Boolean,
	onToggleRange: () -> Unit,
	renaming: Boolean,
	onStartRename: () -> Unit,
	onCommitRename: (String) -> Unit,
	onCancelRename: () -> Unit,
	onCommitValue: (Float) -> Unit,
	linkIcon: UmamoIcon?,
	linkContentDescription: String?,
	onLinkClick: (() -> Unit)?,
) {
	val colors = LocalUmamoColors.current
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			// A single click toggles the range editor; a double click begins inline rename. singleOrDoubleClick
			// uses raw pointerInput (never requests focus, so keyboard dispatch stays on the shell root) and
			// fires the single click immediately - no double-tap wait. While renaming, the field below consumes
			// its own presses, so this gesture does not fight it.
			modifier = Modifier.weight(1f).singleOrDoubleClick(onSingle = onToggleRange, onDouble = onStartRename),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (showLeadingSlot) {
				if (showRangeToggle) {
					val chevron =
						if (rangeOpen) {
							LocalUmamoIcons.chevronDown
						} else {
							LocalUmamoIcons.chevronRight
						}
					Canvas(
						modifier = Modifier.size(12.dp).semantics { contentDescription = rangeToggleLabel },
					) {
						drawIcon(chevron, colors.textMuted)
					}
				} else {
					Spacer(modifier = Modifier.width(12.dp))
				}
			}
			if (renaming) {
				InlineRenameField(
					initialName = name,
					textStyle = LocalUmamoTypography.current.labelSmall.copy(color = colors.text),
					cursorColor = colors.text,
					onCommit = onCommitRename,
					onCancel = onCancelRename,
					modifier = Modifier.weight(1f).padding(start = 4.dp),
				)
			} else {
				Text(
					text = name,
					style = LocalUmamoTypography.current.labelSmall,
					color = if (rangeOpen) colors.accent else colors.text,
					modifier = Modifier.padding(start = 4.dp),
				)
			}
		}
		if (abs(value - default) > RESET_EPSILON) {
			IconButton(
				icon = LocalUmamoIcons.reset,
				onClick = { onCommitValue(default) },
				contentDescription = resetLabel,
				size = DpSize(20.dp, 20.dp),
				appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
			)
			Spacer(modifier = Modifier.width(4.dp))
		}
		NumberField(value = value, onValueChange = onCommitValue, range = range, modifier = Modifier.width(64.dp))
		// A fixed-width trailing slot whether or not this row offers a link edit, so the number
		// fields stay column-aligned across all rows.
		Spacer(modifier = Modifier.width(4.dp))
		Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
			if (linkIcon != null && onLinkClick != null) {
				IconButton(
					icon = linkIcon,
					onClick = onLinkClick,
					contentDescription = linkContentDescription ?: "",
					size = DpSize(20.dp, 20.dp),
					appearance = IconButtonAppearance.Filled(LocalUmamoShapes.current.small),
					suppressFocus = true,
				)
			}
		}
	}
}

/**
 * The axis-name caption above a pad axis's range fields (user data, never localized).
 *
 * @param String name The axis parameter's display name.
 */
@Composable
private fun RangeAxisLabel(name: String) {
	Text(
		text = name,
		style = LocalUmamoTypography.current.labelSmall,
		color = LocalUmamoColors.current.textMuted,
		modifier = Modifier.padding(start = 8.dp, top = 4.dp),
	)
}

/**
 * The min / default / max fields for one parameter's range, rendered inside its island while the range
 * editor is open. Editing any field commits one undo step through the session (which normalizes
 * min <= max, clamps the default into the range, and re-clamps the live pose); the model then refreshes
 * the fields. Any number of islands can hold an open range editor at once.
 *
 * パラメータ範囲（最小・既定・最大）の編集フィールド。島の中に開き、各編集はセッション経由で1つの
 * 取り消し段になる。複数の島を同時に開ける。
 *
 * @param Parameter parameter  The parameter whose range is edited.
 * @param Function  onSetRange Called with (min, default, max) to commit a range edit.
 */
@Composable
private fun RangeFieldsRow(parameter: Parameter, onSetRange: (Float, Float, Float) -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
		verticalAlignment = Alignment.Bottom,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		RangeField(
			label = stringResource(Res.string.parameter_range_min),
			value = parameter.min,
			modifier = Modifier.weight(1f),
			onCommit = { newMin -> onSetRange(newMin, parameter.default, parameter.max) },
		)
		RangeField(
			label = stringResource(Res.string.parameter_range_default),
			value = parameter.default,
			modifier = Modifier.weight(1f),
			onCommit = { newDefault -> onSetRange(parameter.min, newDefault, parameter.max) },
		)
		RangeField(
			label = stringResource(Res.string.parameter_range_max),
			value = parameter.max,
			modifier = Modifier.weight(1f),
			onCommit = { newMax -> onSetRange(parameter.min, parameter.default, newMax) },
		)
	}
}

/**
 * One labelled numeric field of the range editor (min / default / max). The field clamps only to a wide
 * sanity bound; the session normalizes the resulting triple.
 *
 * @param String   label    The field's caption.
 * @param Float    value    The current value.
 * @param Modifier modifier The layout modifier (the caller supplies the row weight).
 * @param Function onCommit Called with the typed value when the field commits.
 */
@Composable
private fun RangeField(
	label: String,
	value: Float,
	modifier: Modifier = Modifier,
	onCommit: (Float) -> Unit,
) {
	Column(modifier = modifier) {
		Text(
			text = label,
			style = LocalUmamoTypography.current.labelSmall,
			color = LocalUmamoColors.current.textMuted,
			modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
		)
		NumberField(
			value = value,
			onValueChange = onCommit,
			range = RANGE_FIELD_LIMIT,
			modifier = Modifier.fillMaxWidth(),
		)
	}
}

/** A non-snapshot holder for the grip's latest layout coordinates, for the drag gesture's window origin. */
private class GripCoordinatesHolder {
	/** The grip's most recent layout coordinates, or null before the first layout pass. */
	var coordinates: LayoutCoordinates? = null
}

/**
 * The leading drag handle of a parameter row (a slider, a pad, or a group header). Dragging it reorders
 * the row or moves it into / out of a group. Uses raw pointerInput (immediate drag on a dedicated grip,
 * since the row body carries sliders and pads), which also never requests focus - so it does not
 * reintroduce the link-icon focus bug.
 *
 * @param String gripLabel The localized accessible label for the handle.
 * @param ParameterMoveSubject subject What a drag of this row relocates.
 * @param String rowKey The row's stable key (the drag / drop identity).
 * @param RowDragController<ParameterMoveSubject> dragController The shared drag state.
 * @param Function onDrop Invoked on release to apply the move.
 */
@Composable
private fun ParameterGripHandle(
	gripLabel: String,
	subject: ParameterMoveSubject,
	rowKey: String,
	dragController: RowDragController<ParameterMoveSubject>,
	onDrop: () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val gripCoordinates = remember { GripCoordinatesHolder() }
	Box(
		modifier =
			Modifier
				.size(width = 18.dp, height = 24.dp)
				// A hand cursor on hover signals the row is grabbable here (desktop only; touch has no hover).
				.pointerHoverIcon(PointerIcon.Hand)
				.onGloballyPositioned { coordinates -> gripCoordinates.coordinates = coordinates }
				.pointerInput(rowKey) {
					detectDragGestures(
						onDragStart = { offset ->
							val origin = gripCoordinates.coordinates?.boundsInWindow()
							dragController.start(
								rowKey,
								subject,
								(origin?.left ?: 0f) + offset.x,
								(origin?.top ?: 0f) + offset.y,
							)
						},
						onDrag = { change, _ ->
							change.consume()
							val origin = gripCoordinates.coordinates?.boundsInWindow()
							dragController.drag(
								(origin?.left ?: 0f) + change.position.x,
								(origin?.top ?: 0f) + change.position.y,
							)
						},
						onDragEnd = { onDrop() },
						onDragCancel = { dragController.end() },
					)
				}
				.semantics { contentDescription = gripLabel },
		contentAlignment = Alignment.Center,
	) {
		Canvas(modifier = Modifier.size(14.dp)) {
			drawIcon(LocalUmamoIcons.gripVertical, colors.textMuted)
		}
	}
}

/**
 * Draws the before / after insertion line for a drop-target row. Into is drawn by the group header's own
 * fill, so this only handles the reorder bands; a null band or an Into draws nothing.
 *
 * @param RowDropBand band The row's current drop band, or null when it is not the target.
 * @param Color accentColor The insertion-line color.
 * @return Modifier The modifier drawing the line behind the row.
 */
private fun Modifier.parameterDropLine(band: RowDropBand?, accentColor: Color): Modifier =
	this.drawBehind {
		if (band == RowDropBand.Before || band == RowDropBand.After) {
			val strokeWidth = 2.5.dp.toPx()
			val lineY =
				if (band == RowDropBand.Before) {
					strokeWidth / 2f
				} else {
					size.height - strokeWidth / 2f
				}
			drawLine(accentColor, Offset(0f, lineY), Offset(size.width, lineY), strokeWidth)
		}
	}

/**
 * The display name of the row currently being dragged (a parameter name, a pad's horizontal axis name,
 * or a group name), for the floating drag ghost.
 *
 * @param List rows The current render rows.
 * @param String? draggingKey The dragged row's key, or null.
 * @return String The dragged row's display name, or empty when none.
 */
private fun draggedRowLabel(rows: List<ParameterRow>, draggingKey: String?): String {
	val row = rows.firstOrNull { candidate -> rowKey(candidate) == draggingKey } ?: return ""
	return when (row) {
		is ParameterRow.Single -> row.parameter.name
		is ParameterRow.Pair2D -> row.horizontal.name
		is ParameterRow.GroupHeader -> row.name
	}
}

/**
 * Applies a parameter-row drag drop: resolves the target row and band, then dispatches the tree move.
 * Ends the controller first (after capturing its state), so a no-op / illegal drop simply clears the
 * drag. An Into drop expands the destination group.
 *
 * @param RowDragController<ParameterMoveSubject> dragController The drag state (read then cleared).
 * @param List rows The current render rows.
 * @param PuppetModel puppet The open model (its tree resolves the drop anchor).
 * @param EditorSession? session The session the move dispatches through.
 * @param Function onExpandGroup Expands a group id (for an Into drop).
 */
private fun performParameterDrop(
	dragController: RowDragController<ParameterMoveSubject>,
	rows: List<ParameterRow>,
	puppet: PuppetModel,
	session: EditorSession?,
	onExpandGroup: (ParameterGroupId) -> Unit,
) {
	val targetKey = dragController.dropTargetKey
	val subject = dragController.draggedPayload
	val fraction = dragController.dropTargetFraction ?: 0.5f
	dragController.end()
	if (targetKey == null || subject == null || session == null) {
		return
	}
	val targetRow = rows.firstOrNull { candidate -> rowKey(candidate) == targetKey } ?: return
	val band = parameterDropBandFor(subject, targetRow, targetRow.depth == 0, fraction) ?: return
	val (newParentGroupId, before) = parameterDropAnchor(puppet, targetRow, band)
	session.moveParameterRow(subject, newParentGroupId, before)
	if (band == RowDropBand.Into && targetRow is ParameterRow.GroupHeader) {
		onExpandGroup(targetRow.groupId)
	}
}

/** The shared height of a group header row (matches the kit SectionHeader it replaced). */
private val GROUP_HEADER_HEIGHT = 22.dp

/**
 * A parameter group's header rail: a recessed rounded row with a disclosure chevron and the group name.
 * A single tap toggles the group; a double tap opens inline rename. Both go through [singleOrDoubleClick],
 * whose raw pointerInput - unlike a clickable - never requests focus (keeping keyboard dispatch on the
 * shell root) and, unlike detectTapGestures, fires the toggle immediately instead of waiting out the
 * double-tap window. A nest-into drop tints and outlines it.
 *
 * @param String name The group's display name.
 * @param Boolean expanded Whether the group is open (chevron points down).
 * @param Boolean nesting Whether a drag is hovering to nest into this group (drop highlight).
 * @param Function onToggle Called on a single tap to expand / collapse.
 * @param Function onStartRename Called on a double tap to begin inline rename.
 */
@Composable
private fun ParameterGroupHeaderBody(
	name: String,
	expanded: Boolean,
	nesting: Boolean,
	onToggle: () -> Unit,
	onStartRename: () -> Unit,
) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	Row(
		modifier =
			Modifier
				.fillMaxWidth()
				.height(GROUP_HEADER_HEIGHT)
				.clip(shapes.small)
				.background(if (nesting) colors.dropTargetBackground else colors.tabBackground, shape = shapes.small)
				.then(
					if (nesting) {
						Modifier.border(1.dp, colors.accent, shapes.small)
					} else {
						Modifier
					},
				)
				// A single tap toggles the group immediately (no double-tap wait, so it never feels laggy);
				// a double tap opens inline rename.
				.singleOrDoubleClick(onSingle = onToggle, onDouble = onStartRename)
				.padding(horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		val chevron =
			if (expanded) {
				LocalUmamoIcons.chevronDown
			} else {
				LocalUmamoIcons.chevronRight
			}
		Canvas(modifier = Modifier.size(10.dp)) {
			drawIcon(chevron, colors.textMuted)
		}
		Spacer(modifier = Modifier.width(5.dp))
		Text(text = name, style = LocalUmamoTypography.current.labelMedium)
	}
}

/**
 * The inline rename field for a group header, styled as the recessed rail it replaces while editing.
 * Commits on Enter or focus loss, cancels on Escape (routed by the shell through LocalInlineEditController).
 *
 * @param String initialName The current group name, pre-selected for replacement.
 * @param Function onCommit Called with the trimmed new name.
 * @param Function onCancel Called when the edit is abandoned.
 */
@Composable
private fun ParameterGroupRenameField(initialName: String, onCommit: (String) -> Unit, onCancel: () -> Unit) {
	val colors = LocalUmamoColors.current
	val shapes = LocalUmamoShapes.current
	Box(
		modifier =
			Modifier
				.fillMaxWidth()
				.height(GROUP_HEADER_HEIGHT)
				.clip(shapes.small)
				.background(colors.tabBackground, shape = shapes.small)
				.padding(horizontal = 4.dp),
		contentAlignment = Alignment.CenterStart,
	) {
		InlineRenameField(
			initialName = initialName,
			textStyle = LocalUmamoTypography.current.labelMedium.copy(color = colors.text),
			cursorColor = colors.text,
			onCommit = onCommit,
			onCancel = onCancel,
		)
	}
}
