package org.umamo.ui.workspace

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.MergeParameterKeys
import org.umamo.edit.MergeTarget
import org.umamo.edit.ParameterUnit
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformParameterKeys
import org.umamo.edit.choiceKey
import org.umamo.edit.parameterKey
import org.umamo.interop.art.ArtworkAnchor
import org.umamo.ui.model.ImportParameterKeys
import org.umamo.ui.model.MatchParameterKeys
import org.umamo.ui.model.RepackParameterKeys
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.PlacementParameterKeys
import org.umamo.ui.viewport.falloffLabel

/**
 * Maps an [org.umamo.edit.OperatorParameter.labelKey] to its localized row label.  The keys are the
 * ones each adjustable operation's parameter list declares (a choice's entries carry prefixed keys,
 * resolved by [choiceLabel]); an unmapped key renders verbatim so a newly added parameter never
 * renders blank.
 *
 * @param String labelKey The parameter's stable label key.
 * @return String The localized label.
 */
@Composable
internal fun operatorParameterLabel(labelKey: String): String =
	when (labelKey) {
		RepackParameterKeys.PAGE_SIZE -> stringResource(Res.string.repack_options_page_size)
		RepackParameterKeys.GUTTER -> stringResource(Res.string.repack_options_gutter)
		RepackParameterKeys.EXTRUDE -> stringResource(Res.string.repack_options_extrude)
		RepackParameterKeys.ALLOW_ROTATION -> stringResource(Res.string.repack_options_allow_rotation)
		RepackParameterKeys.KEEP_PINNED -> stringResource(Res.string.repack_options_keep_pinned)
		RepackParameterKeys.POWER_OF_TWO -> stringResource(Res.string.repack_options_power_of_two)
		RepackParameterKeys.SQUARE_PAGES -> stringResource(Res.string.repack_options_square_pages)
		RepackParameterKeys.SHRINK_PAGES -> stringResource(Res.string.repack_options_shrink_pages)
		RepackParameterKeys.ALPHA_THRESHOLD -> stringResource(Res.string.repack_options_alpha_threshold)
		PlacementParameterKeys.DELTA_X -> stringResource(Res.string.placement_options_move_x)
		PlacementParameterKeys.DELTA_Y -> stringResource(Res.string.placement_options_move_y)
		PlacementParameterKeys.ANGLE -> stringResource(Res.string.placement_options_angle)
		PlacementParameterKeys.SCALE_X -> stringResource(Res.string.placement_options_scale_x)
		PlacementParameterKeys.SCALE_Y -> stringResource(Res.string.placement_options_scale_y)
		ImportParameterKeys.ALIGN -> stringResource(Res.string.import_options_align)
		ImportParameterKeys.OFFSET_X -> stringResource(Res.string.import_options_offset_x)
		ImportParameterKeys.OFFSET_Z -> stringResource(Res.string.import_options_offset_z)
		ImportParameterKeys.ALPHA_THRESHOLD -> stringResource(Res.string.import_options_alpha_threshold)
		ImportParameterKeys.MARGIN -> stringResource(Res.string.import_options_margin)
		MatchParameterKeys.THRESHOLD -> stringResource(Res.string.match_options_threshold)
		// The transform rows share the placement rows' Move / Angle / Scale labels where the text is the
		// same; the viewport's vertical axis is Z (Y+ forward, Z+ up), so it has labels of its own.
		TransformParameterKeys.MOVE_X -> stringResource(Res.string.placement_options_move_x)
		TransformParameterKeys.MOVE_Y -> stringResource(Res.string.placement_options_move_y)
		TransformParameterKeys.MOVE_Z -> stringResource(Res.string.transform_options_move_z)
		TransformParameterKeys.ANGLE -> stringResource(Res.string.placement_options_angle)
		TransformParameterKeys.SCALE_X -> stringResource(Res.string.placement_options_scale_x)
		TransformParameterKeys.SCALE_Y -> stringResource(Res.string.placement_options_scale_y)
		TransformParameterKeys.SCALE_Z -> stringResource(Res.string.transform_options_scale_z)
		TransformParameterKeys.PROPORTIONAL -> stringResource(Res.string.transform_options_proportional)
		TransformParameterKeys.FALLOFF -> stringResource(Res.string.transform_options_falloff)
		TransformParameterKeys.PROPORTIONAL_SIZE -> stringResource(Res.string.transform_options_proportional_size)
		TransformParameterKeys.CONNECTED_ONLY -> stringResource(Res.string.transform_options_connected)
		TransformParameterKeys.SLIDE_FACTOR -> stringResource(Res.string.transform_options_slide_factor)
		MergeParameterKeys.TARGET -> stringResource(Res.string.merge_options_target)
		else -> choiceLabel(labelKey) ?: labelKey
	}

/**
 * The localized label of a choice entry's prefixed key: a falloff curve's, a merge target's, or an
 * import anchor's.
 *
 * @param String labelKey The entry's label key.
 * @return String? The label, or null when the key carries none of the prefixes.
 */
@Composable
private fun choiceLabel(labelKey: String): String? {
	if (labelKey.startsWith(TransformParameterKeys.FALLOFF_CHOICE_PREFIX)) {
		val key = labelKey.removePrefix(TransformParameterKeys.FALLOFF_CHOICE_PREFIX)
		return ProportionalFalloff.entries.firstOrNull { falloff -> falloff.choiceKey == key }?.let { falloff -> falloffLabel(falloff) }
	}
	if (labelKey.startsWith(ImportParameterKeys.ANCHOR_CHOICE_PREFIX)) {
		val key = labelKey.removePrefix(ImportParameterKeys.ANCHOR_CHOICE_PREFIX)
		return ArtworkAnchor.entries.firstOrNull { anchor -> anchor.key == key }?.let { anchor -> artworkAnchorLabel(anchor) }
	}
	if (labelKey.startsWith(MergeParameterKeys.TARGET_CHOICE_PREFIX)) {
		val key = labelKey.removePrefix(MergeParameterKeys.TARGET_CHOICE_PREFIX)
		return when (MergeTarget.entries.firstOrNull { target -> target.parameterKey == key }) {
			MergeTarget.AtCenter -> stringResource(Res.string.merge_target_center)
			MergeTarget.AtFirst -> stringResource(Res.string.merge_target_first)
			MergeTarget.AtLast -> stringResource(Res.string.merge_target_last)
			null -> null
		}
	}
	return null
}

/**
 * The localized name of an import anchor, shared by the strip's Align row and the Import settings
 * row that seeds it.
 *
 * @param ArtworkAnchor anchor The anchor.
 * @return String The label.
 */
@Composable
internal fun artworkAnchorLabel(anchor: ArtworkAnchor): String =
	when (anchor) {
		ArtworkAnchor.TopLeft -> stringResource(Res.string.import_anchor_top_left)
		ArtworkAnchor.Top -> stringResource(Res.string.import_anchor_top)
		ArtworkAnchor.TopRight -> stringResource(Res.string.import_anchor_top_right)
		ArtworkAnchor.Left -> stringResource(Res.string.import_anchor_left)
		ArtworkAnchor.Center -> stringResource(Res.string.import_anchor_center)
		ArtworkAnchor.Right -> stringResource(Res.string.import_anchor_right)
		ArtworkAnchor.BottomLeft -> stringResource(Res.string.import_anchor_bottom_left)
		ArtworkAnchor.Bottom -> stringResource(Res.string.import_anchor_bottom)
		ArtworkAnchor.BottomRight -> stringResource(Res.string.import_anchor_bottom_right)
	}

/**
 * The suffix a numeric row shows after its value for [unit], or null for a unitless one.
 *
 * @param ParameterUnit unit The parameter's display unit.
 * @return String? The localized unit suffix.
 */
@Composable
internal fun parameterUnitSuffix(unit: ParameterUnit): String? =
	when (unit) {
		ParameterUnit.None -> null
		ParameterUnit.Pixels -> stringResource(Res.string.unit_pixels)
		ParameterUnit.Degrees -> stringResource(Res.string.unit_degrees)
		ParameterUnit.Percent -> stringResource(Res.string.unit_percent)
	}