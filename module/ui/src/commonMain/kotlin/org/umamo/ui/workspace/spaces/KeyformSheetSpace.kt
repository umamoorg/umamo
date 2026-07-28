package org.umamo.ui.workspace.spaces

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ParameterSelection
import org.umamo.edit.insertChannelKeyAt
import org.umamo.edit.moveChannelKey
import org.umamo.edit.removeChannelKeyAt
import org.umamo.edit.removeChannelKeys
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.action.Command
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.Text
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.tracks.TRACK_LABEL_COLUMN_DEFAULT_WIDTH
import org.umamo.ui.tracks.TrackAxis
import org.umamo.ui.tracks.TrackRow
import org.umamo.ui.tracks.TrackRowDecor
import org.umamo.ui.tracks.TrackSheet
import org.umamo.ui.tracks.TrackSheetBackdrop
import org.umamo.ui.workspace.AreaScope

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
	 * Whether [expandedKeys] has been seeded yet.
	 *
	 * A fresh sheet opens with every group expanded (an all-collapsed sheet looks identical to one with
	 * nothing keyed), but "collapse everything" has to stay reachable - so the seed happens ONCE rather
	 * than whenever the set is empty.
	 */
	var seeded: Boolean = false
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
	Box(modifier = Modifier.fillMaxSize().background(colors.panelBackground)) {
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
			viewState.seeded = true
			viewState.expandedKeys = projections.flatMap { (_, projection) -> projection.groupRowKeys }.toSet()
		}
		// Which keys are selected, so Delete has something to act on. Cleared when the projections change,
		// because a row key can outlive the key it pointed at (a removal renumbers nothing, but a rebind can
		// replace the whole track) and a stale selection would delete the wrong thing.
		var selectedKeys by remember(projections) { mutableStateOf(emptySet<TrackKeyRef>()) }
		if (projections.all { (_, projection) -> projection.rows.isEmpty() }) {
			EmptySheetNotice(stringResource(Res.string.keyform_sheet_no_tracks))
			return@Box
		}
		// Delete removes every selected key as ONE undo step. Registered as a command rather than wired to a
		// key handler here so the keymap owns the binding, per the action-registry rule; the sheet only
		// supplies what "the current selection" means while it is on screen.
		val commands = LocalCommands.current
		DisposableEffect(commands, session, projections, selectedKeys) {
			val deleteCommand =
				Command("keyform.deleteSelectedKeys", title = Res.string.cmd_keyform_delete_keys) {
					val removals =
						selectedKeys.mapNotNull { keyRef ->
							val entry = projections.firstOrNull { (parameter, _) -> parameter.id == keyRef.parameterId }
							entry?.second?.targetsByRowKey?.get(keyRef.rowKey)?.let { target ->
								Triple(target, entry.first, keyRef.position)
							}
						}
					if (session != null && removals.isNotEmpty()) {
						session.removeChannelKeys(removals)
					}
				}
			commands.register(deleteCommand)
			onDispose { commands.unregister(deleteCommand.id) }
		}
		// ONE outer scroll over all the sections; each TrackSheet lays its rows out eagerly for exactly this
		// reason (a lazy list nested in a scroll fights it for the gesture).
		Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
			for ((parameter, projection) in projections) {
				key(parameter.id) {
					// The section header names which parameter the ruler below belongs to.  Shown even for a
					// single section: without it the sheet is a set of numbers with no stated domain.
					Text(
						text = parameter.name,
						style = LocalUmamoTypography.current.labelMedium,
						color = colors.textMuted,
						modifier =
							Modifier
								.fillMaxWidth()
								.background(colors.tabBackground)
								.padding(horizontal = 8.dp, vertical = 4.dp),
					)
					if (projection.rows.isEmpty()) {
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
							selectedKeys = selectedKeys,
							playhead = liveParams?.observedValues?.get(parameter.id) ?: parameter.default,
							labelColumnWidth = viewState.labelColumnWidth,
							onLabelColumnWidthChange = { width -> viewState.labelColumnWidth = width },
							expandedKeys = viewState.expandedKeys,
							onToggleExpanded = { row ->
								viewState.expandedKeys =
									if (row.key in viewState.expandedKeys) {
										viewState.expandedKeys - row.key
									} else {
										viewState.expandedKeys + row.key
									}
							},
							onSelectedKeysChange = { keys -> selectedKeys = keys },
						)
					}
				}
			}
		}
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
 * @param Float playhead The live scrub value of this parameter.
 * @param Dp labelColumnWidth The label column's width, shared by every section.
 * @param Function onLabelColumnWidthChange Publishes a dragged column width.
 * @param Set<String> expandedKeys The open group rows, shared by every section.
 * @param Function onToggleExpanded Publishes a chevron click.
 * @param Function onSelectedKeysChange Publishes a new selection to the sheet.
 */
@Composable
private fun KeyformSheetSection(
	parameter: Parameter,
	projection: KeyformSheetProjection,
	selectedKeys: Set<TrackKeyRef>,
	playhead: Float,
	labelColumnWidth: Dp,
	onLabelColumnWidthChange: (Dp) -> Unit,
	expandedKeys: Set<String>,
	onToggleExpanded: (TrackRow) -> Unit,
	onSelectedKeysChange: (Set<TrackKeyRef>) -> Unit,
) {
	val session = LocalEditorSession.current
	val liveParams = LocalLiveParams.current
	val colors = LocalUmamoColors.current
	val icons = LocalUmamoIcons
	val insertLabel = stringResource(Res.string.cmd_keyform_insert)
	val removeLabel = stringResource(Res.string.cmd_keyform_remove)
	val rows =
		remember(projection, selectedKeys, parameter.id) {
			projection.rows.map { row -> row.withSelection(parameter.id, selectedKeys) }
		}
	val (domainStart, domainEnd) = parameterDomain(parameter)
	TrackSheet(
		rows = rows,
		axis = TrackAxis(domainStart, domainEnd),
		// The playhead is the live scrub value, so the sheet reads as a view OF the current pose rather
		// than a static list beside it.
		playhead = playhead,
		modifier = Modifier.fillMaxWidth(),
		labelColumnWidth = labelColumnWidth,
		onLabelColumnWidthChange = onLabelColumnWidthChange,
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
			onSelectedKeysChange(setOf(TrackKeyRef(parameter.id, row.key, mark.position)))
			liveParams?.preview(parameter.id, mark.position)
			liveParams?.commit(setOf(parameter.id))
		},
		// Clicking empty track drops the selection, matching every other list in the editor.
		onTrackClick = { _, _ -> onSelectedKeysChange(emptySet()) },
		onMarkDragEnd = { row, mark, releasedAt ->
			val target = projection.targetsByRowKey[row.key]
			if (session != null && target != null) {
				session.moveChannelKey(target, parameter, mark.position, releasedAt)
				// The grid clamps at the neighbours, so the released position and the stored one can
				// differ; dropping the selection is honest about not knowing where the key landed.
				onSelectedKeysChange(emptySet())
			}
		},
		laneMenuItems = { hit ->
			// A group row has no target of its own (it is the owner, not a channel), and a geometry or
			// blend-shape row is not authorable yet - so both get an empty menu rather than actions that
			// would silently do nothing.
			val target = projection.targetsByRowKey[hit.row.key]
			val hitMark = hit.mark
			when {
				session == null || target == null -> emptyList()
				hitMark != null ->
					listOf(
						MenuItem.Action(
							label = removeLabel,
							onSelect = { session.removeChannelKeyAt(target, parameter, hitMark.position) },
						),
					)

				else ->
					listOf(
						MenuItem.Action(
							label = insertLabel,
							onSelect = { session.insertChannelKeyAt(target, parameter, hit.value) },
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
		marks = marks.map { mark -> mark.copy(selected = TrackKeyRef(parameterId, key, mark.position) in selectedKeys) },
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
