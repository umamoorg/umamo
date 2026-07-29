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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ParameterSelection
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.dragTrackKeys
import org.umamo.edit.limitedDragFraction
import org.umamo.edit.moveTrackKey
import org.umamo.edit.moveTrackKeys
import org.umamo.edit.removeTrackKeys
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.SCROLLBAR_THICKNESS
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.VerticalScrollbarOverlay
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
import org.umamo.ui.tracks.TRACK_MARK_RADIUS
import org.umamo.ui.tracks.TrackAxis
import org.umamo.ui.tracks.TrackKeyMark
import org.umamo.ui.tracks.TrackRow
import org.umamo.ui.tracks.TrackRowDecor
import org.umamo.ui.tracks.TrackSheet
import org.umamo.ui.tracks.TrackSheetBackdrop
import org.umamo.ui.tracks.TrackSheetMarqueeOverlay
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
	// A Column, not a Box with the window indicator layered on: as an overlay the indicator painted over the
	// bottom 8dp of the last row and - because it is draggable - swallowed the pointer there, so marks in
	// that strip were unclickable whenever the sheet was zoomed.  As a sibling it cannot reach the rows.
	val scrollState = rememberScrollState()
	val markRadiusPx = with(LocalDensity.current) { TRACK_MARK_RADIUS.toPx() }
	Column(
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
		// The window indicator is composed AFTER this box, so it needs the weight; the box takes the rest.
		Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
			// The track region stops short of the vertical bar, so a mark at the domain maximum is never
			// underneath it.  The backdrop is inset to match, or the two columns would stop lining up.
			TrackSheetBackdrop(
				labelColumnWidth = viewState.labelColumnWidth,
				modifier = Modifier.padding(end = SCROLLBAR_THICKNESS),
			)
			if (puppet == null || targetedParameters.isEmpty()) {
				EmptySheetNotice(stringResource(Res.string.keyform_sheet_no_parameter))
				return@Box
			}
			// The filter is a projection INPUT, not a draw-time skip: a filtered-out track has to be absent
			// from the row tree so an owner left with nothing loses its group row too, and so a summary mark
			// never stands for a key the sheet is not showing.
			val filter =
				remember(viewState.showGeometry, viewState.showChannels, viewState.showBlendShapes) {
					KeyformTrackFilter(viewState.showGeometry, viewState.showChannels, viewState.showBlendShapes)
				}
			val projections =
				remember(puppet, targetedParameters, labels, filter) {
					targetedParameters.map { parameter ->
						parameter to keyformSheetRows(puppet, parameter.id, labels, filter)
					}
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
				// "Nothing is keyed" and "you hid it" are opposite diagnoses, and only one of them is the
				// user's own doing - saying the first while a filter is eating the rows sends them looking for
				// a rig problem that is not there.
				val everythingFiltered = projections.any { (_, projection) -> projection.hiddenByFilter }
				EmptySheetNotice(
					stringResource(
						if (everythingFiltered) Res.string.keyform_sheet_all_filtered else Res.string.keyform_sheet_no_tracks,
					),
				)
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
							val entry =
								currentProjections.value.firstOrNull { (parameter, _) -> parameter.id == keyRef.parameterId }
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
							armBoxSelect = { viewState.boxSelectArmed = true },
						)
					keyformSheetViews.register(scope.areaId, surface)
					onDispose { keyformSheetViews.unregister(scope.areaId, surface) }
				}
			}
			// Dragging one mark of a multi-key selection drags the whole selection, which means resolving refs
			// from EVERY section: a linked pad shows two, and one box select can enclose keys in both.  The
			// section that owns the gesture sees only its own projection, so the resolution lives here and it
			// is handed down as an action.
			//
			// PREVIEW then COMMIT, through the same clamp: while the pointer moves, the fraction is only
			// recorded (the marks draw shifted by it and the model is untouched); on release it is applied as
			// one undo step.  A per-move commit would push an undo entry per pixel, and a preview at the
			// unclamped fraction would show the group travelling past the wall it is about to stop at.
			val dragSelectedKeys: (Float, Boolean) -> Unit = { fraction, commit ->
				val plan =
					viewState.selectedKeys.mapNotNull { keyRef ->
						val entry = projections.firstOrNull { (parameter, _) -> parameter.id == keyRef.parameterId }
						entry?.second?.tracksByRowKey?.get(keyRef.rowKey)?.let { track ->
							keyRef to Triple(track, entry.first, keyRef.keyIndex)
						}
					}
				if (session == null || plan.isEmpty()) {
					viewState.dragPreviewFraction = 0f
				} else if (commit) {
					viewState.dragPreviewFraction = 0f
					val landed = session.dragTrackKeys(plan.map { (_, key) -> key }, fraction)
					// Re-pointed at where each key actually ended up: a crossing renumbers its axis, so keeping the
					// old ordinals would leave the selection on whichever keys took their places.
					viewState.selectedKeys =
						plan.mapIndexed { position, (keyRef, _) -> keyRef.copy(keyIndex = landed.getOrElse(position) { keyRef.keyIndex }) }
							.toSet()
				} else {
					viewState.dragPreviewFraction = session.model.value.limitedDragFraction(plan.map { (_, key) -> key }, fraction)
				}
			}
			// ONE outer scroll over all the sections; each TrackSheet lays its rows out eagerly for exactly this
			// reason (a lazy list nested in a scroll fights it for the gesture).
			Column(
				modifier =
					Modifier
						.fillMaxSize()
						.padding(end = SCROLLBAR_THICKNESS)
						.verticalScroll(scrollState),
			) {
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
							Box(
								modifier = Modifier.fillMaxWidth().padding(12.dp),
								contentAlignment = Alignment.Center,
							) {
								Text(
									text =
										stringResource(
											if (projection.hiddenByFilter) {
												Res.string.keyform_sheet_all_filtered
											} else {
												Res.string.keyform_sheet_no_tracks
											},
										),
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
								onDragSelectedKeys = dragSelectedKeys,
								// The preview fraction is of the parameter's RANGE; a lane draws in its own domain units, so
								// each section converts with its own span - which is what keeps a linked pad's two axes moving
								// together on screen despite unrelated ranges.
								selectedMarkDragDelta = {
									viewState.dragPreviewFraction *
										(maxOf(parameter.max, parameter.min) - minOf(parameter.max, parameter.min))
								},
								onLaneBounds = { row, bounds -> viewState.laneBounds[row.key] = bounds },
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
			// Above the separator, so while armed the marquee takes the drag rather than the column resize.
			TrackSheetMarqueeOverlay(
				armed = viewState.boxSelectArmed,
				onSelect = { region, additive ->
					val enclosed = keysWithin(region, projections, viewState.laneBounds, markRadiusPx)
					viewState.selectedKeys = if (additive) viewState.selectedKeys + enclosed else enclosed
				},
				onDismiss = { viewState.boxSelectArmed = false },
			)
			VerticalScrollbarOverlay(scrollState)
		}
		// Beneath the scroll region rather than over it: it describes the HORIZONTAL view, and it hides
		// itself entirely when the whole domain is framed.
		TrackWindowScrollbar(
			window = viewState.window,
			onWindowChange = { window -> viewState.window = window },
			labelColumnWidth = viewState.labelColumnWidth,
			modifier = Modifier.padding(end = SCROLLBAR_THICKNESS),
		)
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
 * @param Function onDragSelectedKeys Previews (commit = false) or applies (commit = true) a drag of the
 *   WHOLE selection by a fraction of each key's parameter range.  Only the sheet can do this - the
 *   selection spans sections and a section sees only its own projection.
 * @param Function selectedMarkDragDelta The in-flight group drag in THIS section's domain units, drawn on
 *   its selected marks so they travel with the one under the hand.  A lambda so the per-frame read lands in
 *   the lane's draw scope rather than recomposing the sheet.
 * @param Function onLaneBounds Reports each lane's window bounds, for the box-select marquee.
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
	onDragSelectedKeys: (Float, Boolean) -> Unit,
	selectedMarkDragDelta: () -> Float,
	onLaneBounds: (TrackRow, Rect) -> Unit,
) {
	val session = LocalEditorSession.current
	val liveParams = LocalLiveParams.current
	val keyableHover = LocalKeyableHover.current
	val commands = LocalCommands.current
	val colors = LocalUmamoColors.current
	val icons = LocalUmamoIcons
	val keymap = LocalKeymap.current
	val insertLabel = stringResource(Res.string.cmd_keyform_insert)
	// Resolved in COMPOSITION, not in the menu lambda: stringResource only runs while composing, and
	// the lambda fires on a pointer event.
	val selectOwnerLabels =
		ownerKindLabels().mapValues { (_, kindLabel) ->
			stringResource(
				Res.string.keyform_sheet_select_owner,
				kindLabel,
			)
		}
	val ownerKindLabels = ownerKindLabels()
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
				KeyformOwnerKind.Drawable -> TrackRowDecor(icons.mesh, colors.outlinerObjectTint)
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
			// A summary mark stands for every child key stacked at that value, so clicking it selects all
			// of them - which is what makes Delete and the arrow nudges work on a folded group too.
			val members = projection.summaryMembers(row.key, mark.keyIndex)
			onSelectedKeysChange(members?.toSet() ?: setOf(TrackKeyRef(parameter.id, row.key, mark.keyIndex)))
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
			liveParams?.preview(
				parameter.id,
				value.coerceIn(minOf(parameter.min, parameter.max), maxOf(parameter.min, parameter.max)),
			)
		},
		// One undo step per gesture, at its end - the same contract a slider drag has.
		onTrackScrubEnd = { _, _ -> liveParams?.commit(setOf(parameter.id)) },
		// The whole selection follows the mark under the hand rather than snapping to it on release,
		// which is what makes a group drag read as moving keys instead of as a deferred command.  The
		// model is untouched until the release; only what is DRAWN moves.
		onMarkDrag = { row, mark, at ->
			val dragged = TrackKeyRef(parameter.id, row.key, mark.keyIndex)
			groupDragFraction(parameter, selectedKeys, dragged, mark, at)?.let { fraction ->
				onDragSelectedKeys(fraction, false)
			}
		},
		selectedMarkDragDelta = selectedMarkDragDelta,
		onMarkDragEnd = { row, mark, releasedAt ->
			val members = projection.summaryMembers(row.key, mark.keyIndex)
			val track = projection.tracksByRowKey[row.key]
			if (session != null && members != null) {
				// Dragging a summary moves everything it stands for, to one destination, as one undo step -
				// they were stacked at a value and stay stacked.
				session.moveTrackKeys(
					members.mapNotNull { member ->
						projection.tracksByRowKey[member.rowKey]?.let { memberTrack ->
							Triple(memberTrack, parameter, member.keyIndex)
						}
					},
					releasedAt,
				)
				// Every member's ordinal may have changed on its own track, so the safe answer is to select
				// the group mark's new membership rather than guess - it is recomputed from the new model.
				onSelectedKeysChange(emptySet())
			} else if (session != null && track != null) {
				val dragged = TrackKeyRef(parameter.id, row.key, mark.keyIndex)
				val groupFraction = groupDragFraction(parameter, selectedKeys, dragged, mark, releasedAt)
				if (groupFraction != null) {
					onDragSelectedKeys(groupFraction, true)
				} else {
					// A key may cross its neighbours, which renumbers the axis - so the move reports where the
					// dragged key ended up and the selection is re-pointed at it.  Keeping the old ordinal
					// would silently leave the selection on whichever key took its place.
					val landedIndex = session.moveTrackKey(track, parameter, mark.keyIndex, releasedAt)
					if (dragged in selectedKeys) {
						onSelectedKeysChange(selectedKeys - dragged + TrackKeyRef(parameter.id, row.key, landedIndex))
					}
				}
			}
		},
		// Publishing the hovered row is what lets `I` / `Alt+I` aim at a track the way they already aim at a
		// Properties row: point at it and press.  The projection maps the row back to what it edits; a row
		// with no track ref (a group header, a blend-shape binding) publishes nothing, so the shortcut
		// falls through to its notice rather than acting on something it cannot address.  The hover carries
		// THIS section's parameter - the selection's active member can be the other axis of a linked pad.
		onLaneBounds = { row, bounds -> onLaneBounds(row, bounds) },
		onLaneHover = { row, hit ->
			projection.tracksByRowKey[row.key]?.let { track ->
				if (hit == null) {
					keyableHover?.exit(track)
				} else {
					keyableHover?.enter(KeyformHover(track, hit.value, hit.mark?.keyIndex, parameter.id))
				}
			}
		},
		// The LABEL half of a row answers a different question from the lane: the lane is about a key at a
		// position, the label is about the thing the row names.  Only a GROUP row names a rig entity, and
		// only a selectable one - a glue has no SelectionTarget - so every other row gets an empty list and
		// no gesture at all.
		labelMenuItems = { row ->
			val target = projection.ownerByRowKey[row.key]?.let(::selectionTargetOf)
			val ownerKind = projection.ownerKindByRowKey[row.key]
			if (session == null || target == null || ownerKind == null) {
				emptyList()
			} else {
				listOf(
					MenuItem.Action(
						label = selectOwnerLabels.getValue(ownerKind),
						onSelect = { session.setSelection(Selection(setOf(target), target)) },
					),
				)
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
			val summaryMembers = hitMark?.let { mark -> projection.summaryMembers(hit.row.key, mark.keyIndex) }
			when {
				// A summary mark stands for several keys, so its menu removes them all in one step.  There
				// is no Insert counterpart: a folded group cannot say which of its tracks a new key belongs
				// on, and picking one would be a guess.
				session != null && summaryMembers != null ->
					listOf(
						MenuItem.Action(
							label = deleteLabel,
							onSelect = {
								session.removeTrackKeys(
									summaryMembers.mapNotNull { member ->
										projection.tracksByRowKey[member.rowKey]?.let { memberTrack ->
											Triple(memberTrack, parameter, member.keyIndex)
										}
									},
								)
								onSelectedKeysChange(emptySet())
							},
						),
					)

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
								keyableHover?.enter(
									KeyformHover(
										track,
										hit.value,
										keyIndex = null,
										parameterId = parameter.id,
									),
								)
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
 * The fraction of [parameter]'s range a drag of [mark] to [at] represents, or null when it is not a GROUP
 * drag at all.
 *
 * Null - meaning "handle this as an ordinary single-key move" - whenever the dragged mark is not itself
 * selected, or is the only thing selected, or the parameter has no range to take a fraction of.
 *
 * A fraction rather than an absolute delta because a selection can span two parameters (a linked pad shows
 * both axes at once) whose ranges differ by orders of magnitude.  Within ONE parameter a fraction of its
 * range IS the distance the hand moved.
 *
 * Shared by the live preview and the release so the two cannot disagree about which gesture this is - the
 * failure mode being a drag that previews as a group and commits as a single key, or the reverse.
 *
 * @param Parameter parameter The section's parameter.
 * @param Set selectedKeys The sheet-wide key selection.
 * @param TrackKeyRef dragged The key under the hand.
 * @param TrackKeyMark mark Its mark, for the position the drag started from.
 * @param Float at The pointer's current (or released) domain position.
 * @return Float? The signed fraction, or null when this is not a group drag.
 */
private fun groupDragFraction(
	parameter: Parameter,
	selectedKeys: Set<TrackKeyRef>,
	dragged: TrackKeyRef,
	mark: TrackKeyMark,
	at: Float,
): Float? {
	if (dragged !in selectedKeys || selectedKeys.size < 2) {
		return null
	}
	val span = maxOf(parameter.max, parameter.min) - minOf(parameter.max, parameter.min)
	return if (span > 0f) (at - mark.position) / span else null
}

/**
 * The selection target a keyform owner names, or null when it names nothing selectable.
 *
 * Glue is the null case, and deliberately so: it has no SelectionTarget, no outliner entry, and is
 * addressed by a mesh pair rather than an id, so there is nothing for a "select this" to put in the
 * selection.  See TODO.md's Claude Note on glue having no editable home.
 *
 * @param KeyformOwner owner The row's owner.
 * @return SelectionTarget? What to select, or null.
 */
private fun selectionTargetOf(owner: KeyformOwner): SelectionTarget? =
	when (owner) {
		is KeyformOwner.Drawable -> SelectionTarget.Drawable(owner.id)
		is KeyformOwner.Part -> SelectionTarget.Part(owner.id)
		is KeyformOwner.Deformer -> SelectionTarget.Deformer(owner.id)
		is KeyformOwner.Glue -> null
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
		marks =
			marks.map { mark ->
				mark.copy(
					selected =
						TrackKeyRef(
							parameterId,
							key,
							mark.keyIndex,
						) in selectedKeys,
				)
			},
		children = children.map { child -> child.withSelection(parameterId, selectedKeys) },
	)

/**
 * Every key the window-space [region] encloses, across every section.
 *
 * Resolved against the lanes' own reported bounds rather than computed from row heights: sections fold,
 * groups collapse, and the sheet scrolls, so the only reliable answer to "where is this row" is the one
 * the row gave during layout.  A mark counts when its lane overlaps the region vertically AND its drawn
 * position falls inside it horizontally, using the same pixel mapping the marks were drawn with.
 *
 * @param Rect region The marquee, in window coordinates.
 * @param List projections Each targeted parameter and its tracks.
 * @param Map laneBounds Each row key's last reported window bounds.
 * @param Float markRadiusPx The sheet's mark radius, which is also its lane end inset.
 * @return Set<TrackKeyRef> The enclosed keys.
 */
private fun keysWithin(
	region: Rect,
	projections: List<Pair<Parameter, KeyformSheetProjection>>,
	laneBounds: Map<String, Rect>,
	markRadiusPx: Float,
): Set<TrackKeyRef> {
	val enclosed = mutableSetOf<TrackKeyRef>()
	for ((parameter, projection) in projections) {
		val (domainStart, domainEnd) = parameterDomain(parameter)
		for (row in projection.rows) {
			// Only rows with a track ref: a group header names an owner and a blend-shape row is not a
			// keyform grid, so neither has keys a selection could act on.
			collectRowsWithin(
				row,
				region,
				laneBounds,
				parameter,
				domainStart,
				domainEnd,
				markRadiusPx,
				projection,
				enclosed,
			)
		}
	}
	return enclosed
}

/** Walks [row] and its children, adding every enclosed key to [into]. */
private fun collectRowsWithin(
	row: TrackRow,
	region: Rect,
	laneBounds: Map<String, Rect>,
	parameter: Parameter,
	domainStart: Float,
	domainEnd: Float,
	markRadiusPx: Float,
	projection: KeyformSheetProjection,
	into: MutableSet<TrackKeyRef>,
) {
	val bounds = laneBounds[row.key]
	if (bounds != null && projection.tracksByRowKey.containsKey(row.key) && bounds.overlapsVertically(region)) {
		val usable = bounds.width - markRadiusPx * 2f
		if (usable > 0f) {
			val axis = TrackAxis(domainStart, domainEnd)
			for (mark in row.marks) {
				val drawnX = bounds.left + markRadiusPx + axis.fractionOf(mark.position) * usable
				if (drawnX in region.left..region.right) {
					into.add(TrackKeyRef(parameter.id, row.key, mark.keyIndex))
				}
			}
		}
	}
	for (child in row.children) {
		collectRowsWithin(child, region, laneBounds, parameter, domainStart, domainEnd, markRadiusPx, projection, into)
	}
}

/** Whether two rectangles share any vertical extent - the marquee's row test. */
private fun Rect.overlapsVertically(other: Rect): Boolean = top < other.bottom && bottom > other.top

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
			KeyformOwnerKind.Drawable -> stringResource(Res.string.owner_kind_drawable)
			KeyformOwnerKind.WarpDeformer -> stringResource(Res.string.owner_kind_warp_deformer)
			KeyformOwnerKind.RotationDeformer -> stringResource(Res.string.owner_kind_rotation_deformer)
			KeyformOwnerKind.Part -> stringResource(Res.string.owner_kind_part)
			KeyformOwnerKind.Glue -> stringResource(Res.string.owner_kind_glue)
		}
	}
