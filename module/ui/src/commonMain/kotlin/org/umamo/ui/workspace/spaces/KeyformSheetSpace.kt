package org.umamo.ui.workspace.spaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ParameterSelection
import org.umamo.runtime.model.FormChannel
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
	val rows =
		remember(puppet, activeParameter.id, labels.geometry, labels.blendShape) {
			keyformSheetRows(puppet, activeParameter.id, labels)
		}
	if (rows.isEmpty()) {
		EmptySheetNotice(stringResource(Res.string.keyform_sheet_no_tracks))
		return
	}
	val (domainStart, domainEnd) = parameterDomain(activeParameter)
	TrackSheet(
		rows = rows,
		axis = TrackAxis(domainStart, domainEnd),
		// The playhead is the live scrub value, so the sheet reads as a view OF the current pose rather
		// than a static list beside it.
		playhead = liveParams?.values?.get(activeParameter.id) ?: activeParameter.default,
		modifier = Modifier.fillMaxSize(),
		// Clicking a mark scrubs to it - the read-only half of the interaction, and how you get the pose
		// exactly onto a key without hunting with the slider.
		onMarkClick = { _, mark ->
			liveParams?.preview(activeParameter.id, mark.position)
			liveParams?.commit(setOf(activeParameter.id))
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
