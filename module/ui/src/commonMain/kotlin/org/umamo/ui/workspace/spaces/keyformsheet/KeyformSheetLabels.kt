package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource
import org.umamo.runtime.model.FormChannel
import org.umamo.ui.properties.formChannelLabelRes
import org.umamo.ui.resources.*

/**
 * The sheet's localized chrome, resolved where the sheet composes and handed down whole.
 *
 * Resolved EAGERLY into plain values rather than looked up where they are used: stringResource is itself
 * composable, so neither the Compose-free projection nor a menu built for a pointer event can reach it.
 * Channel and owner-kind labels are Umamo chrome; item names are the user's own data and are never
 * translated, so none are here.
 *
 * Immutable so Compose compares it by value: a holder built again from the same strings leaves every
 * section that takes it skippable.
 *
 * @param Map channelNames   Each channel's short display label.
 * @param Map ownerKindNames Each owner kind's display label, the subtitle under a group row's name.
 * @param String geometry    The label of a geometry track.
 * @param String blendShape  The label of a blend-shape track.
 * @param String insertKey   The lane menu entry that inserts a key.
 * @param String deleteKey   The lane menu entry that deletes a key.
 * @param Map selectOwner    The group label menu entry that selects the row's owner, per owner kind.
 */
@Immutable
internal data class KeyformSheetLabels(
	val channelNames: Map<FormChannel, String>,
	val ownerKindNames: Map<KeyformOwnerKind, String>,
	val geometry: String,
	val blendShape: String,
	val insertKey: String,
	val deleteKey: String,
	val selectOwner: Map<KeyformOwnerKind, String>,
)

/**
 * Resolves the sheet's chrome in the current locale.
 *
 * @return KeyformSheetLabels The labels, which follow a live locale switch like any other string read.
 */
@Composable
internal fun keyformSheetLabels(): KeyformSheetLabels {
	val ownerKindNames = ownerKindLabels()

	return KeyformSheetLabels(
		channelNames = channelLabels(),
		ownerKindNames = ownerKindNames,
		geometry = stringResource(Res.string.track_geometry),
		blendShape = stringResource(Res.string.track_blend_shape),
		insertKey = stringResource(Res.string.cmd_keyform_insert),
		deleteKey = stringResource(Res.string.cmd_keyform_delete),
		selectOwner = ownerKindNames.mapValues { (_, kindLabel) -> stringResource(Res.string.keyform_sheet_select_owner, kindLabel) },
	)
}

/**
 * The labels the Compose-free projection is handed, kept across recompositions while the strings behind
 * them are unchanged, so the projection is not rebuilt for nothing.
 *
 * @param KeyformSheetLabels labels The sheet's chrome.
 * @return KeyformTrackLabels The projection's labels.
 */
@Composable
internal fun rememberKeyformTrackLabels(labels: KeyformSheetLabels): KeyformTrackLabels {
	val channelNames = labels.channelNames
	val ownerKindNames = labels.ownerKindNames

	return remember(channelNames, ownerKindNames, labels.geometry, labels.blendShape) {
		KeyformTrackLabels(
			channelName = { channel -> channelNames.getValue(channel) },
			geometry = labels.geometry,
			blendShape = labels.blendShape,
			ownerKindName = { kind -> ownerKindNames.getValue(kind) },
		)
	}
}

/**
 * Every channel's localized short label, resolved in one pass.
 *
 * A map rather than a function because the projection is Compose-free and calls its label lookup from
 * ordinary code, where stringResource is unreachable.  The labels themselves come from
 * [formChannelLabelRes], shared with the export report so a channel reads the same in both.
 *
 * @return Map<FormChannel, String> The label per channel.
 */
@Composable
private fun channelLabels(): Map<FormChannel, String> =
	FormChannel.entries.associateWith { channel -> stringResource(formChannelLabelRes(channel)) }

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