package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterSelection
import org.umamo.edit.TrackKeyRef
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.ui.kit.SCROLLBAR_THICKNESS
import org.umamo.ui.kit.VerticalScrollbarOverlay
import org.umamo.ui.kit.holdsHoverIcon
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.umamoPointerIcon
import org.umamo.ui.tracks.TRACK_MARK_RADIUS
import org.umamo.ui.tracks.TrackSheetBackdrop
import org.umamo.ui.tracks.TrackSheetMarqueeOverlay
import org.umamo.ui.tracks.TrackSheetSeparatorOverlay
import org.umamo.ui.tracks.TrackWindow
import org.umamo.ui.tracks.TrackWindowScrollbar
import org.umamo.ui.tracks.trackWindowGestures
import org.umamo.ui.viewport.shownPendingLattices
import org.umamo.ui.workspace.AreaScope
import org.umamo.ui.workspace.KeyformSheetSurface
import org.umamo.ui.workspace.LocalKeyformSheetViews

/*
 * The keyform sheet space.  This file is the body's entry point; the rest of the package, by role:
 *
 *   KeyformSheetHeaderControls.kt   the area header's controls: the track-kind filter
 *   KeyformSheetViewState.kt        the view state the header and the body share
 *   KeyformSheetSection.kt          one parameter's section: its header, its tracks, and the empty notice
 *   KeyformSheetMenus.kt            the entries of the lane and row label context menus
 *   KeyformSheetLabels.kt           the localized chrome
 *
 * and, free of Compose so commonTest pins them directly:
 *
 *   KeyformSheetRows.kt             the projection of a rig into sheet rows, the one place the sheet reads the model
 *   KeyformSheetSelection.kt        what a click does to the key selection, how its refs resolve, and the group drag
 *   KeyformSheetMarquee.kt          which keys a box-select band encloses
 *
 * The track widgets it draws with are org.umamo.ui.tracks, which knows nothing of parameters or keyforms;
 * everything that does know lives here.  What a key edit does to the key selection lives in :edit
 * (KeyformKeySelection.kt).
 */

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
 * Clicking a mark scrubs the parameter onto it and selects it; dragging a mark that is not selected selects
 * it in place of the selection as the drag starts, and a drag then moves the whole selection - a folded
 * group's summary mark stands for every key stacked under it (a key may cross its neighbours, and stops only
 * at the parameter's range); Delete removes the selection as one undo step; right-clicking a lane inserts a
 * key there or removes the one under the pointer; Box Select (B) arms a marquee that adds the keys it
 * encloses to the selection.
 *
 * This composable is the wiring: it derives the sections, holds the scroll and the drag cursor, registers
 * the area's command surface, and hands each section to [KeyformSheetSection].
 *
 * @param AreaScope scope The hosting area's scope.
 */
@Composable
internal fun KeyformSheetSpace(scope: AreaScope) {
	val colors = LocalUmamoColors.current
	val puppet = LocalPuppet.current
	val session = LocalEditorSession.current
	val viewState = scope.spaceState(KEYFORM_SHEET_VIEW_STATE_KEY) { KeyformSheetViewState() }

	val parameterSelection by remember(session) {
		session?.parameterSelection ?: MutableStateFlow(ParameterSelection())
	}.collectAsState()

	// The key selection lives on the SESSION, not on this area's view state: it is snapshotted, and undo can
	// only restore what the session holds.  Two open sheets therefore share one selection - the same trade
	// the parameter target already makes.  A sheet with no session (previews, tests) simply has none.
	val selectedKeys by remember(session) {
		session?.keySelection ?: MutableStateFlow(emptySet())
	}.collectAsState()
	// The warps holding an unkeyed Pose-mode lattice the viewport shows, and the pose it was shaped at: their geometry
	// rows ring the pose so a rigger sees that keying there would store something new.
	val shownPendingLattices by remember(session) {
		session?.let { live -> combine(live.pendingLatticeEdits, live.mode) { pending, mode -> shownPendingLattices(mode, pending) } } ?: MutableStateFlow(emptyMap())
	}.collectAsState(emptyMap())
	val sessionPose by remember(session) {
		session?.pose ?: MutableStateFlow(emptyMap())
	}.collectAsState()
	// Recording a selection as its own undo step, versus folding it into the step a following commit is
	// about to record.  See EditorSession.stageKeySelection for which is correct where.
	val setSelectedKeys: (Set<TrackKeyRef>) -> Unit = { keys -> session?.setKeySelection(keys) }
	val stageSelectedKeys: (Set<TrackKeyRef>) -> Unit = { keys -> session?.stageKeySelection(keys) }

	// Channel and track labels are Umamo chrome, so they resolve from resources here and are injected into
	// the Compose-free projection.  Item names are the user's own data and are never translated.
	val sheetLabels = keyformSheetLabels()
	val labels = rememberKeyformTrackLabels(sheetLabels)

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
				// Only while a drag is live: otherwise the label column's own hover cursors, and the separator band's,
				// keep winning over this.
				.holdsHoverIcon(umamoPointerIcon(LocalUmamoCursors.ewScroll), held = horizontalDrag)
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
				return@Box
			}

			// The filter is a projection INPUT, not a draw-time skip: a filtered-out track has to be absent
			// from the row tree so an owner left with nothing loses its group row too, and so a summary mark
			// never stands for a key the sheet is not showing.
			val filter =
				remember(viewState.showGeometry, viewState.showChannels, viewState.showBlendShapes) {
					KeyformTrackFilter(viewState.showGeometry, viewState.showChannels, viewState.showBlendShapes)
				}
			val pendingWarpIds = shownPendingLattices.keys
			val projections =
				remember(puppet, targetedParameters, labels, filter, pendingWarpIds, sessionPose) {
					targetedParameters.map { parameter ->
						parameter to keyformSheetRows(puppet, parameter.id, labels, filter, pendingWarpIds, sessionPose[parameter.id] ?: parameter.default)
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
				val everythingFiltered = projections.any { (_, projection) -> projection.hiddenByFilter }
				EmptySheetNotice(emptySheetMessage(everythingFiltered), Modifier.fillMaxSize())

				return@Box
			}

			// Dragging one mark of a multi-key selection drags the whole selection, which means resolving refs
			// from EVERY section.  The section that owns the gesture sees only its own projection, so the drag
			// runs here and is handed down as an action.  The projections are read through state as the drag
			// runs: an action holding the list itself would be a new action after every edit, for every section.
			val currentProjections = rememberUpdatedState(projections)
			val dragSelectedKeys: (Float, Boolean) -> Unit = { fraction, commit ->
				viewState.dragKeySelection(session, currentProjections.value, fraction, commit)
			}
			// After both early returns on purpose: a sheet with nothing to show offers the shell's commands
			// nothing, so they fall through to a sheet that does.
			KeyformSheetSurfaceEffect(scope.areaId, session, viewState, projections, dragSelectedKeys)
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
							EmptySheetNotice(emptySheetMessage(projection.hiddenByFilter), Modifier.fillMaxWidth())
						} else {
							KeyformSheetSection(
								parameter = parameter,
								projection = projection,
								labels = sheetLabels,
								selectedKeys = selectedKeys,
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
								onSelectedKeysChange = setSelectedKeys,
								onStageSelectedKeys = stageSelectedKeys,
								onDragSelectedKeys = dragSelectedKeys,
								// The preview fraction is of the parameter's RANGE; a lane draws in its own domain units, so
								// each section converts with its own span - which is what keeps a linked pad's two axes moving
								// together on screen despite unrelated ranges.
								selectedMarkDragDelta = {
									viewState.dragPreviewFraction?.times(
										maxOf(parameter.max, parameter.min) - minOf(parameter.max, parameter.min),
									)
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
					val enclosed =
						keysWithin(
							region,
							projections,
							viewState.laneBounds,
							markRadiusPx,
							viewState.window,
							viewState.expandedKeys,
							viewState.collapsedParameters,
						)
					setSelectedKeys(if (additive) selectedKeys + enclosed else enclosed)
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
 * Registers this area's command surface with the shell's open-sheet registry for as long as the sheet has
 * something to show.
 *
 * The sheet's commands (delete/nudge selected keys, frame all) live at SHELL level - per-area registration
 * made two open sheets clobber each other in the last-write-wins registry.  The area only registers its
 * command surface here; the lambdas read the live view state and the CURRENT projections and drag at
 * dispatch time, through [rememberUpdatedState], so the effect never needs to re-run on an edit or a
 * selection change.  A ref whose row is gone simply drops out of selectedTracks, which is what makes a stale
 * selection harmless rather than dangerous.
 *
 * @param String areaId The hosting area's id.
 * @param EditorSession? session The editing session, or null with none.
 * @param KeyformSheetViewState viewState The area's view state.
 * @param List projections Each targeted parameter and its tracks.
 * @param Function dragSelectedKeys Previews or applies a drag of the whole selection; a nudge applies one.
 */
@Composable
private fun KeyformSheetSurfaceEffect(
	areaId: String,
	session: EditorSession?,
	viewState: KeyformSheetViewState,
	projections: List<Pair<Parameter, KeyformSheetProjection>>,
	dragSelectedKeys: (Float, Boolean) -> Unit,
) {
	val keyformSheetViews = LocalKeyformSheetViews.current
	val currentProjections = rememberUpdatedState(projections)
	val currentDragSelectedKeys = rememberUpdatedState(dragSelectedKeys)
	DisposableEffect(keyformSheetViews, areaId) {
		if (keyformSheetViews == null) {
			onDispose {}
		} else {
			fun selectedTracks(): List<Triple<KeyformTrackRef, Parameter, Int>> =
				resolveTrackKeys(session?.keySelection?.value.orEmpty(), currentProjections.value).map { (_, key) -> key }

			val surface =
				KeyformSheetSurface(
					selectedTracks = ::selectedTracks,
					hasSelection = { session?.keySelection?.value?.isNotEmpty() == true },
					frameAll = { viewState.window = TrackWindow.Full },
					armBoxSelect = { viewState.boxSelectArmed = true },
					boxSelectArmed = { viewState.boxSelectArmed },
					disarmBoxSelect = { viewState.boxSelectArmed = false },
					// The same path a mark drag commits through, so a nudge and a drag cannot disagree about
					// clamping or about where the selection points afterwards.
					nudgeSelection = { fraction -> currentDragSelectedKeys.value(fraction, true) },
				)
			keyformSheetViews.register(areaId, surface)
			onDispose { keyformSheetViews.unregister(areaId, surface) }
		}
	}
}