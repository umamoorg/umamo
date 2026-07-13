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
		"notice.merge.needsVertices" -> stringResource(Res.string.notice_merge_needs_vertices)
		"notice.connect.needsTwoVertices" -> stringResource(Res.string.notice_connect_needs_two_vertices)
		"notice.connect.refused" -> stringResource(Res.string.notice_connect_refused)
		"notice.rip.nothing" -> stringResource(Res.string.notice_rip_nothing)
		"notice.uv.noUvs" -> stringResource(Res.string.notice_uv_no_uvs)
		"notice.proportional.on" -> stringResource(Res.string.notice_proportional_on)
		"notice.proportional.off" -> stringResource(Res.string.notice_proportional_off)
		"notice.proportional.connected.on" -> stringResource(Res.string.notice_proportional_connected_on)
		"notice.proportional.connected.off" -> stringResource(Res.string.notice_proportional_connected_off)
		else -> stringResource(Res.string.notice_unknown)
	}
