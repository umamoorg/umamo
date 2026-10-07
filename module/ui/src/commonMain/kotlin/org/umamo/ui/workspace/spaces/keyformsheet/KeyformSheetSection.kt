package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.TrackKeyRef
import org.umamo.edit.keyform.moveTrackKeySelectingIt
import org.umamo.runtime.model.Parameter
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.button.DisclosureChevron
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoColors
import org.umamo.ui.theme.UmamoIcons
import org.umamo.ui.tracks.TrackAxis
import org.umamo.ui.tracks.TrackRow
import org.umamo.ui.tracks.TrackRowDecor
import org.umamo.ui.tracks.TrackSheet
import org.umamo.ui.tracks.TrackWindow
import org.umamo.ui.workspace.KeyformHover
import org.umamo.ui.workspace.LocalKeyableHover

/**
 * A parameter section's header: its name and the chevron that folds the section away.
 *
 * @param String name The parameter's display name (user data - never translated).
 * @param Boolean collapsed Whether the section is folded.
 * @param Function onToggle Invoked when the header is clicked.
 */
@Composable
internal fun SectionHeader(name: String, collapsed: Boolean, onToggle: () -> Unit) {
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
		DisclosureChevron(expanded = !collapsed, tint = colors.textMuted, glyphSize = 14.dp)
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
 * @param KeyformSheetLabels labels The sheet's chrome, for the menus.
 * @param Set<TrackKeyRef> selectedKeys The sheet-wide key selection (shared across sections).
 * @param TrackWindow window The visible slice of the parameter's range, shared by every section.
 * @param Dp labelColumnWidth The label column's width, shared by every section.
 * @param Set<String> expandedKeys The open group rows, shared by every section.
 * @param Function onToggleExpanded Publishes a chevron click.
 * @param Function onSelectedKeysChange Publishes a new selection, recording it as its own undo step.
 * @param Function onStageSelectedKeys Publishes a new selection WITHOUT a step of its own, for a gesture
 *   that commits one immediately afterwards (or has just committed one) - see EditorSession.stageKeySelection.
 * @param Function onDragSelectedKeys Previews (commit = false) or applies (commit = true) a drag of the
 *   WHOLE selection by a fraction of each key's parameter range.  Only the sheet can do this - the
 *   selection spans sections and a section sees only its own projection.
 * @param Function selectedMarkDragDelta The in-flight group drag in THIS section's domain units, drawn on
 *   its selected marks so they travel with the one under the hand.  A lambda so the per-frame read lands in
 *   the lane's draw scope rather than recomposing the sheet.
 * @param Function onLaneBounds Reports each lane's window bounds, for the box-select marquee.
 */
@Composable
internal fun KeyformSheetSection(
	parameter: Parameter,
	projection: KeyformSheetProjection,
	labels: KeyformSheetLabels,
	selectedKeys: Set<TrackKeyRef>,
	window: TrackWindow,
	labelColumnWidth: Dp,
	expandedKeys: Set<String>,
	onToggleExpanded: (TrackRow) -> Unit,
	onSelectedKeysChange: (Set<TrackKeyRef>) -> Unit,
	onStageSelectedKeys: (Set<TrackKeyRef>) -> Unit,
	onDragSelectedKeys: (Float, Boolean) -> Unit,
	selectedMarkDragDelta: () -> Float?,
	onLaneBounds: (TrackRow, Rect) -> Unit,
) {
	val session = LocalEditorSession.current
	val liveParams = LocalLiveParams.current
	val keyableHover = LocalKeyableHover.current
	val commands = LocalCommands.current
	val colors = LocalUmamoColors.current
	val icons = LocalUmamoIcons
	val keymap = LocalKeymap.current
	// The playhead follows the live scrub through snapshotFlow rather than a composition read:
	// observedValues is one whole-map state replaced on every preview move of ANY parameter, so reading
	// it while composing invalidated the entire sheet per pointer move (rememberParameterPoseState
	// documents the same rule).  The initial read is deliberately unobserved; the flow delivers every later change to
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
		decorFor = { row -> trackRowDecorOf(projection.ownerKindByRowKey[row.key], icons, colors) },
		// Clicking a mark selects it AND scrubs to it: selection is what Delete acts on, and scrubbing is
		// how you land the pose exactly on a key without hunting with the slider. The two never conflict,
		// so doing both is strictly more useful than choosing.
		//
		// SHIFT+click is the exception on both counts: it TOGGLES rather than replaces (toggle, not
		// add-only, because it is the only gesture that can take a single mark back OUT of a selection
		// without starting over), and it does NOT scrub - building a multi-key selection is a selection
		// act, and having the pose jump to each mark on the way would fight the very comparison the
		// selection is being built for.  The marquee stays add-only, which is the convention for a
		// region gesture; a click is where toggling belongs.
		onMarkClick = { row, mark, additive ->
			// A summary mark stands for every child key stacked at that value, so clicking it selects all
			// of them - which is what makes Delete and the arrow nudges work on a folded group too.
			val clicked = projection.keysOfMark(parameter.id, row.key, mark.keyIndex).toSet()
			if (additive) {
				// Nothing follows a shift-click, so it records a step of its own.
				onSelectedKeysChange(selectionAfterAdditiveClick(selectedKeys, clicked))
			} else if (session != null && liveParams != null) {
				// ONE undo step for the whole click, named for the selection rather than for the scrub it
				// implies: the user clicked a keyframe, and the pose landing on it is the consequence.
				// Recorded through the session rather than staged behind liveParams.commit because a click on
				// the key the pose ALREADY sits on moves nothing - the pose commit would short-circuit and the
				// selection would never reach history at all.
				liveParams.preview(parameter.id, mark.position)
				session.selectKeysAtPose(clicked, liveParams.values)
			} else {
				onSelectedKeysChange(clicked)
			}
		},
		// Pressing or dragging empty track scrubs the parameter, so the whole track region works like the
		// ruler of a timeline rather than only the marks being live.  The press also drops the key
		// selection, matching every other list in the editor.
		onTrackScrub = { _, value, additive ->
			// A SHIFT press keeps the selection: shift-clicking is how a multi-key selection is built, and a
			// near-miss on a mark should not wipe the work rather than merely failing to add to it.
			if (!additive) {
				// Staged: onTrackScrubEnd commits the scrub, and the clear belongs to that same step.
				// It confirms the clear there too - the commit records nothing when the press lands on the
				// value the playhead already holds, and a selection lost that way has no undo.
				onStageSelectedKeys(emptySet())
			}
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
		onTrackScrubEnd = { _, _ ->
			liveParams?.commit(setOf(parameter.id))
			// CONFIRM the selection the press staged (see EditorSession.stageKeySelection): a no-op when the
			// commit above folded it into the scrub's step, and a step of its own when the commit declined
			// because the pose never moved.  Read live rather than from `selectedKeys`, which is this
			// composition's snapshot of a selection the press has since changed.
			session?.setKeySelection(session.keySelection.value)
		},
		// A drag works on the selection, and a mark that is not selected is selected as the drag starts:
		// its keys replace the selection, as a click on it would.  STAGED, so the release records that
		// selection and the move as one step.  The whole selection then follows the mark under the hand
		// rather than snapping to it on release, which is what makes a group drag read as moving keys
		// instead of as a deferred command.  The model is untouched until the release; only what is DRAWN
		// moves.
		onMarkDrag = { row, mark, at ->
			val draggedKeys = projection.keysOfMark(parameter.id, row.key, mark.keyIndex)
			val selection = selectionDraggedWith(selectedKeys, draggedKeys)
			if (selection != selectedKeys) {
				onStageSelectedKeys(selection)
			}
			groupDragFraction(parameter, selection, draggedKeys, row.key in projection.groupRowKeys, mark, at)?.let { fraction ->
				onDragSelectedKeys(fraction, false)
			}
		},
		selectedMarkDragDelta = selectedMarkDragDelta,
		onMarkDragEnd = { row, mark, releasedAt ->
			val draggedKeys = projection.keysOfMark(parameter.id, row.key, mark.keyIndex)
			val summary = row.key in projection.groupRowKeys
			val track = projection.tracksByRowKey[row.key]
			if (session != null) {
				val selection = selectionDraggedWith(selectedKeys, draggedKeys)
				val groupFraction = groupDragFraction(parameter, selection, draggedKeys, summary, mark, releasedAt)
				if (groupFraction != null) {
					// Commits the LIVE selection, which is the one the drag's start staged: the lane reports a
					// drag's first move before it can report its release.
					onDragSelectedKeys(groupFraction, true)
				} else if (!summary && track != null) {
					// The dragged key ends up selected at the ordinal it lands on.
					session.moveTrackKeySelectingIt(draggedKeys.single(), track, parameter, releasedAt, selection)
				} else {
					// Nothing moves (a parameter with no range), but the selection the drag's start staged
					// still has to be CONFIRMED, or it would reach no history at all.
					session.setKeySelection(session.keySelection.value)
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
					keyableHover?.enter(KeyformHover(track, hit.value, hit.mark?.keyIndex, parameter.id, row.key))
				}
			}
		},
		labelMenuItems = { row -> labelMenuItemsFor(row, projection, session, labels) },
		laneMenuItems = { hit -> laneMenuItemsFor(hit, parameter, projection, session, keyableHover, commands, keymap, labels) },
	)
}

/**
 * A row's icon: its owner's, on a group row, and none on any other.
 *
 * Only group rows carry an icon: they are the rows that name a thing in the rig.  A channel track names a
 * property, and several of those (opacity, draw order) have no icon in the set - giving some of them art
 * and not others would read as a missing glyph rather than a category.
 *
 * @param KeyformOwnerKind? ownerKind The owner kind behind a group row, or null for any other row.
 * @param UmamoIcons icons The icon set.
 * @param UmamoColors colors The palette.
 * @return TrackRowDecor The row's decor.
 */
private fun trackRowDecorOf(ownerKind: KeyformOwnerKind?, icons: UmamoIcons, colors: UmamoColors): TrackRowDecor =
	when (ownerKind) {
		KeyformOwnerKind.Drawable -> TrackRowDecor(icons.mesh, colors.outlinerObjectTint)
		KeyformOwnerKind.Part -> TrackRowDecor(icons.part, colors.outlinerObjectTint)
		KeyformOwnerKind.WarpDeformer -> TrackRowDecor(icons.warpDeformer, colors.outlinerDeformTint)
		KeyformOwnerKind.RotationDeformer -> TrackRowDecor(icons.rotationDeformer, colors.outlinerDeformTint)
		KeyformOwnerKind.Glue -> TrackRowDecor(icons.linked, colors.outlinerDeformTint)
		null -> TrackRowDecor()
	}

/**
 * The centered muted notice shown where the sheet, or one section of it, has nothing to draw.
 *
 * @param String message The notice.
 * @param Modifier modifier The notice's extent: the whole sheet, or a section's width.
 */
@Composable
internal fun EmptySheetNotice(message: String, modifier: Modifier = Modifier) {
	Box(modifier = modifier.padding(12.dp), contentAlignment = Alignment.Center) {
		Text(
			text = message,
			style = LocalUmamoTypography.current.bodyMedium,
			color = LocalUmamoColors.current.textMuted,
		)
	}
}

/**
 * What an empty sheet, or an empty section, says: that the filters hide its rows, or that nothing is keyed.
 *
 * "Nothing is keyed" and "you hid it" are opposite diagnoses, and only one of them is the user's own doing -
 * saying the first while a filter is eating the rows sends them looking for a rig problem that is not there.
 *
 * @param Boolean hiddenByFilter Whether the filters dropped rows that would otherwise be listed.
 * @return String The notice.
 */
@Composable
internal fun emptySheetMessage(hiddenByFilter: Boolean): String =
	stringResource(if (hiddenByFilter) Res.string.keyform_sheet_all_filtered else Res.string.keyform_sheet_no_tracks)