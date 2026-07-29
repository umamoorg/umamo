package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ParameterSelection
import org.umamo.edit.moveTrackKey
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.Text
import org.umamo.ui.model.KeyformHover
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalKeyableHover
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.drawIcon
import org.umamo.ui.theme.umamoPointerIcon
import org.umamo.ui.tracks.TRACK_LABEL_COLUMN_DEFAULT_WIDTH
import org.umamo.ui.tracks.TrackAxis
import org.umamo.ui.tracks.TrackRow
import org.umamo.ui.tracks.TrackRowDecor
import org.umamo.ui.tracks.TrackSheet
import org.umamo.ui.tracks.TrackSheetBackdrop
import org.umamo.ui.tracks.TrackSheetSeparatorOverlay
import org.umamo.ui.tracks.TrackWindow
import org.umamo.ui.tracks.TrackWindowScrollbar
import org.umamo.ui.tracks.trackWindowGestures
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.HoveredSurface
import org.umamo.ui.workspace.KeyformSheetSurface
import org.umamo.ui.workspace.LocalHoveredSurfaceTracker
import org.umamo.ui.workspace.LocalKeyformSheetViews
import org.umamo.ui.workspace.SpaceKind

/** The key this space's view state is stored under on its hosting area. */
private const val KEYFORM_SHEET_VIEW_STATE_KEY = "keyformsheet"

/**
 * The keyform sheet's per-area view state: the label column's width and which groups are open.
 *
 * On the AreaScope rather than the session because these are how one area is LOOKING at the rig, not what
 * the rig is - two sheets side by side may reasonably be folded differently, and neither belongs in undo.
 */
private class KeyformSheetViewState {
	/** The label column's width, dragged on the separator. */
	var labelColumnWidth: Dp by mutableStateOf(TRACK_LABEL_COLUMN_DEFAULT_WIDTH)

	/** The group rows whose tracks are shown. */
	var expandedKeys: Set<String> by mutableStateOf(emptySet())

	/**
	 * The selected keys, which is what Delete acts on.
	 *
	 * On the view state rather than remembered against the projection: the projection is rebuilt on every
	 * model change, so keying the selection to it discarded the selection on the user's own edit - and a
	 * click both selects AND scrubs, so even selecting could not survive its own gesture.  Refs that no
	 * longer resolve are pruned at use, which is cheaper and less surprising than clearing wholesale.
	 */
	var selectedKeys: Set<TrackKeyRef> by mutableStateOf(emptySet())

	/**
	 * Whether [expandedKeys] has been seeded yet.
	 *
	 * A fresh sheet opens with every group expanded (an all-collapsed sheet looks identical to one with
	 * nothing keyed), but "collapse everything" has to stay reachable - so the seed happens ONCE rather
	 * than whenever the set is empty.
	 */
	var seeded: Boolean = false

	/**
	 * The parameter sections folded away.
	 *
	 * COLLAPSED rather than expanded, so a section that appears later (targeting a second parameter) opens
	 * rather than arriving invisible.  Sections matter for a linked pad, where one parameter's tracks can
	 * bury the other's.
	 */
	var collapsedParameters: Set<ParameterId> by mutableStateOf(emptySet())

	/**
	 * The visible slice of every section's domain.
	 *
	 * ONE window for the whole area, normalized, so zooming works like a timeline's: every track and both
	 * of a linked pad's sections move together.  Per-track zoom has no precedent in any editor with tracks
	 * and would make comparing two rows - the reason the sheet exists - impossible.
	 */
	var window: TrackWindow by mutableStateOf(TrackWindow.Full)
}

/**
 * The keyform sheet: for each parameter targeted in the Parameters panel, one track per (item, channel)
 * keyed on it, with the marks laid out across that parameter's authored range.
 *
 * One SECTION per targeted parameter, stacked under a single scroll.  A linked pad targets both of its
 * axes at once, so a single-section sheet would show only half of what the panel says is selected.
 *
 * Within a section, tracks hang under a collapsible per-owner group row - one track per CHANNEL rather
 * than per item, because that is the thing the per-channel split made possible and the thing this sheet
 * exists to make visible: an item can key opacity on a parameter its geometry never touches.  A collapsed
 * group still shows its subtree's key positions, so folding hides detail, never the presence of keys.
 *
 * Clicking a mark scrubs the parameter onto it and selects it; dragging a mark moves the key (clamped at
 * its neighbours); Delete removes the selection as one undo step; right-clicking a lane inserts a key
 * there or removes the one under the pointer.  Box-select is not built yet.
 *
 * キーフォームシート。選択パラメータごとに、(オブジェクト, チャンネル)のトラックを所有者単位で
 * 折りたためる形で表示する。
 *
 * @param AreaScope scope The hosting area's scope.
 */
@Composable
internal fun KeyformSheetSpace(scope: AreaScope) {
	val colors = LocalUmamoColors.current
	val puppet = LocalPuppet.current
	val session = LocalEditorSession.current
	val liveParams = LocalLiveParams.current
	val viewState = scope.spaceState(KEYFORM_SHEET_VIEW_STATE_KEY) { KeyformSheetViewState() }

	val parameterSelection by remember(session) {
		session?.parameterSelection ?: MutableStateFlow(ParameterSelection())
	}.collectAsState()

	// Channel and track labels are Umamo chrome, so they resolve from resources here and are injected into
	// the Compose-free projection. Item names are the user's own data and are never translated.
	// Resolved EAGERLY into maps rather than looked up inside the injected lambdas: stringResource is
	// itself composable, so a lambda the projection calls later cannot reach it.
	val channelLabels = channelLabels()
	val ownerKindLabels = ownerKindLabels()
	val geometryLabel = stringResource(Res.string.track_geometry)
	val blendShapeLabel = stringResource(Res.string.track_blend_shape)
	val labels =
		remember(channelLabels, ownerKindLabels, geometryLabel, blendShapeLabel) {
			KeyformTrackLabels(
				channelName = { channel -> channelLabels.getValue(channel) },
				geometry = geometryLabel,
				blendShape = blendShapeLabel,
				ownerKindName = { kind -> ownerKindLabels.getValue(kind) },
			)
		}

	// EVERY targeted parameter gets a section, not just the active one: clicking a linked pad targets both
	// of its axes, and showing only one of them makes half the pad's keys invisible.  Ordered by the model
	// so the sections read in the same order as the panel's rows, not in selection order.
	val targetedParameters =
		remember(puppet, parameterSelection) {
			puppet?.parameters?.filter { parameter -> parameter.id in parameterSelection.ids }.orEmpty()
		}
	// The backdrop is drawn whatever the sheet has to show, so an empty sheet still reads as a track region
	// beside a label column rather than as blank panel.
	// Either horizontal drag - panning the tracks, or resizing the label column - holds the resize cursor
	// for the whole gesture.  Tracked here rather than inside each gesture because the cursor has to be
	// declared ONCE, on a node that exists the whole time: a pointerHoverIcon that only appears mid-drag
	// is not re-resolved until the pointer next crosses a node boundary, which mid-drag it may never do.
	var panningTracks by remember { mutableStateOf(false) }
	var resizingColumn by remember { mutableStateOf(false) }
	val horizontalDrag = panningTracks || resizingColumn
	// Stamp the shell's last-touched surface, exactly like the 2D viewport and the UV editor: the sheet's
	// shell-level commands (delete/nudge selected keys, frame all) resolve WHICH sheet at dispatch time
	// from this.  Observed on the Initial pass so the lanes' own gestures keep every event.
	val hoveredTracker = LocalHoveredSurfaceTracker.current
	Box(
		modifier =
			Modifier
				.fillMaxSize()
				.background(colors.panelBackground)
				.pointerInput(hoveredTracker, scope.areaId) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent(PointerEventPass.Initial)
							if (event.type != PointerEventType.Exit) {
								hoveredTracker?.lastTouched = HoveredSurface(scope.areaId, SpaceKind.KeyformSheet)
							}
						}
					}
				}
				.pointerHoverIcon(
					icon = if (horizontalDrag) umamoPointerIcon(LocalUmamoCursors.ewScroll) else PointerIcon.Default,
					// Only while a drag is live: otherwise the label column's own hover cursors, and the
					// separator band's, must keep winning over this.
					overrideDescendants = horizontalDrag,
				)
				.trackWindowGestures(
					window = viewState.window,
					onWindowChange = { window -> viewState.window = window },
					labelColumnWidth = viewState.labelColumnWidth,
					onPanningChange = { panning -> panningTracks = panning },
				),
	) {
		TrackSheetBackdrop(labelColumnWidth = viewState.labelColumnWidth)
		if (puppet == null || targetedParameters.isEmpty()) {
			EmptySheetNotice(stringResource(Res.string.keyform_sheet_no_parameter))
			return@Box
		}
		val projections =
			remember(puppet, targetedParameters, labels) {
				targetedParameters.map { parameter -> parameter to keyformSheetRows(puppet, parameter.id, labels) }
			}
		if (!viewState.seeded) {
			// In a SideEffect so it runs only for APPLIED compositions: an abandoned composition rolls back
			// the snapshot write to expandedKeys but not the plain seeded gate, which would strand the sheet
			// all-collapsed forever - indistinguishable from a rig with nothing keyed.
			val seedKeys = projections.flatMap { (_, projection) -> projection.groupRowKeys }.toSet()
			SideEffect {
				if (!viewState.seeded) {
					viewState.seeded = true
					viewState.expandedKeys = seedKeys
				}
			}
		}
		if (projections.all { (_, projection) -> projection.rows.isEmpty() }) {
			EmptySheetNotice(stringResource(Res.string.keyform_sheet_no_tracks))
			return@Box
		}
		// The sheet's commands (delete/nudge selected keys, frame all) live at SHELL level - per-area
		// registration made two open sheets clobber each other in the last-write-wins registry.  The area
		// only registers its command surface here; the lambdas read the live view state and the CURRENT
		// projections at dispatch time, so the effect never needs to re-run on an edit or a selection
		// change.  A ref whose row is gone simply drops out of selectedTracks, which is what makes a stale
		// selection harmless rather than dangerous.
		val keyformSheetViews = LocalKeyformSheetViews.current
		val currentProjections = rememberUpdatedState(projections)
		DisposableEffect(keyformSheetViews, scope.areaId) {
			if (keyformSheetViews == null) {
				onDispose {}
			} else {
				fun selectedTracks(): List<Triple<KeyformTrackRef, Parameter, Int>> =
					viewState.selectedKeys.mapNotNull { keyRef ->
						val entry = currentProjections.value.firstOrNull { (parameter, _) -> parameter.id == keyRef.parameterId }
						entry?.second?.tracksByRowKey?.get(keyRef.rowKey)?.let { track ->
							Triple(track, entry.first, keyRef.keyIndex)
						}
					}
				val surface =
					KeyformSheetSurface(
						selectedTracks = ::selectedTracks,
						hasSelection = { viewState.selectedKeys.isNotEmpty() },
						clearSelection = { viewState.selectedKeys = emptySet() },
						frameAll = { viewState.window = TrackWindow.Full },
					)
				keyformSheetViews.register(scope.areaId, surface)
				onDispose { keyformSheetViews.unregister(scope.areaId, surface) }
			}
		}
		// ONE outer scroll over all the sections; each TrackSheet lays its rows out eagerly for exactly this
		// reason (a lazy list nested in a scroll fights it for the gesture).
		Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
			for ((parameter, projection) in projections) {
				key(parameter.id) {
					val collapsed = parameter.id in viewState.collapsedParameters
					// The section header names which parameter the ruler below belongs to, and folds it
					// away.  Shown even for a single section: without it the sheet is a set of numbers with
					// no stated domain.
					SectionHeader(
						name = parameter.name,
						collapsed = collapsed,
						onToggle = {
							viewState.collapsedParameters =
								if (collapsed) {
									viewState.collapsedParameters - parameter.id
								} else {
									viewState.collapsedParameters + parameter.id
								}
						},
					)
					if (collapsed) {
						// Folded: the header alone, so a linked pad's other axis is one click away.
					} else if (projection.rows.isEmpty()) {
						Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
							Text(
								text = stringResource(Res.string.keyform_sheet_no_tracks),
								style = LocalUmamoTypography.current.bodyMedium,
								color = colors.textMuted,
							)
						}
					} else {
						KeyformSheetSection(
							parameter = parameter,
							projection = projection,
							selectedKeys = viewState.selectedKeys,
							window = viewState.window,
							labelColumnWidth = viewState.labelColumnWidth,
							expandedKeys = viewState.expandedKeys,
							onToggleExpanded = { row ->
								viewState.expandedKeys =
									if (row.key in viewState.expandedKeys) {
										viewState.expandedKeys - row.key
									} else {
										viewState.expandedKeys + row.key
									}
							},
							onSelectedKeysChange = { keys -> viewState.selectedKeys = keys },
						)
					}
				}
			}
		}
		TrackSheetSeparatorOverlay(
			labelColumnWidth = viewState.labelColumnWidth,
			onLabelColumnWidthChange = { width -> viewState.labelColumnWidth = width },
			onDraggingChange = { dragging -> resizingColumn = dragging },
		)
		// The window indicator sits at the bottom edge, over the scroll, because it describes the
		// horizontal view rather than the vertical one - and it hides itself when everything is framed.
		Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
			TrackWindowScrollbar(
				window = viewState.window,
				onWindowChange = { window -> viewState.window = window },
				labelColumnWidth = viewState.labelColumnWidth,
			)
		}
	}
}

/**
 * A parameter section's header: its name and the chevron that folds the section away.
 *
 * @param String name The parameter's display name (user data - never translated).
 * @param Boolean collapsed Whether the section is folded.
 * @param Function onToggle Invoked when the header is clicked.
 */
@Composable
private fun SectionHeader(name: String, collapsed: Boolean, onToggle: () -> Unit) {
	val colors = LocalUmamoColors.current
	Row(
		modifier =
			Modifier
				.fillMaxWidth()
				.background(colors.tabBackground)
				// NOT focusable: a clickable takes focus, and a row that can be disposed by the very edit
				// beside it leaves Compose with no focus owner, which kills every keyboard shortcut.
				.focusProperties { canFocus = false }
				.clickable(onClick = onToggle)
				.padding(horizontal = 6.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Canvas(modifier = Modifier.size(14.dp)) {
			drawIcon(if (collapsed) LocalUmamoIcons.chevronRight else LocalUmamoIcons.chevronDown, colors.textMuted)
		}
		Spacer(modifier = Modifier.width(4.dp))
		Text(text = name, style = LocalUmamoTypography.current.labelMedium, color = colors.textMuted)
	}
}

/**
 * One parameter's ruler and tracks.
 *
 * Split out from [KeyformSheetSpace] so each section's marks recompose against their own projection and
 * playhead rather than the whole sheet's.
 *
 * @param Parameter parameter The parameter this section's domain comes from.
 * @param KeyformSheetProjection projection Its tracks and their owning targets.
 * @param Set<TrackKeyRef> selectedKeys The sheet-wide key selection (shared across sections).
 * @param TrackWindow window The visible slice of the parameter's range, shared by every section.
 * @param Dp labelColumnWidth The label column's width, shared by every section.
 * @param Set<String> expandedKeys The open group rows, shared by every section.
 * @param Function onToggleExpanded Publishes a chevron click.
 * @param Function onSelectedKeysChange Publishes a new selection to the sheet.
 */
@Composable
private fun KeyformSheetSection(
	parameter: Parameter,
	projection: KeyformSheetProjection,
	selectedKeys: Set<TrackKeyRef>,
	window: TrackWindow,
	labelColumnWidth: Dp,
	expandedKeys: Set<String>,
	onToggleExpanded: (TrackRow) -> Unit,
	onSelectedKeysChange: (Set<TrackKeyRef>) -> Unit,
) {
	val session = LocalEditorSession.current
	val liveParams = LocalLiveParams.current
	val keyableHover = LocalKeyableHover.current
	val commands = LocalCommands.current
	val colors = LocalUmamoColors.current
	val icons = LocalUmamoIcons
	val keymap = LocalKeymap.current
	val insertLabel = stringResource(Res.string.cmd_keyform_insert)
	val deleteLabel = stringResource(Res.string.cmd_keyform_delete)
	// The playhead follows the live scrub through snapshotFlow rather than a composition read:
	// observedValues is one whole-map state replaced on every preview move of ANY parameter, so reading
	// it while composing invalidated the entire sheet per pointer move (ParametersSpace documents the
	// same rule).  The initial read is deliberately unobserved; the flow delivers every later change to
	// this section's own state alone.
	var playhead by remember(parameter.id) {
		mutableStateOf(
			Snapshot.withoutReadObservation { liveParams?.observedValues?.get(parameter.id) } ?: parameter.default,
		)
	}
	LaunchedEffect(liveParams, parameter.id) {
		snapshotFlow { liveParams?.observedValues?.get(parameter.id) ?: parameter.default }
			.collect { value -> playhead = value }
	}
	val rows =
		remember(projection, selectedKeys, parameter.id) {
			projection.rows.map { row -> row.withSelection(parameter.id, selectedKeys) }
		}
	val (domainStart, domainEnd) = parameterDomain(parameter)
	// The section draws the VISIBLE slice of its parameter, which is what makes one normalized window
	// drive two axes with unrelated ranges at the same screen positions.
	val axis = window.axisOver(TrackAxis(domainStart, domainEnd))
	TrackSheet(
		rows = rows,
		axis = axis,
		// The playhead is the live scrub value, so the sheet reads as a view OF the current pose rather
		// than a static list beside it.
		playhead = playhead,
		modifier = Modifier.fillMaxWidth(),
		labelColumnWidth = labelColumnWidth,
		expandedKeys = expandedKeys,
		onToggleExpanded = onToggleExpanded,
		decorFor = { row ->
			// Only group rows carry an icon: they are the rows that name a thing in the rig.  A channel
			// track names a property, and several of those (opacity, draw order) have no icon in the set -
			// giving some of them art and not others would read as a missing glyph rather than a category.
			when (projection.ownerKindByRowKey[row.key]) {
				KeyformOwnerKind.ArtMesh -> TrackRowDecor(icons.mesh, colors.outlinerObjectTint)
				KeyformOwnerKind.Part -> TrackRowDecor(icons.part, colors.outlinerObjectTint)
				KeyformOwnerKind.WarpDeformer -> TrackRowDecor(icons.warpDeformer, colors.outlinerDeformTint)
				KeyformOwnerKind.RotationDeformer -> TrackRowDecor(icons.rotationDeformer, colors.outlinerDeformTint)
				KeyformOwnerKind.Glue -> TrackRowDecor(icons.linked, colors.outlinerDeformTint)
				null -> TrackRowDecor()
			}
		},
		// Clicking a mark selects it AND scrubs to it: selection is what Delete acts on, and scrubbing is
		// how you land the pose exactly on a key without hunting with the slider. The two never conflict,
		// so doing both is strictly more useful than choosing.
		onMarkClick = { row, mark ->
			onSelectedKeysChange(setOf(TrackKeyRef(parameter.id, row.key, mark.keyIndex)))
			liveParams?.preview(parameter.id, mark.position)
			liveParams?.commit(setOf(parameter.id))
		},
		// Pressing or dragging empty track scrubs the parameter, so the whole track region works like the
		// ruler of a timeline rather than only the marks being live.  The press also drops the key
		// selection, matching every other list in the editor.
		onTrackScrub = { _, value ->
			onSelectedKeysChange(emptySet())
			// Clamped again at the model boundary, not only in the lane: the lane clamps to the VISIBLE
			// window, which is a subrange, but this is the call that reaches the evaluator - and a pose
			// outside the parameter's range brackets nothing, so every entity keyed on it disappears.
			// The minOf/maxOf form tolerates a reversed (min > max) range from a malformed import, like
			// every sibling clamp in this feature - a plain coerceIn throws on one mid-gesture.
			liveParams?.preview(parameter.id, value.coerceIn(minOf(parameter.min, parameter.max), maxOf(parameter.min, parameter.max)))
		},
		// One undo step per gesture, at its end - the same contract a slider drag has.
		onTrackScrubEnd = { _, _ -> liveParams?.commit(setOf(parameter.id)) },
		onMarkDragEnd = { row, mark, releasedAt ->
			val track = projection.tracksByRowKey[row.key]
			if (session != null && track != null) {
				// The key keeps its ordinal, so the selection survives a move untouched - the whole point
				// of addressing keys by ordinal rather than by the value that is being changed.
				session.moveTrackKey(track, parameter, mark.keyIndex, releasedAt)
			}
		},
		// Publishing the hovered row is what lets `I` / `Alt+I` aim at a track the way they already aim at a
		// Properties row: point at it and press.  The projection maps the row back to what it edits; a row
		// with no track ref (a group header, a blend-shape binding) publishes nothing, so the shortcut
		// falls through to its notice rather than acting on something it cannot address.  The hover carries
		// THIS section's parameter - the selection's active member can be the other axis of a linked pad.
		onLaneHover = { row, hit ->
			projection.tracksByRowKey[row.key]?.let { track ->
				if (hit == null) {
					keyableHover?.exit(track)
				} else {
					keyableHover?.enter(KeyformHover(track, hit.value, hit.mark?.keyIndex, parameter.id))
				}
			}
		},
		laneMenuItems = { hit ->
			// A group row names the owner rather than a track, and a blend-shape row is not a keyform grid -
			// neither has a track ref, so both get an empty menu rather than actions that silently do nothing.
			// Each item dispatches THROUGH the registry rather than calling the session, so the menu can
			// never drift from the shortcut it advertises.  The popup owns the pointer by the time an item
			// fires and the live lane hover is gone, so the clicked spot is re-published for the command's
			// dispatch-time read and cleared right after.
			val track = projection.tracksByRowKey[hit.row.key]
			val hitMark = hit.mark
			when {
				session == null || track == null -> emptyList()
				hitMark != null ->
					listOf(
						MenuItem.Action(
							label = deleteLabel,
							onSelect = {
								keyableHover?.enter(KeyformHover(track, hit.value, hitMark.keyIndex, parameter.id))
								commands.invoke("keyform.delete")
								keyableHover?.exit(track)
							},
							shortcut = keymap.chordFor("keyform.delete")?.let { chord -> formatAccelerator(chord) },
						),
					)

				else ->
					listOf(
						MenuItem.Action(
							label = insertLabel,
							onSelect = {
								keyableHover?.enter(KeyformHover(track, hit.value, keyIndex = null, parameterId = parameter.id))
								commands.invoke("keyform.insert")
								keyableHover?.exit(track)
							},
							shortcut = keymap.chordFor("keyform.insert")?.let { chord -> formatAccelerator(chord) },
						),
					)
			}
		},
	)
}

/**
 * This row and its whole subtree with each mark's selected flag resolved against [selectedKeys].
 *
 * Recursive because selection is per-track but the tree is what gets rendered, and a child's marks have to
 * carry the flag whether or not its parent happens to be expanded.
 *
 * @param ParameterId parameterId The section's parameter, which is part of a key's identity.
 * @param Set<TrackKeyRef> selectedKeys The current selection.
 * @return TrackRow The row with selection applied.
 */
private fun TrackRow.withSelection(parameterId: ParameterId, selectedKeys: Set<TrackKeyRef>): TrackRow =
	copy(
		marks = marks.map { mark -> mark.copy(selected = TrackKeyRef(parameterId, key, mark.keyIndex) in selectedKeys) },
		children = children.map { child -> child.withSelection(parameterId, selectedKeys) },
	)

/** The centered muted notice shown when the sheet has nothing to draw. */
@Composable
private fun EmptySheetNotice(message: String) {
	Box(modifier = Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
		Text(
			text = message,
			style = LocalUmamoTypography.current.bodyMedium,
			color = LocalUmamoColors.current.textMuted,
		)
	}
}

/**
 * Every channel's localized short label, resolved in one pass.
 *
 * A map rather than a function because the projection is Compose-free and calls its label lookup from
 * ordinary code, where stringResource is unreachable.  Exhaustive over the enum, so adding a channel is a
 * compile error here rather than a missing label at runtime.
 *
 * @return Map<FormChannel, String> The label per channel.
 */
@Composable
private fun channelLabels(): Map<FormChannel, String> =
	FormChannel.entries.associateWith { channel ->
		when (channel) {
			FormChannel.DRAW_ORDER -> stringResource(Res.string.channel_draw_order)
			FormChannel.OPACITY -> stringResource(Res.string.channel_opacity)
			FormChannel.MULTIPLY_COLOR -> stringResource(Res.string.channel_multiply_color)
			FormChannel.SCREEN_COLOR -> stringResource(Res.string.channel_screen_color)
			FormChannel.FLIP_X -> stringResource(Res.string.channel_flip_x)
			FormChannel.FLIP_Y -> stringResource(Res.string.channel_flip_y)
			FormChannel.GLUE_INTENSITY -> stringResource(Res.string.channel_glue_intensity)
		}
	}

/**
 * Every owner kind's localized label, resolved in one pass - the subtitle under a group row's name.
 *
 * @return Map<KeyformOwnerKind, String> The label per owner kind.
 */
@Composable
private fun ownerKindLabels(): Map<KeyformOwnerKind, String> =
	KeyformOwnerKind.entries.associateWith { kind ->
		when (kind) {
			KeyformOwnerKind.ArtMesh -> stringResource(Res.string.owner_kind_art_mesh)
			KeyformOwnerKind.WarpDeformer -> stringResource(Res.string.owner_kind_warp_deformer)
			KeyformOwnerKind.RotationDeformer -> stringResource(Res.string.owner_kind_rotation_deformer)
			KeyformOwnerKind.Part -> stringResource(Res.string.owner_kind_part)
			KeyformOwnerKind.Glue -> stringResource(Res.string.owner_kind_glue)
		}
	}
