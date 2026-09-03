package org.umamo.ui.model

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.umamo.ui.resources.*

/**
 * Maps a [org.umamo.edit.Notice.messageKey] to its localized message.  The keys mirror those emitted by
 * :edit (which stays presentation-free, so it cannot hold display strings itself - the same pattern as
 * the history labels); an unmapped key falls back to a generic message so a newly added notice never
 * renders blank.
 *
 * @param String messageKey The notice's stable message key.
 * @return String The localized notice message.
 */
@Composable
fun noticeText(messageKey: String): String =
	when (messageKey) {
		"notice.transform.onlyDrawables" -> stringResource(Res.string.notice_transform_only_drawables)
		"notice.transform.deformed" -> stringResource(Res.string.notice_transform_deformed)
		"notice.keyform.noTarget" -> stringResource(Res.string.notice_keyform_no_target)
		"notice.keyform.noParameter" -> stringResource(Res.string.notice_keyform_no_parameter)
		"notice.merge.needsVertices" -> stringResource(Res.string.notice_merge_needs_vertices)
		"notice.connect.needsTwoVertices" -> stringResource(Res.string.notice_connect_needs_two_vertices)
		"notice.connect.refused" -> stringResource(Res.string.notice_connect_refused)
		"notice.rip.nothing" -> stringResource(Res.string.notice_rip_nothing)
		"notice.uv.noUvs" -> stringResource(Res.string.notice_uv_no_uvs)
		"notice.uv.noSelection" -> stringResource(Res.string.notice_uv_no_selection)
		"notice.uv.placement.layerAddressed" -> stringResource(Res.string.notice_uv_placement_layer_addressed)
		"notice.uv.placement.noPlacedArt" -> stringResource(Res.string.notice_uv_placement_no_placed_art)
		"notice.uv.placement.pageViewOnly" -> stringResource(Res.string.notice_uv_placement_page_view_only)
		"notice.uv.placement.notOnPage" -> stringResource(Res.string.notice_uv_placement_not_on_page)
		"notice.uv.placement.notDerivable" -> stringResource(Res.string.notice_uv_placement_not_derivable)
		"notice.uv.placement.overlap" -> stringResource(Res.string.notice_uv_placement_overlap)
		"notice.uv.placement.offPage" -> stringResource(Res.string.notice_uv_placement_off_page)
		"notice.uv.placement.pinned" -> stringResource(Res.string.notice_uv_placement_pinned)
		"notice.edit.noEditableGeometry" -> stringResource(Res.string.notice_edit_no_editable_geometry)
		"notice.display.partialSourceArtwork" -> stringResource(Res.string.notice_display_partial_source_artwork)
		"notice.display.sourceArtworkUnavailable" -> stringResource(Res.string.notice_display_source_artwork_unavailable)
		"notice.atlas.repacked" -> stringResource(Res.string.notice_atlas_repacked)
		"notice.atlas.repackSuperseded" -> stringResource(Res.string.notice_atlas_repack_superseded)
		"notice.atlas.repackUnchanged" -> stringResource(Res.string.notice_atlas_repack_unchanged)
		"notice.proportional.on" -> stringResource(Res.string.notice_proportional_on)
		"notice.proportional.off" -> stringResource(Res.string.notice_proportional_off)
		"notice.proportional.connected.on" -> stringResource(Res.string.notice_proportional_connected_on)
		"notice.proportional.connected.off" -> stringResource(Res.string.notice_proportional_connected_off)
		else -> stringResource(Res.string.notice_unknown)
	}