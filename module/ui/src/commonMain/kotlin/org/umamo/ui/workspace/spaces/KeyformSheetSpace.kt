package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ParameterSelection
import org.umamo.edit.moveChannelKey
import org.umamo.edit.removeChannelKeys
import org.umamo.runtime.model.FormChannel
import org.umamo.ui.action.Command
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.kit.Text
import org.umamo.ui.model.LocalEditorSession
import org.umamo.ui.model.LocalLiveParams
import org.umamo.ui.model.LocalPuppet
import org.umamo.ui.resources.*
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.tracks.TrackAxis
import org.umamo.ui.tracks.TrackSheet
import org.umamo.ui.workspace.AreaScope

/**
 * The keyform sheet: for the parameter targeted in the Parameters panel, one track per (item, channel)
 * keyed on it, with the marks laid out across the parameter's authored range.
 *
 * One row per CHANNEL rather than per item, because that is the thing the per-channel split made possible
 * and the thing this sheet exists to make visible: an item can key opacity on a parameter its geometry
 * never touches, and a per-item row would hide that.
 *
 * Read-only for now - clicking a mark scrubs the parameter to it, which is the cheap half of the
 * interaction and the half that needs no authoring ops.  Dragging, box-select, and delete come with the
 * editing pass.
 *
 * キーフォームシート。選択パラメータについて、(オブジェクト, チャンネル)ごとのトラックを表示する。
 *
 * @param AreaScope scope The hosting area's scope.
 */
@Composable
internal fun KeyformSheetSpace(scope: AreaScope) {
	val colors = LocalUmamoColors.current
	val puppet = LocalPuppet.current
	val session = LocalEditorSession.current
	val liveParams = LocalLiveParams.current

	val parameterSelection by remember(session) {
		session?.parameterSelection ?: MutableStateFlow(ParameterSelection())
	}.collectAsState()

	// Channel and track labels are Umamo chrome, so they resolve from resources here and are injected into
	// the Compose-free projection. Item names are the user's own data and are never translated.
	// Resolved EAGERLY into a map rather than looked up inside the injected lambda: stringResource is
	// itself composable, so a lambda the projection calls later cannot reach it.
	val channelLabels = channelLabels()
	val geometryLabel = stringResource(Res.string.track_geometry)
	val blendShapeLabel = stringResource(Res.string.track_blend_shape)
	val labels =
		remember(channelLabels, geometryLabel, blendShapeLabel) {
			KeyformTrackLabels(
				channelName = { channel -> channelLabels.getValue(channel) },
				geometry = geometryLabel,
				blendShape = blendShapeLabel,
			)
		}

	val activeParameter = parameterSelection.active?.let { id -> puppet?.parameters?.firstOrNull { it.id == id } }
	if (puppet == null || activeParameter == null) {
		EmptySheetNotice(stringResource(Res.string.keyform_sheet_no_parameter))
		return
	}
	val projection =
		remember(puppet, activeParameter.id, labels.geometry, labels.blendShape) {
			keyformSheetRows(puppet, activeParameter.id, labels)
		}
	// Which keys are selected, so Delete has something to act on. Cleared when the projection changes,
	// because a row key can outlive the key it pointed at (a removal renumbers nothing, but a rebind can
	// replace the whole track) and a stale selection would delete the wrong thing.
	var selectedKeys by remember(projection) { mutableStateOf(emptySet<TrackKeyRef>()) }
	val rows =
		remember(projection, selectedKeys) {
			projection.rows.map { row ->
				row.copy(
					marks =
						row.marks.map { mark ->
							mark.copy(selected = TrackKeyRef(row.key, mark.position) in selectedKeys)
						},
				)
			}
		}
	if (rows.isEmpty()) {
		EmptySheetNotice(stringResource(Res.string.keyform_sheet_no_tracks))
		return
	}
	// Delete removes every selected key as ONE undo step. Registered as a command rather than wired to a
	// key handler here so the keymap owns the binding, per the action-registry rule; the sheet only
	// supplies what "the current selection" means while it is on screen.
	val commands = LocalCommands.current
	DisposableEffect(commands, session, projection, selectedKeys, activeParameter.id) {
		val deleteCommand =
			Command("keyform.deleteSelectedKeys", title = Res.string.cmd_keyform_delete_keys) {
				val removals =
					selectedKeys.mapNotNull { keyRef ->
						projection.targetsByRowKey[keyRef.rowKey]?.let { target ->
							Triple(target, activeParameter, keyRef.position)
						}
					}
				if (session != null && removals.isNotEmpty()) {
					session.removeChannelKeys(removals)
				}
			}
		commands.register(deleteCommand)
		onDispose { commands.unregister(deleteCommand.id) }
	}
	val (domainStart, domainEnd) = parameterDomain(activeParameter)
	TrackSheet(
		rows = rows,
		axis = TrackAxis(domainStart, domainEnd),
		// The playhead is the live scrub value, so the sheet reads as a view OF the current pose rather
		// than a static list beside it.
		playhead = liveParams?.values?.get(activeParameter.id) ?: activeParameter.default,
		modifier = Modifier.fillMaxSize(),
		// Clicking a mark selects it AND scrubs to it: selection is what Delete acts on, and scrubbing is
		// how you land the pose exactly on a key without hunting with the slider. The two never conflict,
		// so doing both is strictly more useful than choosing.
		onMarkClick = { row, mark ->
			selectedKeys = setOf(TrackKeyRef(row.key, mark.position))
			liveParams?.preview(activeParameter.id, mark.position)
			liveParams?.commit(setOf(activeParameter.id))
		},
		// Clicking empty track drops the selection, matching every other list in the editor.
		onTrackClick = { _, _ -> selectedKeys = emptySet() },
		onMarkDragEnd = { row, mark, releasedAt ->
			val target = projection.targetsByRowKey[row.key]
			if (session != null && target != null) {
				session.moveChannelKey(target, activeParameter, mark.position, releasedAt)
				// Follow the key to where it landed - the grid clamps at the neighbours, so the released
				// position and the stored one can differ, and the selection must track the STORED one.
				selectedKeys = emptySet()
			}
		},
	)
}

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
