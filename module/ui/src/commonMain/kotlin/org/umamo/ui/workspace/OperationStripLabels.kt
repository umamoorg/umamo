package org.umamo.ui.workspace

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import org.umamo.edit.ParameterUnit
import org.umamo.ui.model.ImportParameterKeys
import org.umamo.ui.model.RepackParameterKeys
import org.umamo.ui.resources.*
import org.umamo.ui.viewport.PlacementParameterKeys

/**
 * Maps an [org.umamo.edit.OperatorParameter.labelKey] to its localized row label.  The keys are the
 * ones each adjustable operation's parameter list declares; an unmapped key renders verbatim so a
 * newly added parameter never renders blank.
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
		ImportParameterKeys.ALPHA_THRESHOLD -> stringResource(Res.string.import_options_alpha_threshold)
		ImportParameterKeys.MARGIN -> stringResource(Res.string.import_options_margin)
		else -> labelKey
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